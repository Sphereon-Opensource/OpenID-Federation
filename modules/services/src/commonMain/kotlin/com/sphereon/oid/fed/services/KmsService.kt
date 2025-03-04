package com.sphereon.oid.fed.services

import com.sphereon.crypto.kms.EcDSACryptoProvider
import com.sphereon.crypto.kms.IKeyManagementSystem
import com.sphereon.crypto.kms.azure.AzureKeyVaultClientConfig
import com.sphereon.crypto.kms.azure.AzureKeyVaultCryptoProvider
import com.sphereon.crypto.kms.azure.CredentialMode
import com.sphereon.crypto.kms.azure.CredentialOpts
import com.sphereon.crypto.kms.azure.ExponentialBackoffRetryOpts
import com.sphereon.crypto.kms.azure.SecretCredentialOpts

object KmsService {
    private val provider: String = System.getenv("KMS_PROVIDER") ?: "memory"

    private val azureConfig by lazy {
        AzureKeyVaultClientConfig(
            applicationId = System.getenv("AZURE_KEYVAULT_APPLICATION_ID"),
            keyvaultUrl = System.getenv("AZURE_KEYVAULT_URL"),
            tenantId = System.getenv("AZURE_KEYVAULT_TENANT_ID"),
            credentialOpts = CredentialOpts(
                credentialMode = CredentialMode.SERVICE_CLIENT_SECRET,
                secretCredentialOpts = SecretCredentialOpts(
                    clientId = System.getenv("AZURE_KEYVAULT_CLIENT_ID"),
                    clientSecret = System.getenv("AZURE_KEYVAULT_CLIENT_SECRET")
                )
            ),
            exponentialBackoffRetryOpts = ExponentialBackoffRetryOpts(
                maxRetries = System.getenv("AZURE_KEYVAULT_MAX_RETRIES")?.toInt() ?: 10,
                baseDelayInMS = System.getenv("AZURE_KEYVAULT_BASE_DELAY")?.toLong() ?: 500L,
                maxDelayInMS = System.getenv("AZURE_KEYVAULT_MAX_DELAY")?.toLong() ?: 15000L
            )
        )
    }

    private val kmsProvider: IKeyManagementSystem = when (provider) {
        "memory" -> EcDSACryptoProvider()
        "azure" -> AzureKeyVaultCryptoProvider(azureConfig)
        else -> EcDSACryptoProvider()
    }

    fun getKmsProvider(): IKeyManagementSystem = kmsProvider
}
