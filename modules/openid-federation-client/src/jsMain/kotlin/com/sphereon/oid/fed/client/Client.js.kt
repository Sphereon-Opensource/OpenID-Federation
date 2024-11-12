import com.sphereon.oid.fed.client.crypto.ICryptoService
import com.sphereon.oid.fed.client.crypto.cryptoService
import com.sphereon.oid.fed.client.fetch.IFetchService
import com.sphereon.oid.fed.client.fetch.fetchService
import com.sphereon.oid.fed.client.trustchain.TrustChain
import com.sphereon.oid.fed.openapi.models.Jwk
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlin.js.Promise

@JsExport
@JsName("IFetchService")
interface IFetchServiceJS {
    fun fetchStatement(endpoint: String): Promise<String>
}

@JsExport
@JsName("ICryptoService")
interface ICryptoServiceJS {
    fun verify(data: String, key: String): Promise<Boolean>
}

class FetchServiceAdapter(private val jsFetchService: IFetchServiceJS) : IFetchService {
    override suspend fun fetchStatement(endpoint: String): String {
        return jsFetchService.fetchStatement(endpoint).await()
    }
}

class CryptoServiceAdapter(private val jsCryptoService: ICryptoServiceJS) : ICryptoService {
    override suspend fun verify(jwt: String, key: Jwk): Boolean {
        return jsCryptoService.verify(jwt, Json.encodeToString(Jwk.serializer(), key)).await()
    }
}

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
