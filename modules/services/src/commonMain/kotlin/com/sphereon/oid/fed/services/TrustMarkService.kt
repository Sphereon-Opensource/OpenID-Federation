package com.sphereon.oid.fed.services

import com.sphereon.crypto.kms.IKeyManagementSystem
import com.sphereon.oid.fed.common.Constants
import com.sphereon.oid.fed.common.builder.TrustMarkObjectBuilder
import com.sphereon.oid.fed.common.exceptions.EntityAlreadyExistsException
import com.sphereon.oid.fed.common.exceptions.NotFoundException
import com.sphereon.oid.fed.logger.Logger
import com.sphereon.oid.fed.openapi.models.Account
import com.sphereon.oid.fed.openapi.models.CreateTrustMark
import com.sphereon.oid.fed.openapi.models.CreateTrustMarkType
import com.sphereon.oid.fed.openapi.models.JwtHeader
import com.sphereon.oid.fed.openapi.models.TrustMark
import com.sphereon.oid.fed.openapi.models.TrustMarkListRequest
import com.sphereon.oid.fed.openapi.models.TrustMarkRequest
import com.sphereon.oid.fed.openapi.models.TrustMarkStatusRequest
import com.sphereon.oid.fed.openapi.models.TrustMarkType
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.persistence.models.TrustMarkIssuer
import com.sphereon.oid.fed.services.mappers.trustMark.toDTO
import com.sphereon.oid.fed.persistence.models.TrustMark as TrustMarkEntity

/**
 * Service class responsible for managing Trust Marks, their types, and issuers.
 * Provides operations such as creation, retrieval, deletion, and management
 * of Trust Marks and associated types.
 */
class TrustMarkService(
    private val jwkService: JwkService,
    private val keyManagementSystem: IKeyManagementSystem,
    private val accountService: AccountService
) {
    /**
     * Logger instance used for logging messages related to the `TrustMarkService` class.
     * Tagged with the name "TrustMarkService" to easily identify logs associated with this service.
     */
    private val logger = Logger.tag("TrustMarkService")
    /**
     * Provides access to the persistence layer for trust mark-related operations.
     * This property is used to interact with the underlying data storage for fetching
     * and manipulating trust mark records.
     */
    private val trustMarkQueries = Persistence.trustMarkQueries
    /**
     * Provides access to the trust mark type queries within the persistence layer.
     * Used to interact with the database for operations related to trust mark types.
     */
    private val trustMarkTypeQueries = Persistence.trustMarkTypeQueries
    /**
     * Represents the queries related to trust mark issuers.
     * This variable is used for performing database operations
     * concerning trust mark issuer data within the persistence layer.
     */
    private val trustMarkIssuerQueries = Persistence.trustMarkIssuerQueries

    /**
     * Creates a new Trust Mark Type for a given account.
     *
     * @param account The account for which the Trust Mark Type is created. This includes details such as the account ID and username.
     * @param createDto The DTO containing the details required to create a new Trust Mark Type, including its identifier.
     * @return The newly created Trust Mark Type as a DTO, including information such as its ID, identifier, and timestamps for creation and last update.
     */
    fun createTrustMarkType(
        account: Account,
        createDto: CreateTrustMarkType
    ): TrustMarkType {
        logger.info("Creating trust mark type ${createDto.identifier} for username: ${account.username}")
        validateTrustMarkTypeIdentifierDoesNotExist(account, createDto.identifier)
        val createdType = trustMarkTypeQueries.create(
            account_id = account.id,
            identifier = createDto.identifier
        ).executeAsOne()
        logger.info("Successfully created trust mark type with ID: ${createdType.id}")

        return createdType.toDTO()
    }

    /**
     * Validates that a trust mark type with the given identifier does not already exist for the specified account.
     * If an identifier is provided and a trust mark type with the same identifier exists for the account, an exception is thrown.
     *
     * @param account The account under which the identifier's uniqueness is being validated.
     * @param identifier The identifier to be checked for uniqueness. Can be null, in which case no validation is performed.
     * @throws EntityAlreadyExistsException If the identifier already exists for a trust mark type in the given account.
     */
    private fun validateTrustMarkTypeIdentifierDoesNotExist(account: Account, identifier: String?) {
        if (identifier != null) {
            logger.debug("Validating identifier uniqueness for account ID: ${account.id}, identifier: $identifier")
            val trustMarkAlreadyExists = trustMarkTypeQueries.findByAccountIdAndIdentifier(account.id, identifier)
                .executeAsOneOrNull()

            if (trustMarkAlreadyExists != null) {
                logger.error("Trust mark type already exists with identifier: $identifier")
                throw EntityAlreadyExistsException("A trust mark type with the given identifier already exists for this account.")
            }
        }
    }

    /**
     * Finds all TrustMarkType records associated with the given account.
     *
     * @param account The account for which to find TrustMarkType records.
     * @return A list of TrustMarkType objects associated with the given account.
     */
    fun findAllByAccount(account: Account): List<TrustMarkType> {
        logger.debug("Finding all trust mark types for account ID: ${account.id}")
        val types = trustMarkTypeQueries.findByAccountId(account.id).executeAsList()
            .map { it.toDTO() }
        logger.debug("Found ${types.size} trust mark types")
        return types
    }

    /**
     * Finds a TrustMarkType by its ID within a specified account.
     *
     * @param account The account within which the TrustMarkType is sought.
     * @param id The unique identifier of the TrustMarkType to be found.
     * @return The TrustMarkType associated with the specified ID within the given account.
     * @throws NotFoundException If no TrustMarkType with the specified ID exists for the account.
     */
    fun findById(account: Account, id: Int): TrustMarkType {
        logger.debug("Finding trust mark type ID: $id for account ID: ${account.id}")
        val definition = assertTrustMarkTypePresent(account, id)
        return definition.toDTO()
    }

    /**
     * Deletes a trust mark type associated with the given account and ID.
     *
     * @param account The account associated with the trust mark type to be deleted.
     * @param id The unique identifier of the trust mark type to delete.
     * @return The deleted trust mark type as a TrustMarkType object.
     */
    fun deleteTrustMarkType(account: Account, id: Int): TrustMarkType {
        logger.info("Deleting trust mark type ID: $id for account ID: ${account.id}")
        assertTrustMarkTypePresent(account, id)
        val deletedType = trustMarkTypeQueries.delete(id).executeAsOne()
        logger.info("Successfully deleted trust mark type ID: $id")
        return deletedType.toDTO()
    }

    /**
     * Retrieves a list of issuer identifiers associated with a specified trust mark type for a given account.
     *
     * @param account The account for which the trust mark type issuers are being retrieved.
     * @param trustMarkTypeId The ID of the trust mark type for which issuers are to be retrieved.
     * @return A list containing the identifiers of issuers associated with the specified trust mark type.
     * @throws NotFoundException If the specified trust mark type is not found for the provided account.
     */
    fun getIssuersForTrustMarkType(account: Account, trustMarkTypeId: Int): List<String> {
        logger.debug("Getting issuers for trust mark type ID: $trustMarkTypeId, account ID: ${account.id}")
        assertTrustMarkTypePresent(account, trustMarkTypeId)
        val issuers = trustMarkIssuerQueries.findByTrustMarkTypeId(trustMarkTypeId)
            .executeAsList()
            .map { it.issuer_identifier }
        logger.debug("Found ${issuers.size} issuers")
        return issuers
    }

    /**
     * Associates a new issuer identifier with a specific trust mark type for the given account.
     * If the issuer is already associated with the trust mark type, an exception is thrown.
     *
     * @param account The account to which the trust mark type belongs.
     * @param trustMarkTypeId The unique identifier of the trust mark type to which the issuer will be added.
     * @param issuerIdentifier The unique identifier of the issuer to be added to the trust mark type.
     * @return The created TrustMarkIssuer after successfully associating the issuer with the trust mark type.
     * @throws EntityAlreadyExistsException if the issuer is already associated with the specified trust mark type.
     */
    fun addIssuerToTrustMarkType(account: Account, trustMarkTypeId: Int, issuerIdentifier: String): TrustMarkIssuer {
        logger.info("Adding issuer $issuerIdentifier to trust mark type ID: $trustMarkTypeId")
        assertTrustMarkTypePresent(account, trustMarkTypeId)
        val existingIssuer = trustMarkIssuerQueries.findByTrustMarkTypeId(trustMarkTypeId)
            .executeAsList()
            .any { it.issuer_identifier == issuerIdentifier }

        if (existingIssuer) {
            logger.error("Issuer $issuerIdentifier already exists for trust mark type ID: $trustMarkTypeId")
            throw EntityAlreadyExistsException("Issuer $issuerIdentifier is already associated with the trust mark definition.")
        }

        val created = trustMarkIssuerQueries.create(
            trust_mark_type_id = trustMarkTypeId,
            issuer_identifier = issuerIdentifier
        ).executeAsOne()
        logger.info("Successfully added issuer $issuerIdentifier")
        return created
    }

    /**
     * Removes an issuer from a specified trust mark type.
     *
     * @param account The account that owns the trust mark type.
     * @param trustMarkTypeId The identifier of the trust mark type.
     * @param issuerIdentifier The unique identifier of the issuer to be removed.
     * @return The removed TrustMarkIssuer instance.
     * @throws NotFoundException If the issuer is not associated with the trust mark type or if the trust mark type does not exist.
     */
    fun removeIssuerFromTrustMarkType(
        account: Account,
        trustMarkTypeId: Int,
        issuerIdentifier: String
    ): TrustMarkIssuer {
        logger.info("Removing issuer $issuerIdentifier from trust mark type ID: $trustMarkTypeId")
        assertTrustMarkTypePresent(account, trustMarkTypeId)
        trustMarkIssuerQueries.findByTrustMarkTypeId(trustMarkTypeId)
            .executeAsList()
            .find { it.issuer_identifier == issuerIdentifier }
            ?: run {
                logger.error("Issuer $issuerIdentifier not found for trust mark type ID: $trustMarkTypeId")
                throw NotFoundException("Issuer $issuerIdentifier is not associated with the trust mark definition.")
            }

        val removed = trustMarkIssuerQueries.delete(
            trust_mark_type_id = trustMarkTypeId,
            issuer_identifier = issuerIdentifier
        ).executeAsOne()
        logger.info("Successfully removed issuer $issuerIdentifier")
        return removed
    }

    /**
     * Retrieves a list of Trust Marks associated with the given account.
     *
     * @param account The account for which to retrieve the Trust Marks.
     * @return A list of TrustMark objects associated with the specified account.
     */
    fun getTrustMarksForAccount(account: Account): List<TrustMark> {
        logger.debug("Getting trust marks for account ID: $account.id")
        val trustMarks = trustMarkQueries.findByAccountId(account.id).executeAsList().map { it.toDTO() }
        logger.debug("Found ${trustMarks.size} trust marks")
        return trustMarks
    }

    /**
     * Asserts that a trust mark type with the given ID exists for the specified account.
     * If the trust mark type cannot be found, logs an error and throws a NotFoundException.
     *
     * @param account The account to which the trust mark type is expected to belong.
     * @param trustMarkTypeId The unique identifier for the trust mark type to be verified.
     * @throws NotFoundException If no trust mark type with the specified ID exists for the account.
     */
    private fun assertTrustMarkTypePresent(account: Account, trustMarkTypeId: Int) =
        trustMarkTypeQueries.findByAccountIdAndId(account.id, trustMarkTypeId).executeAsOneOrNull()
            ?: run {
                logger.error("Trust mark type not found with ID: $trustMarkTypeId")
                throw NotFoundException("Trust mark definition with ID $trustMarkTypeId not found for account ${account.id}.")
            }

    /**
     * Creates a Trust Mark for a given account, based on the provided details.
     *
     * @param account The account for which the Trust Mark is being created.
     * @param body The details required to create the Trust Mark, including the subject, type identifier, and optional claims.
     * @param currentTimeMillis The current time in milliseconds, used for setting issuance time (defaults to `System.currentTimeMillis()` if not provided).
     * @return The created Trust Mark as a `TrustMark` object.
     * @throws IllegalArgumentException If no keys are found for the given account.
     */
    suspend fun createTrustMark(
        account: Account,
        body: CreateTrustMark,
        currentTimeMillis: Long = System.currentTimeMillis()
    ): TrustMark {
        logger.info("Creating trust mark for account ID: $account.id, subject: ${body.sub}")

        val keys = jwkService.getKeys(account)
        if (keys.isEmpty()) {
            logger.error("No keys found for account ID: $account.id")
            throw IllegalArgumentException(Constants.NO_KEYS_FOUND)
        }

        val kid = keys[0].kid
        logger.debug("Using key with KID: $kid")

        val iat = (currentTimeMillis / 1000).toInt()

        val trustMark = TrustMarkObjectBuilder()
            .iss(accountService.getAccountIdentifierByAccount(account))
            .sub(body.sub)
            .id(body.trustMarkTypeIdentifier)
            .iat(iat)
            .logoUri(body.logoUri)
            .ref(body.ref)
            .delegation(body.delegation)

        if (body.exp != null) {
            trustMark.exp(body.exp)
            logger.debug("Setting expiration to: ${body.exp}")
        }

        val jwtService = JwtService(keyManagementSystem)
        val jwt = jwtService.signSerializable(
            trustMark.build(),
            JwtHeader(typ = "trust-mark+jwt", kid = kid!!),
            kid
        )
        logger.debug("Successfully signed trust mark")

        val trustMarkEntity: TrustMarkEntity = trustMarkQueries.create(
            account_id = account.id,
            sub = body.sub,
            trust_mark_type_identifier = body.trustMarkTypeIdentifier,
            exp = body.exp,
            iat = iat,
            trust_mark_value = jwt
        ).executeAsOne()
        logger.info("Successfully created trust mark with ID: ${trustMarkEntity.id}")
        return trustMarkEntity.toDTO()
    }

    /**
     * Deletes a Trust Mark associated with the specified account and ID.
     *
     * @param account The account to which the Trust Mark belongs.
     * @param id The unique identifier of the Trust Mark to be deleted.
     * @return The deleted Trust Mark.
     *
     * @throws NotFoundException If the Trust Mark with the specified ID is not found for the given account.
     */
    fun deleteTrustMark(account: Account, id: Int): TrustMark {
        logger.info("Deleting trust mark ID: $id for account ID: $account.id")
        trustMarkQueries.findByAccountIdAndId(account.id, id).executeAsOneOrNull()
            ?: throw NotFoundException("Trust mark with ID $id not found for account $account.id.").also {
                logger.error("Trust mark not found with ID: $id")
            }

        val deleted = trustMarkQueries.delete(id).executeAsOne().toDTO()
        logger.info("Successfully deleted trust mark ID: $id")
        return deleted
    }

    /**
     * Checks the status of a Trust Mark for a specific account based on the provided parameters.
     *
     * @param account The account for which the Trust Mark status is being checked.
     * @param request The request containing the subject, Trust Mark Type identifier, and optionally, the issued-at time (iat).
     * @return True if a matching Trust Mark is found based on the criteria provided in the request, false otherwise.
     */
    fun getTrustMarkStatus(account: Account, request: TrustMarkStatusRequest): Boolean {
        logger.debug("Checking trust mark status for account ID: ${account.id}, subject: ${request.sub}")
        val trustMarks = trustMarkQueries.findByAccountIdAndAndSubAndTrustMarkTypeIdentifier(
            account.id,
            request.trustMarkId,
            request.sub
        ).executeAsList()
        logger.debug("Found ${trustMarks.size} matching trust marks")

        if (request.iat != null) {
            logger.debug("Filtering by IAT: ${request.iat}")
            val trustMarkWithIat = trustMarks.find { it.iat == request.iat }
            return trustMarkWithIat != null
        }

        return trustMarks.isNotEmpty()
    }

    /**
     * Retrieves a list of distinct subjects (subs) associated with an account and a specific trust mark type.
     * If a specific subject is provided in the request, the results will be filtered to include only that subject.
     *
     * @param account The account for which trust-marked subjects are to be retrieved.
     * @param request The trust mark list request containing the trust mark type identifier and an optional subject.
     * @return An array of distinct subject strings associated with the given trust mark type and account.
     */
    fun getTrustMarkedSubs(account: Account, request: TrustMarkListRequest): Array<String> {
        logger.debug("Getting trust marked subjects for account ID: ${account.id}, trust mark type: ${request.trustMarkId}")
        val subs = if (request.sub != null) {
            logger.debug("Filtering by subject: ${request.sub}")
            trustMarkQueries.findAllDistinctSubsByAccountIdAndTrustMarkTypeIdentifierAndSub(
                account.id, request.trustMarkId, request.sub!!
            ).executeAsList()
        } else {
            trustMarkQueries.findAllDistinctSubsByAccountIdAndTrustMarkTypeIdentifier(
                account.id, request.trustMarkId
            ).executeAsList()
        }
        logger.debug("Found ${subs.size} subjects")
        return subs.toTypedArray()
    }

    /**
     * Retrieves the trust mark value for the specified account and request details.
     *
     * @param account The account object containing the account information.
     * @param request The request object specifying the trust mark type and subject.
     * @return The value of the trust mark as a string.
     * @throws NotFoundException If the trust mark is not found.
     */
    fun getTrustMark(account: Account, request: TrustMarkRequest): String {
        logger.debug("Getting trust mark for account ID: ${account.id}, trust mark ID: ${request.trustMarkId}, subject: ${request.sub}")
        val trustMark = trustMarkQueries.getLatestByAccountIdAndTrustMarkTypeIdentifierAndSub(
            account.id,
            request.trustMarkId,
            request.sub,
        ).executeAsOneOrNull() ?: throw NotFoundException("Trust mark not found.")

        logger.debug("Found trust mark with ID: ${trustMark.id}")
        return trustMark.trust_mark_value
    }
}
