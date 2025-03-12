package com.sphereon.oid.fed.services


import com.sphereon.crypto.kms.IKeyManagementSystem
import com.sphereon.crypto.kms.aws.AwsKmsCryptoProvider
import com.sphereon.crypto.kms.azure.AzureKeyVaultClientConfig
import com.sphereon.crypto.kms.azure.AzureKeyVaultCryptoProvider
import com.sphereon.crypto.kms.azure.CredentialMode
import com.sphereon.crypto.kms.azure.CredentialOpts
import com.sphereon.crypto.kms.azure.ExponentialBackoffRetryOpts
import com.sphereon.crypto.kms.azure.SecretCredentialOpts
import com.sphereon.crypto.kms.ecdsa.EcDSACryptoProvider
import com.sphereon.crypto.kms.model.AccessKeyCredentialOpts
import com.sphereon.crypto.kms.model.AwsKmsClientConfig
import com.sphereon.crypto.kms.model.KeyProviderConfig
import com.sphereon.crypto.kms.model.KeyProviderSettings
import com.sphereon.crypto.kms.model.KeyProviderType

enum class KmsType {
    MEMORY,
    AZURE,
    AWS;

    companion object {
        fun fromString(value: String?): KmsType {
            return when (value?.lowercase()) {
                "azure" -> AZURE
                "aws" -> AWS
                "memory" -> MEMORY
                else -> throw SecurityException("Unknown KMS provider")
            }
        }
    }
}

class KmsService private constructor(
    provider: KmsType,
    azureConfig: AzureKeyVaultClientConfig? = null,
    awsConfig: AwsKmsClientConfig? = null

) {
    private val kmsProvider: IKeyManagementSystem = when (provider) {
        KmsType.MEMORY -> EcDSACryptoProvider()
        KmsType.AWS -> {
            requireNotNull(awsConfig) { "AWS configuration is required when using AWS Provider type" }
            AwsKmsCryptoProvider(KeyProviderSettings(id = awsConfig.applicationId, config = KeyProviderConfig(type = KeyProviderType.AWS_KMS, aws = awsConfig)))
        }

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

        fun createAwsKms(
            applicationId: String,
            region: String,
            accessKeyId: String,
            secretAccessKey: String,
            maxRetries: Int = 10,
            baseDelayInMS: Long = 500L,
            maxDelayInMS: Long = 15000L
        ): KmsService {
            val config = AwsKmsClientConfig(
                applicationId = applicationId,
                region = region,
                credentialOpts = com.sphereon.crypto.kms.model.CredentialOpts(
                    credentialMode = com.sphereon.crypto.kms.model.CredentialMode.ACCESS_KEY,
                    accessKeyCredentialOpts = AccessKeyCredentialOpts(accessKeyId = accessKeyId, secretAccessKey = secretAccessKey)
                ),
                exponentialBackoffRetryOpts = com.sphereon.crypto.kms.model.ExponentialBackoffRetryOpts(
                    maxRetries = maxRetries,
                    baseDelayInMS = baseDelayInMS,
                    maxDelayInMS = maxDelayInMS
                )
            )
            return KmsService(KmsType.AWS, awsConfig = config)
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

            return KmsService(KmsType.AZURE, azureConfig = config)
        }
    }
}
