@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
set "PS_SCRIPT=%SCRIPT_DIR%scripts\deploy-module.ps1"

if not exist "%PS_SCRIPT%" (
  echo Missing script: "%PS_SCRIPT%"
  exit /b 1
)

where powershell >nul 2>nul
if errorlevel 1 (
  echo powershell.exe was not found in PATH.
  exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%PS_SCRIPT%" %*
exit /b %ERRORLEVEL%

