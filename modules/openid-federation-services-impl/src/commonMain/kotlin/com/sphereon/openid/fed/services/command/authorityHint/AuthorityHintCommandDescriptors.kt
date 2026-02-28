package com.sphereon.openid.fed.services.command.authorityHint

import com.sphereon.core.api.service.RegistrableServiceCommandDescriptor
import com.sphereon.di.session.SessionScope
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo

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
