package com.sphereon.openid.fed.services.command.metadata

import com.sphereon.core.api.service.RegistrableServiceCommandDescriptor
import com.sphereon.di.session.SessionScope
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo

@ContributesTo(SessionScope::class)
interface MetadataCommandDescriptors {

    @Provides @IntoSet
    fun createMetadata(cmd: Lazy<CreateMetadataCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(CreateMetadataCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun deleteMetadata(cmd: Lazy<DeleteMetadataCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(DeleteMetadataCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun findMetadataByAccount(cmd: Lazy<FindMetadataByAccountCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(FindMetadataByAccountCommand.COMMAND_ID) { cmd.value }
}
