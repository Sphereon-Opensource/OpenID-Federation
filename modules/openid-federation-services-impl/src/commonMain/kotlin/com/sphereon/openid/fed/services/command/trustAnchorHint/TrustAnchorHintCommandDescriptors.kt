package com.sphereon.openid.fed.services.command.trustAnchorHint

import com.sphereon.core.api.service.RegistrableServiceCommandDescriptor
import com.sphereon.di.session.SessionScope
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.ContributesTo

@ContributesTo(SessionScope::class)
interface TrustAnchorHintCommandDescriptors {

    @Provides @IntoSet
    fun createTrustAnchorHint(cmd: Lazy<CreateTrustAnchorHintCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(CreateTrustAnchorHintCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun deleteTrustAnchorHint(cmd: Lazy<DeleteTrustAnchorHintCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(DeleteTrustAnchorHintCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun findTrustAnchorHintsByAccount(cmd: Lazy<FindTrustAnchorHintsByAccountCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(FindTrustAnchorHintsByAccountCommand.COMMAND_ID) { cmd.value }
}
