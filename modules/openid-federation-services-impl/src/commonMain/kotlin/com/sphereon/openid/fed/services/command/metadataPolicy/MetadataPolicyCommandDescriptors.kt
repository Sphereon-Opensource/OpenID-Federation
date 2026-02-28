package com.sphereon.openid.fed.services.command.metadataPolicy

import com.sphereon.core.api.service.RegistrableServiceCommandDescriptor
import com.sphereon.di.session.SessionScope
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo

@ContributesTo(SessionScope::class)
interface MetadataPolicyCommandDescriptors {

    @Provides @IntoSet
    fun createMetadataPolicy(cmd: Lazy<CreateMetadataPolicyCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(CreateMetadataPolicyCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun deleteMetadataPolicy(cmd: Lazy<DeleteMetadataPolicyCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(DeleteMetadataPolicyCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun findMetadataPolicyByAccount(cmd: Lazy<FindMetadataPolicyByAccountCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(FindMetadataPolicyByAccountCommand.COMMAND_ID) { cmd.value }
}
