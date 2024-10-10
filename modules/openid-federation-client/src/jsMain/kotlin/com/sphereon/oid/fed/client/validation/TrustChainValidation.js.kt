package com.sphereon.oid.fed.client.validation

import com.sphereon.oid.fed.client.ICallbackServiceJS
import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatement
import kotlinx.coroutines.await
import kotlin.also
import kotlin.js.Promise

@ExperimentalJsExport
@JsExport
interface ITrustChainValidationCallbackJS {
    fun readAuthorityHints(
        partyBId: String,
    ): Promise<Array<Array<dynamic>>>

    fun fetchSubordinateStatements(
        entityConfigurationStatementsList: Array<Array<dynamic>>,
    ): Promise<Array<Array<String>>>

    fun validateTrustChains(
        jwts: Array<Array<String>>,
        knownTrustChainIds: Array<String>
    ): Promise<Array<Array<dynamic>>>
}

@ExperimentalJsExport
@JsExport
object TrustChainValidationServiceJS: ICallbackServiceJS<ITrustChainValidationCallbackJS>, ITrustChainValidationCallbackJS {

    private val NAME = "TrustChainValidation"
    private var disable = false

    private lateinit var platformCallback: ITrustChainValidationCallbackJS

    override fun readAuthorityHints(
        partyBId: String,
    ): Promise<Array<Array<dynamic>>> =
        platformCallback
            .readAuthorityHints(
                partyBId = partyBId
            )

    override fun fetchSubordinateStatements(
        entityConfigurationStatementsList: Array<Array<dynamic>>,
    ): Promise<Array<Array<String>>> =
        platformCallback
            .fetchSubordinateStatements(
                entityConfigurationStatementsList = entityConfigurationStatementsList
            )

    override fun validateTrustChains(
        jwts: Array<Array<String>>,
        knownTrustChainIds: Array<String>
    ): Promise<Array<Array<dynamic>>> = platformCallback.validateTrustChains(
        jwts = jwts,
                knownTrustChainIds = knownTrustChainIds
            )

    override fun disable(): TrustChainValidationServiceJS {
        this.disable = true
        return this
    }

    override fun enable(): TrustChainValidationServiceJS {
        this.disable = false
        return this
    }

    override fun isEnabled(): Boolean {
        return !this.disable
    }

    override fun register(platformCallback: ITrustChainValidationCallbackJS): TrustChainValidationServiceJS {
        this.platformCallback = platformCallback
        return this
    }
}

open class TrustChainValidationCallbackJSAdapter(val trustChainValidationServiceJS: TrustChainValidationServiceJS = TrustChainValidationServiceJS):
        TrustChainValidationCallback {

    override suspend fun readAuthorityHints(partyBId: String): List<List<EntityConfigurationStatement>> {
        TrustChainValidationConst.LOG.debug("Calling TRUST CHAIN VALIDATION readAuthorityHints...")

        return try {
            trustChainValidationServiceJS.readAuthorityHints(partyBId = partyBId).await().map { it.toList() }.toList()
        } catch (e: Exception) {
            throw e
        }.also {
            TrustChainValidationConst.LOG.info("Calling TRUST CHAIN VALIDATION readAuthorityHints result: $it")
        }
    }

    override suspend fun fetchSubordinateStatements(entityConfigurationStatementsList: List<List<EntityConfigurationStatement>>): List<List<String>> {
        TrustChainValidationConst.LOG.debug("Calling TRUST CHAIN VALIDATION fetchSubordinateStatements...")

        return try {
            val entities = entityConfigurationStatementsList.map { it.toTypedArray() }.toTypedArray()
            trustChainValidationServiceJS.fetchSubordinateStatements(
            entityConfigurationStatementsList = entities ).await().map { it.toList() }.toList()
        } catch (e: Exception) {
            throw e
        }.also {
            TrustChainValidationConst.LOG.info("Calling TRUST CHAIN VALIDATION fetchSubordinateStatements result: $it")
        }
    }

    override suspend fun validateTrustChains(
        jwts: List<List<String>>,
        knownTrustChainIds: List<String>
    ): List<List<Any>> {
        TrustChainValidationConst.LOG.debug("Calling TRUST CHAIN VALIDATION validateTrustChains...")

        return try {
            trustChainValidationServiceJS.validateTrustChains(
            jwts = jwts.map { it.toTypedArray() }.toTypedArray(),
            knownTrustChainIds = knownTrustChainIds.toTypedArray()
        ).await().map { it.toList() }.toList()
        } catch (e: Exception) {
            throw e
        }.also {
            TrustChainValidationConst.LOG.info("Calling TRUST CHAIN VALIDATION validateTrustChains result: $it")
        }
    }

    override fun disable(): ITrustChainValidationCallback {
        this.trustChainValidationServiceJS.disable()
        return this
    }

    override fun enable(): ITrustChainValidationCallback {
        this.trustChainValidationServiceJS.disable()
        return this
    }

    override fun isEnabled(): Boolean {
        return this.trustChainValidationServiceJS.isEnabled()
    }

    override fun register(platformCallback: ITrustChainValidationCallback): ITrustChainValidationCallback {
        throw Error("Register function should not be used on the adapter. It depends on the Javascript TrustChainValidationService object")
    }
}

object TrustChainValidationCallbackJSObject: TrustChainValidationCallbackJSAdapter(TrustChainValidationServiceJS)

actual fun trustChainValidationService(): TrustChainValidationCallback = TrustChainValidationCallbackJSObject
