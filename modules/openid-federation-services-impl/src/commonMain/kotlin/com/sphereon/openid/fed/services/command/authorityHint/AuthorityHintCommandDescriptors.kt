package com.sphereon.openid.fed.services.command.authorityHint

import com.sphereon.core.api.service.RegistrableServiceCommandDescriptor
import com.sphereon.di.session.SessionScope
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.ContributesTo

@ContributesTo(SessionScope::class)
interface AuthorityHintCommandDescriptors {

    @Provides @IntoSet
    fun createAuthorityHint(cmd: Lazy<CreateAuthorityHintCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(CreateAuthorityHintCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun deleteAuthorityHint(cmd: Lazy<DeleteAuthorityHintCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(DeleteAuthorityHintCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun findAuthorityHintsByAccount(cmd: Lazy<FindAuthorityHintsByAccountCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(FindAuthorityHintsByAccountCommand.COMMAND_ID) { cmd.value }
}
