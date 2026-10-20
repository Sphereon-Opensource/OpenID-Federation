package com.sphereon.openid.fed.admin

import kotlinx.serialization.json.*

internal fun adminLifetimeKind(key: AdminDocumentKey): AdminLifetimeKind? = when (key.collection) {
    "configuration" -> AdminLifetimeKind.ENTITY_CONFIGURATION
    "subordinate" -> AdminLifetimeKind.SUBORDINATE_STATEMENT
    else -> null
}

internal fun checkAdminLifetimePolicy(key: AdminDocumentKey, context: AdminLifetimePolicyContext?,
    acknowledgement: AdminLifetimeAcknowledgement?, currentEtag: String?, recreate: Boolean = false) {
    if (acknowledgement != null && acknowledgement.documentEtag != currentEtag)
        adminFail("precondition_failed", 412, "Lifetime acknowledgement does not match the document incarnation")
    if (context == null) {
        if (recreate && acknowledgement != null) adminFail("precondition_failed", 412, "Recreating a policy-bound document requires current trusted policy context")
        return
    }
    if (context.kind != adminLifetimeKind(key) || context.revision < 0 || context.policyLifetimeSeconds <= 0 ||
        listOf(context.binding.ownerId, context.binding.federationId, context.binding.incarnationId).any { it.isBlank() })
        adminFail("invalid_request", 400, "Invalid trusted lifetime policy context")
    if (acknowledgement == null) return
    if (acknowledgement.binding.ownerId != context.binding.ownerId ||
        acknowledgement.binding.federationId != context.binding.federationId || acknowledgement.kind != context.kind)
        adminFail("conflict", 409, "Lifetime policy belongs to another owner or federation")
    if (acknowledgement.binding == context.binding && acknowledgement.revision > context.revision)
        adminFail("precondition_failed", 412, "A newer lifetime policy revision has already been acknowledged")
    if (acknowledgement.binding != context.binding)
        adminFail("conflict", 409, "Lifetime policy belongs to another policy incarnation")
}

internal data class AdminLifetimeDecision(val document: JsonObject, val acknowledge: Boolean = false)

/** Resolve once before signing; the exact captured context is checked again in the atomic store write. */
internal fun applyAdminLifetimePolicy(key: AdminDocumentKey, old: AdminStoredDocument?, document: JsonObject,
    context: AdminLifetimePolicyContext?, acknowledgement: AdminLifetimeAcknowledgement?, publishing: Boolean,
    recreate: Boolean = false): AdminLifetimeDecision {
    checkAdminLifetimePolicy(key, context, acknowledgement, old?.etag, recreate)
    if (context == null) return AdminLifetimeDecision(document)
    if (context.explicitLifetimeWrite) return AdminLifetimeDecision(document, true)
    // Synthetic retained PUTs must not overwrite a later native value or reintroduce a native removal.
    val retained = if (old == null || recreate) document else JsonObject(
        (document - "lifetime") + old.document.filterKeys { it == "lifetime" })
    if (!publishing && old != null && !recreate) return AdminLifetimeDecision(retained)
    val previous = acknowledgement.takeUnless { recreate }
    if (previous == null && !recreate && old?.document?.containsKey("lifetime") == true)
        return AdminLifetimeDecision(retained, true) // Adopt existing native state without manufacturing a policy edit.
    if (previous != null && previous.revision == context.revision) return AdminLifetimeDecision(retained)
    return AdminLifetimeDecision(JsonObject(document + ("lifetime" to JsonPrimitive(context.policyLifetimeSeconds))), true)
}

internal fun updatedAdminLifetimeAcknowledgement(mutation: AdminMutation, previous: AdminLifetimeAcknowledgement?,
    etag: String, newIncarnation: String): AdminLifetimeAcknowledgement? {
    if (adminLifetimeKind(mutation.key) == null) return null
    val old = previous.takeUnless { mutation.recreate }
    if (!mutation.acknowledgeLifetimePolicy) return old?.copy(documentEtag = etag)
    val context = mutation.lifetimePolicy ?: adminFail("invalid_request", 400, "Lifetime acknowledgement requires trusted context")
    return AdminLifetimeAcknowledgement(context.binding, context.kind, context.revision, etag, old?.documentIncarnation ?: newIncarnation)
}
