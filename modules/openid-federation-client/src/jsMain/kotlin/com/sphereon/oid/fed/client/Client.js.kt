import com.sphereon.oid.fed.client.crypto.CryptoServiceAdapter
import com.sphereon.oid.fed.client.crypto.ICryptoService
import com.sphereon.oid.fed.client.crypto.ICryptoServiceJS
import com.sphereon.oid.fed.client.crypto.cryptoService
import com.sphereon.oid.fed.client.fetch.FetchServiceAdapter
import com.sphereon.oid.fed.client.fetch.IFetchService
import com.sphereon.oid.fed.client.fetch.IFetchServiceJS
import com.sphereon.oid.fed.client.fetch.fetchService
import com.sphereon.oid.fed.client.trustchain.TrustChain
import kotlinx.coroutines.*
import kotlin.js.Promise

@JsExport
@JsName("FederationClient")
class FederationClientJS(
    fetchServiceCallback: IFetchServiceJS?,
    cryptoServiceCallback: ICryptoServiceJS?,
) {
    private val fetchService: IFetchService = if (fetchServiceCallback != null) FetchServiceAdapter(fetchServiceCallback) else fetchService()
    private val cryptoService: ICryptoService = if (cryptoServiceCallback != null) CryptoServiceAdapter(cryptoServiceCallback) else cryptoService()

    private val trustChainService: TrustChain = TrustChain(fetchService, cryptoService)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @JsName("resolveTrustChain")
    fun resolveTrustChainJS(
        entityIdentifier: String,
        trustAnchors: Array<String>,
        timeout: Int = 10
    ): Promise<Array<String>?> {
        return scope.promise {
            try {
                trustChainService.resolve(entityIdentifier, trustAnchors, timeout)?.toTypedArray()
            } catch (e: Exception) {
                throw RuntimeException("Failed to resolve trust chain: ${e.message}", e)
            }
        }
    }
}
