package com.sphereon.openid.fed.services.command.renewal

import com.sphereon.core.api.service.RegistrableServiceCommandDescriptor
import com.sphereon.di.session.SessionScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides

@ContributesTo(SessionScope::class)
interface StatementRenewalCommandDescriptors {
    @Provides @IntoSet
    fun renewDueStatements(cmd: Lazy<RenewDueStatementsCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(RenewDueStatementsCommand.COMMAND_ID) { cmd.value }
}

