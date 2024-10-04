package com.sphereon.oid.fed.client.validation

import com.sphereon.oid.fed.common.jwt.JwtService
import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatement
import io.ktor.client.engine.*
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.promise
import kotlin.js.Promise

@ExperimentalJsExport
@JsExport
class TrustChainValidation(val jwtService: JwtService) {

    private val NAME = "TrustChainValidation"

    fun readAuthorityHints(
        partyBId: String,
        engine: HttpClientEngine,
        trustChains: MutableList<List<EntityConfigurationStatement>> = mutableListOf(),
        trustChain: MutableSet<EntityConfigurationStatement> = mutableSetOf()
    ): Promise<List<List<EntityConfigurationStatement>>> = CoroutineScope(context = CoroutineName(NAME)).promise {
        TrustChainValidationCommon(jwtService)
            .readAuthorityHints(
                partyBId = partyBId,
                engine = engine,
                trustChains = trustChains,
                trustChain = trustChain
            )
    }

    fun fetchSubordinateStatements(
        entityConfigurationStatementsList: List<List<EntityConfigurationStatement>>,
        engine: HttpClientEngine
    ): Promise<List<List<String>>> = CoroutineScope(context = CoroutineName(NAME)).promise {
        TrustChainValidationCommon(jwtService)
            .fetchSubordinateStatements(
                entityConfigurationStatementsList = entityConfigurationStatementsList,
                engine = engine
            )
    }

    fun validateTrustChains(
        jwts: List<List<String>>,
        knownTrustChainIds: List<String>
    ): Promise<List<List<Any>>> =
        Promise.resolve(
            TrustChainValidationCommon(jwtService)
                .validateTrustChains(
                    jwts = jwts,
                    knownTrustChainIds = knownTrustChainIds
                )
        )
}
