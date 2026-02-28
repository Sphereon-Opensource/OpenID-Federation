package com.sphereon.openid.fed.services.command.jwk

import com.sphereon.core.api.service.RegistrableServiceCommandDescriptor
import com.sphereon.di.session.SessionScope
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo

@ContributesTo(SessionScope::class)
interface JwkCommandDescriptors {

    @Provides @IntoSet
    fun createKey(cmd: Lazy<CreateKeyCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(CreateKeyCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun getKeys(cmd: Lazy<GetKeysCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(GetKeysCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun getAssertedKeys(cmd: Lazy<GetAssertedKeysCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(GetAssertedKeysCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun revokeKey(cmd: Lazy<RevokeKeyCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(RevokeKeyCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun getFederationHistoricalKeysJwt(cmd: Lazy<GetFederationHistoricalKeysJwtCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(GetFederationHistoricalKeysJwtCommand.COMMAND_ID) { cmd.value }
}
