package io.github.a13e300.tricky_store

import android.hardware.security.keymint.KeyParameter
import android.hardware.security.keymint.KeyParameterValue
import android.hardware.security.keymint.Tag
import android.os.IBinder
import android.os.Parcel
import android.system.keystore2.Authorization
import android.system.keystore2.CreateOperationResponse
import android.system.keystore2.IKeystoreSecurityLevel
import android.system.keystore2.KeyDescriptor
import android.system.keystore2.KeyEntryResponse
import android.system.keystore2.KeyMetadata
import io.github.a13e300.tricky_store.binder.BinderInterceptor
import io.github.a13e300.tricky_store.proxy.ProxyClient
import io.github.a13e300.tricky_store.proxy.ProxyOperationBinder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class SecurityLevelInterceptor(
    private val original: IKeystoreSecurityLevel,
    private val level: Int
) : BinderInterceptor() {
    companion object {
        private val generateKeyTransaction =
            getTransactCode(IKeystoreSecurityLevel.Stub::class.java, "generateKey")
        private val createOperationTransaction by lazy {
            getTransactCode(IKeystoreSecurityLevel.Stub::class.java, "createOperation")
        }
        private const val DOMAIN_KEY_ID = 4
        // Maps local key alias → proxy alias for proxy-generated keys.
        // In-memory only (per keystore2 process lifetime): NOT persisted to disk, so the
        // mapping is dropped on daemon/device restart. Nothing is reused across sessions —
        // every generateKey signs a fresh cert on the remote. It exists purely to serve the
        // in-session key lifecycle (getKeyEntry read-back, createOperation, deleteKey, and
        // an app-supplied attestKeyAlias generated earlier in the same session).
        private val proxyAliases = ConcurrentHashMap<Key, ProxyKeyInfo>()
        private val proxyKeyIds = ConcurrentHashMap<KeyId, ProxyKeyInfo>()
        private val nextProxyKeyId = AtomicLong(System.currentTimeMillis() shl 16)
        private val loggedTransactions = ConcurrentHashMap.newKeySet<String>()

        // Tags a remote device cannot satisfy on behalf of this device:
        //  - ATTESTATION_ID_*: a device can only attest its OWN IDs → CANNOT_ATTEST_IDS.
        //  - INCLUDE_UNIQUE_ID: needs the unique-ID-attestation permission the remote
        //    proxy app lacks → PERMISSION_DENIED. Strip them before forwarding.
        private val nonProxyableTags = setOf(
            Tag.ATTESTATION_ID_BRAND, Tag.ATTESTATION_ID_DEVICE, Tag.ATTESTATION_ID_PRODUCT,
            Tag.ATTESTATION_ID_SERIAL, Tag.ATTESTATION_ID_IMEI, Tag.ATTESTATION_ID_MEID,
            Tag.ATTESTATION_ID_MANUFACTURER, Tag.ATTESTATION_ID_MODEL, Tag.ATTESTATION_ID_SECOND_IMEI,
            Tag.INCLUDE_UNIQUE_ID
        )

        fun getProxyKeyResponse(uid: Int, alias: String): KeyEntryResponse? =
            proxyAliases[Key(uid, alias)]?.response

        fun removeProxyKey(uid: Int, alias: String): Boolean {
            val removed = proxyAliases.remove(Key(uid, alias)) ?: return false
            proxyKeyIds.remove(KeyId(uid, removed.keyId))
            return true
        }

        fun getProxyAlias(uid: Int, alias: String): String? =
            proxyAliases[Key(uid, alias)]?.proxyAlias
    }

    data class Key(val uid: Int, val alias: String)
    data class KeyId(val uid: Int, val keyId: Long)
    data class ProxyKeyInfo(
        val localAlias: String,
        val proxyAlias: String,
        val keyId: Long,
        val response: KeyEntryResponse
    )

    init {
        Logger.i(
            "security level interceptor level=$level generateKeyCode=$generateKeyTransaction " +
                "createOperationCode=$createOperationTransaction"
        )
    }

    override fun onPreTransact(
        target: IBinder,
        code: Int,
        flags: Int,
        callingUid: Int,
        callingPid: Int,
        data: Parcel
    ): Result {
        if (code == generateKeyTransaction || Config.matchesAnyTarget(callingUid)) {
            val logKey = "$level:$callingUid:$code"
            if (loggedTransactions.add(logKey)) {
                Logger.i(
                    "securityLevel transact level=$level code=$code flags=$flags uid=$callingUid " +
                        "pid=$callingPid dataSz=${data.dataSize()} ${Config.describeTargets(callingUid)} " +
                        "generateKeyCode=$generateKeyTransaction createOperationCode=$createOperationTransaction"
                )
            }
        }
        if (code == generateKeyTransaction) {
            if (Config.needProxy(callingUid)) {
                return handleProxyGenerateKey(callingUid, callingPid, data)
            }
        }
        if (code == createOperationTransaction) {
            return handleCreateOperation(callingUid, data)
        }
        return Skip
    }

    private fun handleProxyGenerateKey(callingUid: Int, callingPid: Int, data: Parcel): Result {
        Logger.i("intercept proxy key gen uid=$callingUid pid=$callingPid ${Config.describeTargets(callingUid)}")
        kotlin.runCatching {
            data.enforceInterface(IKeystoreSecurityLevel.DESCRIPTOR)
            val keyDescriptor =
                data.readTypedObject(KeyDescriptor.CREATOR) ?: return@runCatching
            val localAlias = keyDescriptor.alias
            if (localAlias.isNullOrBlank()) {
                Logger.i(
                    "proxy generateKey skip: empty alias uid=$callingUid " +
                        describeDescriptor(keyDescriptor)
                )
                return@runCatching
            }
            val attestationKeyDescriptor = data.readTypedObject(KeyDescriptor.CREATOR)
            val params = data.createTypedArray(KeyParameter.CREATOR)!!
            val aFlags = data.readInt()
            val entropy = data.createByteArray()

            // Find attestation challenge in params
            var challenge: ByteArray? = null
            for (kp in params) {
                if (kp.tag == Tag.ATTESTATION_CHALLENGE) {
                    challenge = kp.value.getBlob()
                    break
                }
            }
            Logger.i(
                "proxy generateKey parsed uid=$callingUid alias=$localAlias " +
                    "params=${params.size} hasChallenge=${challenge != null} " +
                    describeDescriptor(keyDescriptor)
            )
            if (challenge == null) {
                Logger.i("proxy generateKey skip: no attestation challenge uid=$callingUid alias=$localAlias")
                return@runCatching // Not an attestation request
            }
            if (challenge.size > 128) {
                Logger.i(
                    "proxy generateKey skip: oversized attestation challenge " +
                        "uid=$callingUid alias=$localAlias len=${challenge.size}"
                )
                return@runCatching
            }

            // Get target package info
            val packageName = Config.getProxyPackageName(callingUid)
            if (packageName == null) {
                Logger.i(
                    "proxy generateKey skip: no proxy target package for uid=$callingUid " +
                        Config.describeTargets(callingUid)
                )
                return@runCatching
            }
            val pm = Config.getPm()
            if (pm == null) {
                Logger.i("proxy generateKey skip: package manager unavailable uid=$callingUid")
                return@runCatching
            }
            @Suppress("DEPRECATION")
            val pkgInfo = pm.getPackageInfoCompat(
                packageName, android.content.pm.PackageManager.GET_SIGNATURES.toLong(),
                callingUid / 100000
            )

            var signingCertHash: String? = null
            var versionCode = 1L
            if (pkgInfo != null) {
                versionCode = pkgInfo.longVersionCode
                if (pkgInfo.signatures != null && pkgInfo.signatures.isNotEmpty()) {
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                        .digest(pkgInfo.signatures[0].toByteArray())
                    signingCertHash = digest.joinToString("") { "%02x".format(it) }
                }
            }

            // Convert KeyParameter[] to proxy param entries
            var strippedIds = 0
            val paramEntries = params.mapNotNull { kp ->
                if (kp.tag == Tag.ATTESTATION_CHALLENGE || kp.tag == Tag.ATTESTATION_APPLICATION_ID)
                    return@mapNotNull null
                if (kp.tag in nonProxyableTags) {
                    strippedIds++
                    return@mapNotNull null
                }
                keyParamToEntry(kp)
            }
            if (strippedIds > 0)
                Logger.i("stripped $strippedIds non-proxyable attestation tag(s) for uid=$callingUid (ID/unique-ID cannot be attested by a remote device)")
            val attestLocalAlias = attestationKeyDescriptor?.alias
            val attestKeyAlias = attestLocalAlias?.let { localAlias ->
                getProxyAlias(callingUid, localAlias).also { proxyAlias ->
                    if (proxyAlias == null) {
                        Logger.i("no proxy alias mapped for attestation key uid=$callingUid alias=$localAlias")
                    } else {
                        Logger.i("use proxy attestation key alias=$proxyAlias for uid=$callingUid alias=$localAlias")
                    }
                }
            }

            fun doGenerate(ak: String?) = ProxyClient.generate(
                targetPackage = packageName,
                signingCertHash = signingCertHash,
                signingCert = null,
                versionCode = versionCode,
                challenge = challenge,
                params = paramEntries,
                flags = aFlags,
                entropy = entropy,
                attestKeyAlias = ak,
                securityLevel = level
            )

            // 自愈：代理机换机 / 会话过期后，内存映射里的 attestKeyAlias 在新机 keystore 已不存在，
            // 远端出证回 KEY_NOT_FOUND(errorCode=7)。此时把这条悬空内存映射作废，并改为
            // 不带 attest key 重试一次——本次请求直接由代理机自身的 TEE 根出证，不再硬失败 500。
            // 后续对该别名的请求也不会再复用死映射（getProxyAlias 已返回 null）。
            val result = try {
                doGenerate(attestKeyAlias)
            } catch (e: Exception) {
                if (attestKeyAlias != null && isKeyNotFound(e)) {
                    Logger.e("proxy attest key alias=$attestKeyAlias gone on remote (stale after agent/session change) uid=$callingUid; evict cache + retry without attest key", e)
                    removeProxyKey(callingUid, attestLocalAlias)
                    doGenerate(null)
                } else throw e
            }

            Logger.i("proxy generated key alias=${result.alias} for uid=$callingUid pkg=$packageName")

            // Debug: verify cert chain signatures locally to detect byte corruption
            run {
                val factory = java.security.cert.CertificateFactory.getInstance("X.509")
                val leaf = factory.generateCertificate(java.io.ByteArrayInputStream(result.leafCert)) as java.security.cert.X509Certificate
                val chainCerts = factory.generateCertificates(java.io.ByteArrayInputStream(result.certChain))
                    .map { it as java.security.cert.X509Certificate }
                val allCerts = listOf(leaf) + chainCerts
                Logger.i("proxy chain (${allCerts.size} certs):")
                for (i in allCerts.indices) {
                    val c = allCerts[i]
                    val issuerCert = if (i + 1 < allCerts.size) allCerts[i + 1] else c // self-signed root
                    val ok = try {
                        c.verify(issuerCert.publicKey)
                        "OK"
                    } catch (e: Exception) {
                        "FAIL: ${e.message}"
                    }
                    Logger.i("  [$i] ${c.subjectX500Principal} signed_by=${issuerCert.subjectX500Principal} -> $ok")
                    // Also check if encoded bytes match original raw bytes
                    if (i > 0) {
                        val reEncoded = allCerts[i].encoded
                        Logger.i("  [$i] encoded=${reEncoded.size} bytes, matches_original=${reEncoded.contentEquals(chainCerts[i-1].encoded)}")
                    } else {
                        Logger.i("  [0] leafCert raw=${result.leafCert.size} encoded=${leaf.encoded.size} match=${result.leafCert.contentEquals(leaf.encoded)}")
                    }
                }
            }
            // Pass through exactly what the relay (real keystore2) returned. For an
            // app-supplied attest key, real keystore2 returns ONLY the leaf cert (signed
            // by the attest key) with an empty chain — the caller already holds the attest
            // key's own chain and assembles the full path itself. Do NOT pre-append the
            // attest key's chain here: the app then concatenates it a second time, which
            // produces duplicate certs and makes CertPath validation fail ("证书链不受信任").
            val effectiveChain = result.certChain
            val metadata = KeyMetadata()
            metadata.keySecurityLevel = level
            metadata.certificate = result.leafCert
            metadata.certificateChain = effectiveChain
            val proxyKeyId = nextProxyKeyId.getAndIncrement()
            val d = KeyDescriptor()
            d.domain = DOMAIN_KEY_ID
            d.nspace = proxyKeyId
            d.alias = null
            d.blob = null
            metadata.key = d
            metadata.authorizations = buildAuthorizationsFromParams(params)

            val response = KeyEntryResponse()
            response.metadata = metadata
            response.iSecurityLevel = original

            val proxyInfo = ProxyKeyInfo(localAlias, result.alias, proxyKeyId, response)
            proxyAliases[Key(callingUid, localAlias)] = proxyInfo
            proxyKeyIds[KeyId(callingUid, proxyKeyId)] = proxyInfo
            Logger.i(
                "proxy key mapped uid=$callingUid alias=$localAlias " +
                    "keyId=$proxyKeyId proxyAlias=${result.alias}"
            )

            val p = Parcel.obtain()
            p.writeNoException()
            p.writeTypedObject(metadata, 0)
            return OverrideReply(0, p)
        }.onFailure {
            Logger.e("proxy key gen failed uid=$callingUid", it)
        }
        return Skip
    }

    // 远端出证失败是否为 keystore2 KEY_NOT_FOUND(ResponseCode=7)——典型为缓存的 attestKeyAlias
    // 指向的密钥在代理机已不存在（换机/会话过期）。ProxyClient 把代理机 500 的响应体放进异常
    // message，里面形如 ServiceSpecificException(errorCode=7)。逐层查 cause 链以防被包裹。
    private fun isKeyNotFound(t: Throwable): Boolean {
        val msg = generateSequence(t as Throwable?) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
        return msg.contains("errorCode=7)") || msg.contains("KEY_NOT_FOUND", ignoreCase = true)
    }

    private fun handleCreateOperation(callingUid: Int, data: Parcel): Result {
        kotlin.runCatching {
            data.enforceInterface(IKeystoreSecurityLevel.DESCRIPTOR)
            val keyDescriptor =
                data.readTypedObject(KeyDescriptor.CREATOR) ?: return@runCatching
            val params = data.createTypedArray(KeyParameter.CREATOR)!!
            // val forced = data.readInt() != 0

            val alias = keyDescriptor.alias
            val proxyInfo = if (!alias.isNullOrBlank()) {
                proxyAliases[Key(callingUid, alias)]
            } else if (keyDescriptor.domain == DOMAIN_KEY_ID) {
                proxyKeyIds[KeyId(callingUid, keyDescriptor.nspace)]
            } else {
                null
            }
            if (proxyInfo == null) {
                if (Config.needProxy(callingUid)) {
                    Logger.i(
                        "proxy createOperation skip: no proxy key uid=$callingUid " +
                            describeDescriptor(keyDescriptor)
                    )
                }
                return@runCatching
            }

            Logger.i(
                "intercept createOperation for proxy key uid=$callingUid " +
                    "alias=${proxyInfo.localAlias} keyId=${proxyInfo.keyId} " +
                    describeDescriptor(keyDescriptor)
            )

            val paramEntries = params.mapNotNull { keyParamToEntry(it) }
            // 与 generate 不同：createOperation 操作的是一把已出证的既有代理密钥，无法就地重建
            // （重建会得到不同密钥/证书，作废 app 已拿到的出证）。若远端回 KEY_NOT_FOUND，说明该
            // 代理密钥在代理机已消失（代理机重启 / 清过 keystore / 路由到没有它的机）。把这条悬空
            // 内存映射作废，让本次操作干净失败——app 下次 generateKey 会生成全新代理密钥，
            // 不再复用死别名。
            val opId = try {
                ProxyClient.createOperation(proxyInfo.proxyAlias, paramEntries)
            } catch (e: Exception) {
                if (isKeyNotFound(e)) {
                    Logger.e("proxy operation key alias=${proxyInfo.proxyAlias} gone on remote (stale after agent/session change) uid=$callingUid alias=${proxyInfo.localAlias}; evict cache", e)
                    removeProxyKey(callingUid, proxyInfo.localAlias)
                }
                throw e
            }

            Logger.i("proxy operation created opId=$opId")

            val operationBinder = ProxyOperationBinder(opId)

            val response = CreateOperationResponse()
            response.iOperation = operationBinder
            response.operationChallenge = null
            response.parameters = null
            // upgradedBlob was added after the Android 12 version of this stable
            // parcelable. Leave its default value untouched to avoid a field lookup
            // against older framework implementations.

            val p = Parcel.obtain()
            p.writeNoException()
            p.writeTypedObject(response, 0)
            return OverrideReply(0, p)
        }.onFailure {
            Logger.e("proxy createOperation failed uid=$callingUid", it)
        }
        return Skip
    }

    private fun describeDescriptor(d: KeyDescriptor): String {
        val blob = d.blob
        val blobText = if (blob == null) {
            "null"
        } else {
            "${blob.size}:${sha256Hex(blob).take(12)}"
        }
        return "descriptor(domain=${d.domain}, nspace=${d.nspace}, alias=${d.alias}, blob=$blobText)"
    }

    private fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(data)
            .joinToString("") { "%02x".format(it) }

    @OptIn(ExperimentalStdlibApi::class)
    private fun keyParamToEntry(kp: KeyParameter): ProxyClient.ParamEntry? {
        val v = kp.value ?: return null
        val tag = kp.tag
        return when (v.getTag()) {
            KeyParameterValue.algorithm ->
                ProxyClient.ParamEntry(tag, "algorithm", v.getAlgorithm())
            KeyParameterValue.blockMode ->
                ProxyClient.ParamEntry(tag, "blockMode", v.getBlockMode())
            KeyParameterValue.paddingMode ->
                ProxyClient.ParamEntry(tag, "paddingMode", v.getPaddingMode())
            KeyParameterValue.digest ->
                ProxyClient.ParamEntry(tag, "digest", v.getDigest())
            KeyParameterValue.ecCurve ->
                ProxyClient.ParamEntry(tag, "ecCurve", v.getEcCurve())
            KeyParameterValue.origin ->
                ProxyClient.ParamEntry(tag, "origin", v.getOrigin())
            KeyParameterValue.keyPurpose ->
                ProxyClient.ParamEntry(tag, "keyPurpose", v.getKeyPurpose())
            KeyParameterValue.hardwareAuthenticatorType ->
                ProxyClient.ParamEntry(tag, "hardwareAuthenticatorType", v.getHardwareAuthenticatorType())
            KeyParameterValue.securityLevel ->
                ProxyClient.ParamEntry(tag, "securityLevel", v.getSecurityLevel())
            KeyParameterValue.boolValue ->
                ProxyClient.ParamEntry(tag, "boolValue", v.getBoolValue())
            KeyParameterValue.integer ->
                ProxyClient.ParamEntry(tag, "integer", v.getInteger())
            KeyParameterValue.longInteger ->
                ProxyClient.ParamEntry(tag, "longInteger", v.getLongInteger())
            KeyParameterValue.dateTime ->
                ProxyClient.ParamEntry(tag, "dateTime", v.getDateTime())
            KeyParameterValue.blob ->
                ProxyClient.ParamEntry(tag, "blob", v.getBlob())
            else -> null
        }
    }

    /**
     * The proxy certChain contains the full chain (leaf → intermediates → root).
     * KeyMetadata expects certificate=leaf and certificateChain=intermediates+root only.
     * Always strip the first cert from certChain since leafCert is set separately.
     */
    private fun stripLeafFromChain(leafCert: ByteArray, certChain: ByteArray): ByteArray {
        if (certChain.isEmpty()) return certChain
        try {
            val factory = java.security.cert.CertificateFactory.getInstance("X.509")
            val certs = factory.generateCertificates(java.io.ByteArrayInputStream(certChain)).toList()
            Logger.i("certChain has ${certs.size} certs, raw size=${certChain.size}")
            if (certs.size <= 1) return ByteArray(0)

            // Always drop the first cert (the leaf), keep only intermediates + root
            val remaining = certs.drop(1)
            val out = java.io.ByteArrayOutputStream()
            for (c in remaining) {
                out.write(c.encoded)
            }
            Logger.i("stripped leaf from proxy certChain (${certs.size} -> ${remaining.size} certs)")
            return out.toByteArray()
        } catch (t: Throwable) {
            Logger.e("failed to parse certChain for leaf stripping", t)
        }
        return certChain
    }

    private fun buildAuthorizationsFromParams(params: Array<KeyParameter>): Array<Authorization> {
        val authorizations = ArrayList<Authorization>()
        for (kp in params) {
            if (kp.tag == Tag.ATTESTATION_CHALLENGE || kp.tag == Tag.ATTESTATION_APPLICATION_ID)
                continue
            val a = Authorization()
            a.keyParameter = kp
            a.securityLevel = level
            authorizations.add(a)
        }
        return authorizations.toTypedArray()
    }
}
