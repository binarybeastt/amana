package dev.offlinezec.wallet.mesh

import android.content.Context
import co.electriccoin.lightwallet.client.LightWalletClient
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.lightwallet.client.model.RawTransactionUnsafe
import co.electriccoin.lightwallet.client.model.Response
import dev.offlinezec.wallet.core.hexToBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * lightwalletd over the SDK's gRPC client, with no wallet needed: a relay that only carries
 * strangers' payments can still broadcast them and see them mined.
 */
class LwdNode(context: Context, endpoint: LightWalletEndpoint) : ChainNode {
    private val client = LightWalletClient.new(context.applicationContext, endpoint)

    // lightwalletd wants txids in the internal byte order, the reverse of how they are shown.
    private fun id(txid: String) = txid.hexToBytes().reversedArray()

    override suspend fun submit(txid: String, raw: ByteArray): ChainNode.Submit =
        when (val r = client.submitTransaction(raw)) {
            is Response.Success ->
                if (r.result.code == 0) ChainNode.Submit.Sent
                else ChainNode.classify("(${r.result.code}) ${r.result.message}")
            is Response.Failure -> failure(r)
            else -> ChainNode.Submit.Retry("unexpected response $r")
        }

    override suspend fun mined(txid: String): ChainNode.Mined =
        when (val r = client.fetchTransaction(id(txid))) {
            is Response.Success -> when (val t = r.result) {
                is RawTransactionUnsafe.MainChain -> ChainNode.Mined.Height(t.height.value)
                is RawTransactionUnsafe.Mempool -> ChainNode.Mined.Mempool
                else -> ChainNode.Mined.NotFound // orphaned: send again
            }
            is Response.Failure -> {
                val d = (r.description ?: "").lowercase()
                if (r is Response.Failure.Server.NotFound || "not found" in d || "no such" in d || "-5" in d) {
                    ChainNode.Mined.NotFound
                } else {
                    ChainNode.Mined.Retry("${r.code} ${r.description}")
                }
            }
            else -> ChainNode.Mined.Retry("unexpected response $r")
        }

    private fun failure(r: Response.Failure<*>): ChainNode.Submit {
        val msg = "${r.code} ${r.description}"
        return when (r) {
            is Response.Failure.Connection -> ChainNode.Submit.Retry(msg)
            is Response.Failure.Server.Unavailable -> ChainNode.Submit.Retry(msg)
            is Response.Failure.Server.DeadlineExceeded -> ChainNode.Submit.Retry(msg)
            else -> ChainNode.classify(msg)
        }
    }

    fun dispose() {
        CoroutineScope(Dispatchers.IO).launch { runCatching { client.dispose() } }
    }
}
