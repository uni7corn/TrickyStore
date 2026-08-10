package io.github.a13e300.tricky_store.proxy

import android.system.keystore2.IKeystoreOperation
import io.github.a13e300.tricky_store.Logger

class ProxyOperationBinder(private val opId: String) : IKeystoreOperation.Stub() {
    companion object {
        private val runtimeInterfaceVersion by lazy {
            runCatching {
                IKeystoreOperation::class.java.getField("VERSION").getInt(null)
            }.getOrDefault(1)
        }
        private val runtimeInterfaceHash by lazy {
            runCatching {
                IKeystoreOperation::class.java.getField("HASH").get(null) as String
            }.getOrDefault("")
        }
    }

    override fun updateAad(aadInput: ByteArray?) {
        if (aadInput == null) return
        proxyCall("updateAad") {
            ProxyClient.update(opId, aadInput)
            Unit
        }
    }

    override fun update(input: ByteArray?): ByteArray? =
        if (input == null) null else proxyCall("update") {
            ProxyClient.update(opId, input)
        }

    override fun finish(input: ByteArray?, signature: ByteArray?): ByteArray? =
        proxyCall("finish") {
            ProxyClient.finish(opId, input, signature)
        }

    override fun abort() {
        try {
            ProxyClient.abort(opId)
        } catch (t: Throwable) {
            Logger.i("proxy abort ignored for opId=$opId: ${t.message}")
        }
    }

    override fun getInterfaceVersion(): Int = runtimeInterfaceVersion

    override fun getInterfaceHash(): String = runtimeInterfaceHash

    private fun <T> proxyCall(name: String, block: () -> T): T {
        return try {
            block()
        } catch (t: Throwable) {
            Logger.e("proxy $name failed", t)
            throw RuntimeException("proxy $name failed: ${t.message}", t)
        }
    }
}
