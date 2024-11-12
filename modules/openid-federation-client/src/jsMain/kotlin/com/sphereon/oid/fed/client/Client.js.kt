import com.sphereon.oid.fed.client.crypto.ICryptoService
import com.sphereon.oid.fed.client.crypto.cryptoService
import com.sphereon.oid.fed.client.fetch.IFetchService
import com.sphereon.oid.fed.client.fetch.fetchService
import com.sphereon.oid.fed.client.trustchain.TrustChain
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlin.js.Promise

@JsExport
@JsName("init")
class FederationClientJS(
    fetchServiceCallback: IFetchService?,
    cryptoServiceCallback: ICryptoService?,
) {
    private val fetchService: IFetchService =
        fetchServiceCallback ?: fetchService()
    private val cryptoService: ICryptoService = cryptoServiceCallback ?: cryptoService()

    private val trustChainService: TrustChain = TrustChain(fetchService, cryptoService)

    @OptIn(DelicateCoroutinesApi::class)
    @JsName("resolveTrustChain")
    fun resolveTrustChainJS(entityIdentifier: String, trustAnchors: Array<String>): Promise<Array<String>?> {
        return GlobalScope.promise {
            trustChainService.resolve(
                entityIdentifier,
                trustAnchors,
                10
            )?.toTypedArray()
        }
    }
}
