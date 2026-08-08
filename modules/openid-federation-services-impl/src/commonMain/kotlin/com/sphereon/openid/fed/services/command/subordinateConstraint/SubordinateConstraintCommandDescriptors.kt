package com.sphereon.openid.fed.services.command.subordinateConstraint

import com.sphereon.core.api.service.RegistrableServiceCommandDescriptor
import com.sphereon.di.session.SessionScope
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.ContributesTo

@ContributesTo(SessionScope::class)
interface SubordinateConstraintCommandDescriptors {

    @Provides @IntoSet
    fun getSubordinateConstraints(cmd: Lazy<GetSubordinateConstraintsCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(GetSubordinateConstraintsCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun setSubordinateConstraints(cmd: Lazy<SetSubordinateConstraintsCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(SetSubordinateConstraintsCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun deleteSubordinateConstraints(cmd: Lazy<DeleteSubordinateConstraintsCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(DeleteSubordinateConstraintsCommand.COMMAND_ID) { cmd.value }
}
