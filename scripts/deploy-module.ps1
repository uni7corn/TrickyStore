[CmdletBinding()]
param(
    [switch]$Help,

    [string]$Device = $env:ANDROID_SERIAL,

    [ValidateSet("Release", "Debug")]
    [string]$Variant = "Release",

    [switch]$Build,
    [switch]$NoClean,
    [string]$TrickyStoreZip,

    [switch]$SkipZygiskNext,
    [switch]$ForceZygiskNext,
    [string]$ZygiskNextZipUrl,
    [string]$ZygiskNextUpdateJson = "https://api.nullptr.icu/android/zygisk-next/static/update.json",

    [string]$ProxyUrl,
    [switch]$SkipProxyConfig,

    [switch]$NoReboot,
    [switch]$WaitAfterReboot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($Help) {
    @"
Usage:
  deploy-module.bat -Device 10.0.0.205:5556

Common options:
  -Device <serial|ip:port>     ADB device, for example 10.0.0.205:5556.
  -Variant Release|Debug       Select ZIP variant. Default: Release.
  -Build                       Build before install. Default: use latest existing ZIP.
  -TrickyStoreZip <path>       Explicit TrickyStore module ZIP path.
  -NoClean                     Skip Gradle clean before building.
  -SkipZygiskNext              Do not install Zygisk Next if missing.
  -ForceZygiskNext             Reinstall Zygisk Next even if present.
  -ZygiskNextZipUrl <url>      Use a specific Zygisk Next ZIP URL.
  -ProxyUrl <url>              Write proxy.txt. Omit to preserve the device's current/default URL.
  -SkipProxyConfig             Do not write proxy.txt.
  -NoReboot                    Do not reboot after installing modules.
  -WaitAfterReboot             Wait for boot and verify modules after reboot.

Examples:
  deploy-module.bat -Device 10.0.0.205:5556
  deploy-module.bat -Device 10.0.0.205:5556 -NoReboot
  deploy-module.bat -Device 10.0.0.205:5556 -WaitAfterReboot
  deploy-module.bat -Device 10.0.0.205:5556 -ForceZygiskNext
"@
    exit 0
}

$script:RepoRoot = Split-Path -Parent $PSScriptRoot
$script:Adb = (Get-Command adb -ErrorAction Stop).Source
$script:AdbSerialArgs = [string[]]@()
$script:RootManager = $null

function Write-Step {
    param([string]$Message)
    Write-Host ""
    Write-Host "==> $Message"
}

function Join-CommandOutput {
    param([object[]]$Output)

    if ($null -eq $Output) {
        return ""
    }

    $lines = foreach ($item in @($Output)) {
        if ($null -ne $item) {
            [string]$item
        }
    }

    return ($lines -join [Environment]::NewLine)
}

function Invoke-Adb {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Args,
        [switch]$AllowFailure
    )

    $allArgs = @()
    if (@($script:AdbSerialArgs).Count -gt 0) {
        $allArgs += $script:AdbSerialArgs
    }
    $allArgs += $Args

    $oldErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & $script:Adb @allArgs 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $oldErrorActionPreference
    }
    $text = Join-CommandOutput $output

    if ($exitCode -ne 0 -and -not $AllowFailure) {
        throw "adb $($allArgs -join ' ') failed with exit code $exitCode`n$text"
    }

    return [pscustomobject]@{
        ExitCode = $exitCode
        Output = $text
    }
}

function Invoke-AdbNoSerial {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Args,
        [switch]$AllowFailure
    )

    $oldErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & $script:Adb @Args 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $oldErrorActionPreference
    }
    $text = Join-CommandOutput $output

    if ($exitCode -ne 0 -and -not $AllowFailure) {
        throw "adb $($Args -join ' ') failed with exit code $exitCode`n$text"
    }

    return [pscustomobject]@{
        ExitCode = $exitCode
        Output = $text
    }
}

function Invoke-RootShell {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Command,
        [switch]$UseMagiskMountNamespace,
        [switch]$AllowFailure
    )

    $quotedCommand = ConvertTo-ShellSingleQuoted $Command
    if ($UseMagiskMountNamespace) {
        return Invoke-Adb -Args @("shell", "su -M -c $quotedCommand") -AllowFailure:$AllowFailure
    }

    return Invoke-Adb -Args @("shell", "su -c $quotedCommand") -AllowFailure:$AllowFailure
}

function ConvertTo-ShellSingleQuoted {
    param([Parameter(Mandatory = $true)][string]$Value)
    return "'" + $Value.Replace("'", "'\''") + "'"
}

function ConvertFrom-PropText {
    param([string]$Text)

    $map = @{}
    foreach ($line in ($Text -split "`r?`n")) {
        if ($line -match "^\s*#") {
            continue
        }
        if ($line -match "^\s*([^=]+)=(.*)$") {
            $map[$matches[1].Trim()] = $matches[2]
        }
    }

    return ,$map
}

function Get-DeviceModuleProp {
    param([Parameter(Mandatory = $true)][string]$ModuleId)

    $cmd = "if [ -f /data/adb/modules_update/$ModuleId/module.prop ]; then cat /data/adb/modules_update/$ModuleId/module.prop; elif [ -f /data/adb/modules/$ModuleId/module.prop ]; then cat /data/adb/modules/$ModuleId/module.prop; fi"
    $result = Invoke-RootShell -Command $cmd
    if ([string]::IsNullOrWhiteSpace($result.Output)) {
        return @{}
    }

    return ConvertFrom-PropText $result.Output
}

function Get-PropInt {
    param(
        [hashtable]$Props,
        [string]$Name
    )

    if (-not $Props.ContainsKey($Name)) {
        return $null
    }

    $value = 0
    if ([int]::TryParse($Props[$Name], [ref]$value)) {
        return $value
    }

    return $null
}

function Get-AdbDeviceState {
    $list = (Invoke-AdbNoSerial -Args @("devices") -AllowFailure).Output -split "`r?`n"
    foreach ($line in $list) {
        if ($line -match "^\s*$") {
            continue
        }

        $parts = @($line -split "\s+")
        if ($parts.Count -ge 2 -and $parts[0] -eq $Device) {
            return $parts[1]
        }
    }

    return ""
}

function Wait-AdbDeviceReady {
    param([int]$TimeoutSeconds = 180)

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $attempt = 0
    while ((Get-Date) -lt $deadline) {
        if ($Device -match ":\d+$" -and ($attempt % 3) -eq 0) {
            Invoke-AdbNoSerial -Args @("connect", $Device) -AllowFailure | Out-Null
        }

        $state = Get-AdbDeviceState
        if ($state -eq "device") {
            return
        }
        if ($state -eq "unauthorized") {
            throw "Device $Device is unauthorized. Approve the ADB debugging prompt on the device, then rerun the script."
        }

        Start-Sleep -Seconds 1
        $attempt++
    }

    throw "Timed out waiting for device $Device. Last adb state: $(Get-AdbDeviceState)"
}

function Initialize-Device {
    if ([string]::IsNullOrWhiteSpace($Device)) {
        $devices = @((Invoke-AdbNoSerial -Args @("devices")).Output -split "`r?`n" |
            Where-Object { $_ -match "^\S+\s+device(\s|$)" } |
            ForEach-Object { ($_ -split "\s+")[0] })

        if ($devices.Count -ne 1) {
            throw "Specify -Device, or set ANDROID_SERIAL. Connected devices found: $($devices -join ', ')"
        }
        $script:Device = $devices[0]
    }

    if ($Device -match "^\d{1,3}(\.\d{1,3}){3}$") {
        $script:Device = "${Device}:5555"
    }

    if ($Device -match ":\d+$") {
        Write-Step "Connecting $Device"
        Write-Host (Invoke-AdbNoSerial -Args @("connect", $Device)).Output
    }

    $script:AdbSerialArgs = [string[]]@("-s", $Device)
    Wait-AdbDeviceReady

    Write-Host "Using device: $Device"
}

function Detect-RootManager {
    Write-Step "Checking root manager"

    $rootCheck = Invoke-RootShell -Command "id" -AllowFailure
    if ($rootCheck.ExitCode -ne 0 -or $rootCheck.Output -notmatch "uid=0") {
        throw "Root shell is unavailable. Approve the su request on the device and retry."
    }

    $probe = Invoke-RootShell -Command "if [ -x /data/adb/ksud ] || command -v ksud >/dev/null 2>&1; then echo KSU=1; else echo KSU=0; fi; if command -v magisk >/dev/null 2>&1 || [ -x /sbin/magisk ] || [ -x /debug_ramdisk/magisk ]; then echo MAGISK=1; else echo MAGISK=0; fi"
    $hasKsu = $probe.Output -match "KSU=1"
    $hasMagisk = $probe.Output -match "MAGISK=1"

    if ($hasKsu -and $hasMagisk) {
        throw "Both KernelSU and Magisk were detected. This module aborts on mixed root implementations."
    }
    if ($hasKsu) {
        $script:RootManager = "KernelSU"
    } elseif ($hasMagisk) {
        $script:RootManager = "Magisk"
    } else {
        throw "Neither KernelSU nor Magisk was detected."
    }

    Write-Host "Root manager: $script:RootManager"
}

function Install-ModuleZip {
    param(
        [Parameter(Mandatory = $true)]
        [string]$LocalZip,
        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    if (-not (Test-Path $LocalZip)) {
        throw "$Name zip not found: $LocalZip"
    }

    $fileName = [IO.Path]::GetFileName($LocalZip)
    $remotePath = "/data/local/tmp/$fileName"

    Write-Step "Pushing $Name"
    Write-Host (Invoke-Adb -Args @("push", $LocalZip, $remotePath)).Output

    Write-Step "Installing $Name"
    $quotedRemotePath = ConvertTo-ShellSingleQuoted $remotePath

    if ($script:RootManager -eq "KernelSU") {
        Write-Host (Invoke-RootShell -Command "/data/adb/ksud module install $quotedRemotePath").Output
        return
    }

    Write-Host (Invoke-RootShell -UseMagiskMountNamespace -Command "magisk --install-module $quotedRemotePath").Output
}

function Install-ZygiskNextIfNeeded {
    if ($SkipZygiskNext) {
        Write-Step "Skipping Zygisk Next"
        return
    }

    Write-Step "Checking Zygisk Next"
    $installed = Get-DeviceModuleProp -ModuleId "zygisksu"

    $zipUrl = $ZygiskNextZipUrl
    $remoteVersionCode = $null
    $remoteVersion = $null

    $needsInstall = $ForceZygiskNext -or -not $installed.ContainsKey("id") -or -not [string]::IsNullOrWhiteSpace($ZygiskNextZipUrl)

    if (-not $needsInstall) {
        $label = $installed["version"]
        if ($null -eq $label) {
            $label = "versionCode=$($installed["versionCode"])"
        }
        Write-Host "Zygisk Next already installed: $label"
        return
    }

    if ([string]::IsNullOrWhiteSpace($zipUrl)) {
        $update = Invoke-RestMethod -Uri $ZygiskNextUpdateJson -UseBasicParsing
        if ([string]::IsNullOrWhiteSpace($update.zipUrl)) {
            throw "Zygisk Next update JSON does not include zipUrl: $ZygiskNextUpdateJson"
        }
        $zipUrl = $update.zipUrl
        $remoteVersion = $update.version
        if ($null -ne $update.versionCode) {
            $remoteVersionCode = [int]$update.versionCode
        }
    }

    if ($installed.ContainsKey("id")) {
        Write-Host "Installed Zygisk Next: $($installed["version"])"
    } else {
        Write-Host "Zygisk Next is not installed."
    }
    if ($null -ne $remoteVersionCode) {
        Write-Host "Remote Zygisk Next: $remoteVersion ($remoteVersionCode)"
    }

    $deployDir = Join-Path $script:RepoRoot "build\deploy"
    New-Item -ItemType Directory -Force $deployDir | Out-Null

    $uri = [Uri]$zipUrl
    $fileName = [IO.Path]::GetFileName($uri.AbsolutePath)
    if ([string]::IsNullOrWhiteSpace($fileName) -or -not $fileName.EndsWith(".zip", [StringComparison]::OrdinalIgnoreCase)) {
        $fileName = "Zygisk-Next.zip"
    }

    $localZip = Join-Path $deployDir $fileName
    Write-Step "Downloading Zygisk Next"
    Write-Host $zipUrl
    Invoke-WebRequest -Uri $zipUrl -OutFile $localZip -UseBasicParsing

    Install-ModuleZip -LocalZip $localZip -Name "Zygisk Next"
}

function Invoke-GradleBuild {
    if (-not $Build) {
        Write-Step "Using existing TrickyStore zip"
        return
    }

    Write-Step "Building TrickyStore $Variant"
    $variantTask = ":module:zip$Variant"
    $gradleArgs = @()
    if (-not $NoClean) {
        $gradleArgs += @(":service:clean", ":module:clean")
    }
    $gradleArgs += $variantTask

    Push-Location $script:RepoRoot
    try {
        & ".\gradlew.bat" @gradleArgs
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle failed with exit code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
}

function Get-TrickyStoreZip {
    if (-not [string]::IsNullOrWhiteSpace($TrickyStoreZip)) {
        if ([IO.Path]::IsPathRooted($TrickyStoreZip)) {
            return (Resolve-Path $TrickyStoreZip).Path
        }
        return (Resolve-Path (Join-Path $script:RepoRoot $TrickyStoreZip)).Path
    }

    $variantLower = $Variant.ToLowerInvariant()
    $releaseDir = Join-Path $script:RepoRoot "module\release"
    $zip = Get-ChildItem -Path $releaseDir -Filter "*-$variantLower.zip" |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1

    if ($null -eq $zip) {
        throw "No TrickyStore $Variant zip found under $releaseDir"
    }

    return $zip.FullName
}

function Test-TrickyStoreChecksum {
    param([string]$VariantLower)

    $moduleDir = Join-Path $script:RepoRoot "module\build\outputs\module\$VariantLower"
    $propFile = Join-Path $moduleDir "module.prop"
    $apkFile = Join-Path $moduleDir "service.apk"

    if (-not (Test-Path $propFile) -or -not (Test-Path $apkFile)) {
        Write-Host "Checksum preflight skipped; build output not found."
        return
    }

    $props = ConvertFrom-PropText (Get-Content -Raw $propFile)
    $fields = @("id", "name", "version", "versionCode", "author", "description")
    foreach ($field in $fields) {
        if (-not $props.ContainsKey($field)) {
            throw "module.prop is missing required field: $field"
        }
    }

    $payload = (($fields | ForEach-Object { $props[$_] }) -join "")
    $sha = [System.Security.Cryptography.SHA256]::Create()
    $expected = ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($payload)))).Replace("-", "").ToLowerInvariant()
    $apkText = [Text.Encoding]::ASCII.GetString([IO.File]::ReadAllBytes($apkFile))

    if (-not $apkText.Contains($expected)) {
        throw "service.apk CHECKSUM mismatch. Expected $expected from module.prop, but the APK does not contain it. Re-run without -NoClean."
    }

    Write-Host "Verified TrickyStore service checksum: $expected"
}

function Write-ProxyConfig {
    if ($SkipProxyConfig) {
        Write-Step "Skipping proxy config"
        return
    }

    if ([string]::IsNullOrWhiteSpace($ProxyUrl)) {
        Write-Step "Skipping proxy config"
        Write-Host "ProxyUrl is empty."
        return
    }

    Write-Step "Writing proxy config"
    $quotedProxyUrl = ConvertTo-ShellSingleQuoted $ProxyUrl.Trim()
    Write-Host "proxy.txt = $($ProxyUrl.Trim())"
    Invoke-RootShell -Command "mkdir -p /data/adb/tricky_store; printf '%s\n' $quotedProxyUrl > /data/adb/tricky_store/proxy.txt" | Out-Null
}

function Reboot-And-Wait {
    if ($NoReboot) {
        Write-Step "Reboot skipped"
        Write-Host "Modules are installed, but changes usually take effect after reboot."
        return
    }

    Write-Step "Rebooting device"
    Invoke-Adb -Args @("reboot") | Out-Null
    Wait-AdbDeviceReady

    Write-Host "Waiting for boot_completed=1"
    for ($i = 0; $i -lt 180; $i++) {
        Start-Sleep -Seconds 1
        $boot = (Invoke-Adb -Args @("shell", "getprop", "sys.boot_completed") -AllowFailure).Output.Trim()
        if ($boot -eq "1") {
            Write-Host "Device boot completed."
            return
        }
    }

    throw "Timed out waiting for device boot completion."
}

function Get-TrickyStoreProcess {
    $process = Invoke-RootShell -Command "ps -A | grep -i TrickyStore | grep -v grep || true"
    return $process.Output.Trim()
}

function Ensure-TrickyStoreProcess {
    $process = Get-TrickyStoreProcess
    if (-not [string]::IsNullOrWhiteSpace($process)) {
        Write-Host "TrickyStore process:"
        Write-Host $process
        return
    }

    Write-Host "TrickyStore process was not found; starting service.sh once."
    Invoke-RootShell -Command "cd /data/adb/modules/tricky_store && (sh service.sh >/dev/null 2>&1 &)" | Out-Null
    Start-Sleep -Seconds 3

    $process = Get-TrickyStoreProcess
    if ([string]::IsNullOrWhiteSpace($process)) {
        throw "TrickyStore process did not start after running service.sh."
    }

    Write-Host "TrickyStore process:"
    Write-Host $process
}

function Verify-Install {
    Write-Step "Verifying modules"

    $tricky = Get-DeviceModuleProp -ModuleId "tricky_store"
    if (-not $tricky.ContainsKey("id")) {
        throw "tricky_store is not installed."
    }
    Write-Host "TrickyStore: $($tricky["version"]) versionCode=$($tricky["versionCode"])"

    $zygisk = Get-DeviceModuleProp -ModuleId "zygisksu"
    if ($zygisk.ContainsKey("id")) {
        Write-Host "Zygisk Next: $($zygisk["version"]) versionCode=$($zygisk["versionCode"])"
    } elseif (-not $SkipZygiskNext) {
        throw "Zygisk Next is not installed."
    }

    if (-not $NoReboot) {
        Ensure-TrickyStoreProcess
    }
}

try {
    Push-Location $script:RepoRoot
    try {
        Initialize-Device
        Detect-RootManager
        Install-ZygiskNextIfNeeded
        Invoke-GradleBuild

        $variantLower = $Variant.ToLowerInvariant()
        if ($Build) {
            Test-TrickyStoreChecksum -VariantLower $variantLower
        }

        $zip = Get-TrickyStoreZip
        Write-Step "Selected TrickyStore zip"
        Write-Host $zip

        Install-ModuleZip -LocalZip $zip -Name "TrickyStore"
        Write-ProxyConfig

        if ($NoReboot) {
            Write-Step "Reboot skipped"
            Write-Host "Modules are installed, but changes usually take effect after reboot."
            Verify-Install
        } elseif ($WaitAfterReboot) {
            Reboot-And-Wait
            Detect-RootManager
            Verify-Install
        } else {
            Write-Step "Rebooting device"
            Invoke-Adb -Args @("reboot") | Out-Null
            Write-Host "Reboot command sent. No post-reboot steps will run."
        }

        Write-Step "Done"
    } finally {
        Pop-Location
    }
} catch {
    [Console]::Error.WriteLine("")
    [Console]::Error.WriteLine("ERROR: $($_.Exception.Message)")
    exit 1
}
