package com.sphereon.openid.fed.account.command

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.AccountAlreadyExistsError
import com.sphereon.openid.fed.core.error.InvalidRequestError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.CreateAccount
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.account.mappers.toDTO
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = CreateAccountCommand::class)
class CreateAccountCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<CreateAccount, Account>(
    commandId = CreateAccountCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<CreateAccount>(),
    outputTypeToken = typeToken<Account>()
), CreateAccountCommand {

    private val logger = execution.federationLogger("CreateAccountCommand")
    private val accountQueries = Persistence.accountQueries

    override suspend fun doExecute(
        args: CreateAccount,
        applyDuring: (CreateAccount) -> CreateAccount
    ): IdkResult<Account, IdkError> {
        val createRequest = applyDuring(args)
        logger.info("Starting account creation process for username: ${createRequest.username}")

        val existingAccount = accountQueries.findByUsername(createRequest.username).executeAsOneOrNull()
        if (existingAccount != null) {
            logger.error("Account creation failed: Account with username ${createRequest.username} already exists")
            return federationErr(AccountAlreadyExistsError(createRequest.username))
        }

        createRequest.identifier?.let { identifier ->
            if (!identifier.startsWith("https://")) {
                logger.error("Account creation failed: Identifier must start with https:// - Provided: $identifier")
                return federationErr(InvalidRequestError("Identifier must start with https://"))
            }
        }

        return try {
            val createdAccount = accountQueries.create(
                username = createRequest.username,
                identifier = createRequest.identifier
            ).executeAsOne()
            logger.info("Successfully created account - Username: ${createRequest.username}, ID: ${createdAccount.id}")
            IdkResult.ok(createdAccount.toDTO())
        } catch (e: Exception) {
            logger.error("Failed to create account: ${e.message}", e)
            federationErr(ServerError("Failed to create account", e.message, e))
        }
    }
}
