package com.sphereon.openid.fed.services.command.receivedTrustMark

import com.sphereon.core.api.service.RegistrableServiceCommandDescriptor
import com.sphereon.di.session.SessionScope
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo

@ContributesTo(SessionScope::class)
interface ReceivedTrustMarkCommandDescriptors {

    @Provides @IntoSet
    fun createReceivedTrustMark(cmd: Lazy<CreateReceivedTrustMarkCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(CreateReceivedTrustMarkCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun deleteReceivedTrustMark(cmd: Lazy<DeleteReceivedTrustMarkCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(DeleteReceivedTrustMarkCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun listReceivedTrustMarks(cmd: Lazy<ListReceivedTrustMarksCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(ListReceivedTrustMarksCommand.COMMAND_ID) { cmd.value }
}
