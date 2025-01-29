import com.sphereon.oid.fed.cache.InMemoryCache
import com.sphereon.oid.fed.client.context.FederationContext
import com.sphereon.oid.fed.client.crypto.CryptoServiceAdapter
import com.sphereon.oid.fed.client.crypto.cryptoService
import com.sphereon.oid.fed.client.services.entityConfigurationStatementService.EntityConfigurationStatementService
import com.sphereon.oid.fed.client.services.trustChainService.TrustChainService
import com.sphereon.oid.fed.client.services.trustMarkService.TrustMarkService
import com.sphereon.oid.fed.client.types.ICryptoService
import com.sphereon.oid.fed.client.types.TrustChainResolveResponse
import com.sphereon.oid.fed.client.types.TrustMarkValidationResponse
import com.sphereon.oid.fed.client.types.VerifyTrustChainResponse
import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatementDTO
import com.sphereon.oid.fed.openapi.models.Jwk
import io.ktor.client.*
import io.ktor.client.engine.js.*
import io.ktor.client.plugins.*
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
    cryptoServiceCallback: ICryptoServiceJS?,
    httpClient: HttpClient? = null
) {
    private val cryptoService: ICryptoService =
        if (cryptoServiceCallback != null) CryptoServiceAdapter(cryptoServiceCallback) else cryptoService()

    private val context = FederationContext.create(
        cryptoService = cryptoService,
        cache = InMemoryCache(),
        httpClient = httpClient ?: HttpClient(Js) {
            install(HttpTimeout)
        })

    private val entityService = EntityConfigurationStatementService(context)
    private val trustChainService = TrustChainService(context)
    private val trustMarkService = TrustMarkService(context)
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

    @JsName("verifyTrustChain")
    fun verifyTrustChainJS(
        trustChain: Array<String>,
        trustAnchor: String?,
        currentTime: Int? = null
    ): Promise<VerifyTrustChainResponse> {
        return scope.promise {
            trustChainService.verify(
                trustChain.toList(),
                trustAnchor,
                currentTime?.toLong()
            )
        }
    }

    @JsName("entityConfigurationStatementGet")
    fun entityConfigurationStatementGet(
        entityIdentifier: String
    ): Promise<EntityConfigurationStatementDTO> {
        return scope.promise {
            entityService.fetchEntityConfigurationStatement(entityIdentifier)
        }
    }

    @JsName("verifyTrustMark")
    fun verifyTrustMarkJS(
        trustMark: String,
        trustAnchorConfig: EntityConfigurationStatementDTO,
        currentTime: Int? = null
    ): Promise<TrustMarkValidationResponse> {
        return scope.promise {
            trustMarkService.validateTrustMark(
                trustMark,
                trustAnchorConfig,
                currentTime?.toLong()
            )
        }
    }
}