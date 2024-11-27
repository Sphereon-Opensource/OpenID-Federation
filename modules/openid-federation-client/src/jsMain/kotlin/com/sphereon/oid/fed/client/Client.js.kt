import com.sphereon.oid.fed.client.crypto.CryptoServiceAdapter
import com.sphereon.oid.fed.client.crypto.ICryptoService
import com.sphereon.oid.fed.client.crypto.cryptoService
import com.sphereon.oid.fed.client.fetch.FetchServiceAdapter
import com.sphereon.oid.fed.client.fetch.IFetchService
import com.sphereon.oid.fed.client.fetch.fetchService
import com.sphereon.oid.fed.client.trustchain.TrustChain
import com.sphereon.oid.fed.openapi.models.Jwk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.promise
import kotlin.js.Promise

@JsExport
@JsName("ICryptoService")
external interface ICryptoServiceJS {
    fun verify(
        jwt: String,
        key: Jwk
    ): Promise<Boolean>
}

@JsExport
@JsName("IFetchService")
external interface IFetchServiceJS {
    fun fetchStatement(endpoint: String): Promise<String>
}

@JsExport
@JsName("FederationClient")
class FederationClientJS(
    fetchServiceCallback: IFetchServiceJS?,
    cryptoServiceCallback: ICryptoServiceJS?,
) {
    private val fetchService: IFetchService =
        if (fetchServiceCallback != null) FetchServiceAdapter(fetchServiceCallback) else fetchService()
    private val cryptoService: ICryptoService =
        if (cryptoServiceCallback != null) CryptoServiceAdapter(cryptoServiceCallback) else cryptoService()

    private val trustChainService: TrustChain = TrustChain(fetchService, cryptoService)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @JsName("resolveTrustChain")
    fun resolveTrustChainJS(
        entityIdentifier: String,
        trustAnchors: Array<String>,
        maxDepth: Int = 10
    ): Promise<TrustChainResolveResponse> {
        return scope.promise {
            trustChainService.resolve(entityIdentifier, trustAnchors, maxDepth)
        }
    }
}
