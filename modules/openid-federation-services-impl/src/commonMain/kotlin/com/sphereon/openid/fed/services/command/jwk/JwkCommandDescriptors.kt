package com.sphereon.openid.fed.services.command.jwk

import com.sphereon.core.api.service.RegistrableServiceCommandDescriptor
import com.sphereon.di.session.SessionScope
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.ContributesTo

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
    fun updateKeyRevocation(cmd: Lazy<UpdateKeyRevocationCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(UpdateKeyRevocationCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun retireKey(cmd: Lazy<RetireKeyCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(RetireKeyCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun listHistoricalKeys(cmd: Lazy<ListHistoricalKeysCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(ListHistoricalKeysCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun signAccountRequestJwt(cmd: Lazy<SignAccountRequestJwtCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(SignAccountRequestJwtCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun getFederationHistoricalKeysJwt(cmd: Lazy<GetFederationHistoricalKeysJwtCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(GetFederationHistoricalKeysJwtCommand.COMMAND_ID) { cmd.value }
}
