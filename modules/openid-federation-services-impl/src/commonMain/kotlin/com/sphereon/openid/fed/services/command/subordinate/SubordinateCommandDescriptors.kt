package com.sphereon.openid.fed.services.command.subordinate

import com.sphereon.core.api.service.RegistrableServiceCommandDescriptor
import com.sphereon.di.session.SessionScope
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.ContributesTo

@ContributesTo(SessionScope::class)
interface SubordinateCommandDescriptors {

    @Provides @IntoSet
    fun findSubordinatesByAccount(cmd: Lazy<FindSubordinatesByAccountCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(FindSubordinatesByAccountCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun findSubordinatesByAccountAsArray(cmd: Lazy<FindSubordinatesByAccountAsArrayCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(FindSubordinatesByAccountAsArrayCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun createSubordinate(cmd: Lazy<CreateSubordinateCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(CreateSubordinateCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun deleteSubordinate(cmd: Lazy<DeleteSubordinateCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(DeleteSubordinateCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun getSubordinateStatement(cmd: Lazy<GetSubordinateStatementCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(GetSubordinateStatementCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun publishSubordinateStatement(cmd: Lazy<PublishSubordinateStatementCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(PublishSubordinateStatementCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun fetchSubordinateStatement(cmd: Lazy<FetchSubordinateStatementCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(FetchSubordinateStatementCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun getSubordinateJwks(cmd: Lazy<GetSubordinateJwksCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(GetSubordinateJwksCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun createSubordinateJwk(cmd: Lazy<CreateSubordinateJwkCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(CreateSubordinateJwkCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun deleteSubordinateJwk(cmd: Lazy<DeleteSubordinateJwkCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(DeleteSubordinateJwkCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun findSubordinateMetadata(cmd: Lazy<FindSubordinateMetadataCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(FindSubordinateMetadataCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun createSubordinateMetadata(cmd: Lazy<CreateSubordinateMetadataCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(CreateSubordinateMetadataCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun deleteSubordinateMetadata(cmd: Lazy<DeleteSubordinateMetadataCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(DeleteSubordinateMetadataCommand.COMMAND_ID) { cmd.value }
}
