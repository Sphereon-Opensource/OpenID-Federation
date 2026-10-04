package com.sphereon.openid.fed.services.command.entityConfiguration

import com.sphereon.core.api.service.RegistrableServiceCommandDescriptor
import com.sphereon.di.session.SessionScope
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.ContributesTo

@ContributesTo(SessionScope::class)
interface EntityConfigurationCommandDescriptors {

    @Provides @IntoSet
    fun signPreparedEntityConfiguration(
        cmd: Lazy<SignPreparedEntityConfigurationCommand>,
    ): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(SignPreparedEntityConfigurationCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun replaceEntityConfigurationComponents(
        cmd: Lazy<ReplaceEntityConfigurationComponentsCommand>,
    ): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(ReplaceEntityConfigurationComponentsCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun findEntityConfigurationByAccount(cmd: Lazy<FindEntityConfigurationByAccountCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(FindEntityConfigurationByAccountCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun publishEntityConfiguration(cmd: Lazy<PublishEntityConfigurationCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(PublishEntityConfigurationCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun findLatestStoredEntityConfigurationJwt(
        cmd: Lazy<FindLatestStoredEntityConfigurationJwtCommand>,
    ): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(FindLatestStoredEntityConfigurationJwtCommand.COMMAND_ID) { cmd.value }
}
