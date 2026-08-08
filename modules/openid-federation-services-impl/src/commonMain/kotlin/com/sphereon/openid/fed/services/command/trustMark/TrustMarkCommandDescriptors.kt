package com.sphereon.openid.fed.services.command.trustMark

import com.sphereon.core.api.service.RegistrableServiceCommandDescriptor
import com.sphereon.di.session.SessionScope
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.ContributesTo

@ContributesTo(SessionScope::class)
interface TrustMarkCommandDescriptors {

    @Provides @IntoSet
    fun getTrustMarksForAccount(cmd: Lazy<GetTrustMarksForAccountCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(GetTrustMarksForAccountCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun createTrustMark(cmd: Lazy<CreateTrustMarkCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(CreateTrustMarkCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun deleteTrustMark(cmd: Lazy<DeleteTrustMarkCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(DeleteTrustMarkCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun getTrustMarkStatus(cmd: Lazy<GetTrustMarkStatusCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(GetTrustMarkStatusCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun getTrustMarkedSubs(cmd: Lazy<GetTrustMarkedSubsCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(GetTrustMarkedSubsCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun getTrustMark(cmd: Lazy<GetTrustMarkCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(GetTrustMarkCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun createTrustMarkType(cmd: Lazy<CreateTrustMarkTypeCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(CreateTrustMarkTypeCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun findAllTrustMarkTypesByAccount(cmd: Lazy<FindAllTrustMarkTypesByAccountCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(FindAllTrustMarkTypesByAccountCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun findTrustMarkTypeById(cmd: Lazy<FindTrustMarkTypeByIdCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(FindTrustMarkTypeByIdCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun deleteTrustMarkType(cmd: Lazy<DeleteTrustMarkTypeCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(DeleteTrustMarkTypeCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun getIssuersForTrustMarkType(cmd: Lazy<GetIssuersForTrustMarkTypeCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(GetIssuersForTrustMarkTypeCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun addIssuerToTrustMarkType(cmd: Lazy<AddIssuerToTrustMarkTypeCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(AddIssuerToTrustMarkTypeCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun removeIssuerFromTrustMarkType(cmd: Lazy<RemoveIssuerFromTrustMarkTypeCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(RemoveIssuerFromTrustMarkTypeCommand.COMMAND_ID) { cmd.value }
}
