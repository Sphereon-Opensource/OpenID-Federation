import com.sphereon.oid.fed.client.crypto.ICryptoService
import com.sphereon.oid.fed.client.crypto.cryptoService
import com.sphereon.oid.fed.client.fetch.IFetchService
import com.sphereon.oid.fed.client.fetch.fetchService
import com.sphereon.oid.fed.client.trustchain.TrustChain
import kotlin.js.JsExport
import kotlin.js.JsName

/**
 * Response object for the resolve operation.
 */
@JsExport
@JsName("TrustChainResolveResponse")
data class TrustChainResolveResponse(
    /**
     * A list of strings representing the resolved trust chain.
     * Each string contains a JWT.
     */
    val trustChain: List<String>? = null,

    /**
     * Indicates whether the resolve operation was successful.
     */
    val error: Boolean = false,

    /**
     * Error message in case of a failure, if any.
     */
    val errorMessage: String? = null
)

/**
 * Interface for the FederationClient.
 */
@JsExport.Ignore
interface IFederationClient {
    val fetchServiceCallback: IFetchService?
    val cryptoServiceCallback: ICryptoService?
}

@JsExport.Ignore
class FederationClient(
    override val fetchServiceCallback: IFetchService? = null,
    override val cryptoServiceCallback: ICryptoService? = null
) : IFederationClient {
    private val fetchService: IFetchService =
        fetchServiceCallback ?: fetchService()
    private val cryptoService: ICryptoService = cryptoServiceCallback ?: cryptoService()

    private val trustChainService: TrustChain = TrustChain(fetchService, cryptoService)

    suspend fun resolveTrustChain(
        entityIdentifier: String,
        trustAnchors: Array<String>,
        maxDepth: Int = 5
    ): TrustChainResolveResponse {
        return trustChainService.resolve(entityIdentifier, trustAnchors, maxDepth)
    }
}
