package com.sphereon.openid.fed.services.command.entityConfiguration

import com.sphereon.core.api.service.RegistrableServiceCommandDescriptor
import com.sphereon.di.session.SessionScope
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo

@ContributesTo(SessionScope::class)
interface EntityConfigurationCommandDescriptors {

    @Provides @IntoSet
    fun findEntityConfigurationByAccount(cmd: Lazy<FindEntityConfigurationByAccountCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(FindEntityConfigurationByAccountCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun publishEntityConfiguration(cmd: Lazy<PublishEntityConfigurationCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(PublishEntityConfigurationCommand.COMMAND_ID) { cmd.value }
}
