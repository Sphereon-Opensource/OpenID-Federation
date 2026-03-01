package com.sphereon.openid.fed.services.command.subordinateConstraint

import com.sphereon.core.api.service.RegistrableServiceCommandDescriptor
import com.sphereon.di.session.SessionScope
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo

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
