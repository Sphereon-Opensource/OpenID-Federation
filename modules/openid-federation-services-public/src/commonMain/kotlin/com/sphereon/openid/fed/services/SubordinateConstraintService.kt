package com.sphereon.openid.fed.services

import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.openapi.models.Constraints
import com.sphereon.openid.fed.openapi.models.SubordinateConstraints

/**
 * Service interface for managing constraints on subordinate entities.
 *
 * Constraints define limitations that apply to subordinate entities in the trust chain,
 * including max_path_length, naming_constraints, and allowed_entity_types.
 */
interface SubordinateConstraintService {

    /**
     * Gets the constraints for a subordinate.
     */
    suspend fun getConstraints(tenantId: String, subordinateId: String): FederationResult<SubordinateConstraints>

    /**
     * Sets (creates or updates) the constraints for a subordinate.
     */
    suspend fun setConstraints(tenantId: String, subordinateId: String, constraints: Constraints): FederationResult<SubordinateConstraints>

    /**
     * Deletes the constraints for a subordinate.
     */
    suspend fun deleteConstraints(tenantId: String, subordinateId: String): FederationResult<SubordinateConstraints>
}
