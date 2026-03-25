package com.sphereon.openid.fed.services.command.resolution

import com.sphereon.core.api.service.RegistrableServiceCommandDescriptor
import com.sphereon.di.session.SessionScope
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.ContributesTo

@ContributesTo(SessionScope::class)
interface ResolutionCommandDescriptors {

    @Provides @IntoSet
    fun resolveEntity(cmd: Lazy<ResolveEntityCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(ResolveEntityCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun getSignedResolveResponseJwt(cmd: Lazy<GetSignedResolveResponseJwtCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(GetSignedResolveResponseJwtCommand.COMMAND_ID) { cmd.value }
}
