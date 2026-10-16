package com.sphereon.openid.fed.client.command.trustChain

/** Static diagnostic dimensions. These values intentionally cannot carry entity, endpoint, JWT, or key data. */
internal enum class TrustChainDiagnosticStage {
    RESOLUTION,
    ENTITY_CONFIGURATION,
    SUBORDINATE_FETCH,
    SUBORDINATE_STATEMENT,
    CHAIN_DISCOVERY,
}

internal enum class TrustChainDiagnosticReason {
    UNEXPECTED_FAILURE,
    RETRY_WITH_FRESH_STATEMENTS,
    NO_CANDIDATE,
    TRUST_ANCHORS_EMPTY,
    MAX_DEPTH_REACHED,
    ENTITY_CONFIGURATION_UNAVAILABLE,
    AUTHORITY_HINT_LOOP,
    TRUST_ANCHOR_UNIDENTIFIED,
    TRUST_ANCHOR_KEYS_UNAVAILABLE,
    CHAIN_VALIDATION_REJECTED,
    AUTHORITY_PROCESSING_FAILED,
    AUTHORITY_ENTITY_CONFIGURATION_UNAVAILABLE,
    AUTHORITY_ENTITY_CONFIGURATION_MAPPING_FAILED,
    ENTITY_CONFIGURATION_MAPPING_FAILED,
    AUTHORITY_HINTS_EMPTY,
    INTERMEDIATE_AUTHORITY_HINTS_MISSING,
    INVALID_STATEMENT,
    IDENTITY_MISMATCH,
    JWKS_INVALID,
    SIGNING_KEY_NOT_ADVERTISED,
    SIGNATURE_INVALID,
    FETCH_OR_PARSE_FAILED,
    FETCH_ENDPOINT_MISSING,
    AUTHORITY_JWKS_INVALID,
    SUBJECT_JWKS_MISSING,
    SUBJECT_KEY_NOT_ADVERTISED,
    NO_ACCEPTABLE_STATEMENT,
}

private enum class SafeFailureClass {
    NONE,
    TLS,
    TRANSPORT,
    PARSE,
    CRYPTO,
    OTHER,
}

/** Renders only fixed enum tokens; exception messages and arbitrary class names are never included. */
internal object TrustChainResolutionDiagnostic {
    fun message(
        stage: TrustChainDiagnosticStage,
        reason: TrustChainDiagnosticReason,
        failure: Throwable? = null,
    ): String = "trust_chain_resolution stage=${stage.name} reason=${reason.name} failure=${classify(failure, reason).name}"

    private fun classify(failure: Throwable?, reason: TrustChainDiagnosticReason): SafeFailureClass {
        if (failure == null) {
            return if (reason == TrustChainDiagnosticReason.SIGNATURE_INVALID) SafeFailureClass.CRYPTO else SafeFailureClass.NONE
        }
        val type = failure::class.simpleName ?: return SafeFailureClass.OTHER
        return when (type) {
            "SSLHandshakeException", "SSLPeerUnverifiedException", "CertificateException", "CertPathValidatorException" ->
                SafeFailureClass.TLS
            "ConnectException", "ConnectTimeoutException", "SocketTimeoutException", "HttpRequestTimeoutException",
            "UnknownHostException", "UnresolvedAddressException", "IOException" -> SafeFailureClass.TRANSPORT
            "SerializationException", "JsonDecodingException", "JsonEncodingException" -> SafeFailureClass.PARSE
            "JoseException", "JWTException", "JwtException", "InvalidKeyException", "SignatureException",
            "NoSuchAlgorithmException", "BadSignatureException" -> SafeFailureClass.CRYPTO
            else -> SafeFailureClass.OTHER
        }
    }
}
