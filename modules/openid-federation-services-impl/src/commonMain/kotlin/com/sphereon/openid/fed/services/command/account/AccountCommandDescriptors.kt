package com.sphereon.openid.fed.services.command.account

import com.sphereon.core.api.service.RegistrableServiceCommandDescriptor
import com.sphereon.di.session.SessionScope
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo

@ContributesTo(SessionScope::class)
interface AccountCommandDescriptors {

    @Provides @IntoSet
    fun createAccount(cmd: Lazy<CreateAccountCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(CreateAccountCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun getAllAccounts(cmd: Lazy<GetAllAccountsCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(GetAllAccountsCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun getAccountByUsername(cmd: Lazy<GetAccountByUsernameCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(GetAccountByUsernameCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun getAccountIdentifier(cmd: Lazy<GetAccountIdentifierCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(GetAccountIdentifierCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun deleteAccount(cmd: Lazy<DeleteAccountCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(DeleteAccountCommand.COMMAND_ID) { cmd.value }
}
