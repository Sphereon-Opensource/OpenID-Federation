package com.sphereon.oid.fed.services

import com.sphereon.crypto.kms.EcDSACryptoProvider
import com.sphereon.crypto.kms.IKeyManagementSystem
import com.sphereon.crypto.kms.azure.AzureKeyVaultClientConfig
import com.sphereon.crypto.kms.azure.AzureKeyVaultCryptoProvider
import com.sphereon.crypto.kms.azure.CredentialMode
import com.sphereon.crypto.kms.azure.CredentialOpts
import com.sphereon.crypto.kms.azure.ExponentialBackoffRetryOpts
import com.sphereon.crypto.kms.azure.SecretCredentialOpts

enum class KmsType {
    MEMORY,
    AZURE;

    companion object {
        fun fromString(value: String?): KmsType {
            return when (value?.lowercase()) {
                "azure" -> AZURE
                "memory" -> MEMORY
                else -> throw SecurityException("Unknown KMS provider")
            }
        }
    }
}

class KmsService private constructor(
    provider: KmsType,
    azureConfig: AzureKeyVaultClientConfig?
) {
    private val kmsProvider: IKeyManagementSystem = when (provider) {
        KmsType.MEMORY -> EcDSACryptoProvider()
        KmsType.AZURE -> {
            requireNotNull(azureConfig) { "Azure configuration is required when using AZURE provider type" }
            AzureKeyVaultCryptoProvider(azureConfig)
        }
    }

    fun getKmsProvider(): IKeyManagementSystem = kmsProvider

    companion object {
        /**
         * Creates a KmsService with in-memory provider
         */
        fun createMemoryKms(): KmsService {
            return KmsService(KmsType.MEMORY, null)
        }

        /**
         * Creates a KmsService with Azure Key Vault provider
         */
        fun createAzureKms(
            applicationId: String,
            keyvaultUrl: String,
            tenantId: String,
            clientId: String,
            clientSecret: String,
            maxRetries: Int = 10,
            baseDelayInMS: Long = 500L,
            maxDelayInMS: Long = 15000L
        ): KmsService {
            val config = AzureKeyVaultClientConfig(
                applicationId = applicationId,
                keyvaultUrl = keyvaultUrl,
                tenantId = tenantId,
                credentialOpts = CredentialOpts(
                    credentialMode = CredentialMode.SERVICE_CLIENT_SECRET,
                    secretCredentialOpts = SecretCredentialOpts(
                        clientId = clientId,
                        clientSecret = clientSecret
                    )
                ),
                exponentialBackoffRetryOpts = ExponentialBackoffRetryOpts(
                    maxRetries = maxRetries,
                    baseDelayInMS = baseDelayInMS,
                    maxDelayInMS = maxDelayInMS
                )
            )

            return KmsService(KmsType.AZURE, config)
        }
    }
}
