package com.sphereon.openid.fed.services

import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.core.error.toFederationResult
import com.sphereon.openid.fed.openapi.models.Constraints
import com.sphereon.openid.fed.openapi.models.SubordinateConstraints
import com.sphereon.openid.fed.services.command.subordinateConstraint.DeleteSubordinateConstraintsArgs
import com.sphereon.openid.fed.services.command.subordinateConstraint.DeleteSubordinateConstraintsCommand
import com.sphereon.openid.fed.services.command.subordinateConstraint.GetSubordinateConstraintsArgs
import com.sphereon.openid.fed.services.command.subordinateConstraint.GetSubordinateConstraintsCommand
import com.sphereon.openid.fed.services.command.subordinateConstraint.SetSubordinateConstraintsArgs
import com.sphereon.openid.fed.services.command.subordinateConstraint.SetSubordinateConstraintsCommand
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = SubordinateConstraintService::class)
class SubordinateConstraintServiceImpl(
    private val getSubordinateConstraintsCommand: GetSubordinateConstraintsCommand,
    private val setSubordinateConstraintsCommand: SetSubordinateConstraintsCommand,
    private val deleteSubordinateConstraintsCommand: DeleteSubordinateConstraintsCommand
) : SubordinateConstraintService {

    override suspend fun getConstraints(tenantId: String, subordinateId: String): FederationResult<SubordinateConstraints> =
        getSubordinateConstraintsCommand.execute(GetSubordinateConstraintsArgs(tenantId, subordinateId)).toFederationResult()

    override suspend fun setConstraints(tenantId: String, subordinateId: String, constraints: Constraints): FederationResult<SubordinateConstraints> =
        setSubordinateConstraintsCommand.execute(SetSubordinateConstraintsArgs(tenantId, subordinateId, constraints)).toFederationResult()

    override suspend fun deleteConstraints(tenantId: String, subordinateId: String): FederationResult<SubordinateConstraints> =
        deleteSubordinateConstraintsCommand.execute(DeleteSubordinateConstraintsArgs(tenantId, subordinateId)).toFederationResult()
}
