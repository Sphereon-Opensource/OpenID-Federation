package com.sphereon.openid.fed.services.command.registration

import com.sphereon.core.api.service.RegistrableServiceCommandDescriptor
import com.sphereon.di.session.SessionScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides

@ContributesTo(SessionScope::class)
interface RegistrationCommandDescriptors {

    @Provides @IntoSet
    fun resolveRegistrationClient(cmd: Lazy<ResolveRegistrationClientCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(ResolveRegistrationClientCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun verifyAutomaticRegistration(cmd: Lazy<VerifyAutomaticRegistrationCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(VerifyAutomaticRegistrationCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun createExplicitRegistrationRequest(cmd: Lazy<CreateExplicitRegistrationRequestCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(CreateExplicitRegistrationRequestCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun verifyExplicitRegistrationRequest(cmd: Lazy<VerifyExplicitRegistrationRequestCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(VerifyExplicitRegistrationRequestCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun signExplicitRegistrationResponse(cmd: Lazy<SignExplicitRegistrationResponseCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(SignExplicitRegistrationResponseCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun verifyExplicitRegistrationResponse(cmd: Lazy<VerifyExplicitRegistrationResponseCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(VerifyExplicitRegistrationResponseCommand.COMMAND_ID) { cmd.value }
}
