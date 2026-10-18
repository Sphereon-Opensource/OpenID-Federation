package com.sphereon.openid.fed.admin

import com.sphereon.core.api.service.RegistrableServiceCommandDescriptor
import com.sphereon.di.session.SessionScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides

/** Makes every standard operation available through the command invoker independently of HTTP. */
@ContributesTo(SessionScope::class)
interface AdminCommandDescriptors {
    @Provides @IntoSet
    fun readAdminCacheStats(command: Lazy<ReadAdminCacheStatsCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(ReadAdminCacheStatsCommand.COMMAND_ID) { command.value }
    @Provides @IntoSet
    fun clearAdminCache(command: Lazy<ClearAdminCacheCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(ClearAdminCacheCommand.COMMAND_ID) { command.value }

    @Provides @IntoSet
    fun readAdminNode(command: Lazy<ReadAdminNodeCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(ReadAdminNodeCommand.COMMAND_ID) { command.value }

    @Provides @IntoSet
    fun listAdminKeys(command: Lazy<ListAdminKeysCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(ListAdminKeysCommand.COMMAND_ID) { command.value }

    @Provides @IntoSet
    fun createAdminKey(command: Lazy<CreateAdminKeyCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(CreateAdminKeyCommand.COMMAND_ID) { command.value }

    @Provides @IntoSet
    fun readAdminKey(command: Lazy<ReadAdminKeyCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(ReadAdminKeyCommand.COMMAND_ID) { command.value }

    @Provides @IntoSet
    fun deleteAdminKey(command: Lazy<DeleteAdminKeyCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(DeleteAdminKeyCommand.COMMAND_ID) { command.value }

    @Provides @IntoSet
    fun rotateAdminKey(command: Lazy<RotateAdminKeyCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(RotateAdminKeyCommand.COMMAND_ID) { command.value }

    @Provides @IntoSet
    fun readAdminConfiguration(command: Lazy<ReadAdminConfigurationCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(ReadAdminConfigurationCommand.COMMAND_ID) { command.value }

    @Provides @IntoSet
    fun replaceAdminConfiguration(command: Lazy<ReplaceAdminConfigurationCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(ReplaceAdminConfigurationCommand.COMMAND_ID) { command.value }

    @Provides @IntoSet
    fun patchAdminConfiguration(command: Lazy<PatchAdminConfigurationCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(PatchAdminConfigurationCommand.COMMAND_ID) { command.value }

    @Provides @IntoSet
    fun readAdminConfigurationStatement(command: Lazy<ReadAdminConfigurationStatementCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(ReadAdminConfigurationStatementCommand.COMMAND_ID) { command.value }

    @Provides @IntoSet
    fun listAdminSubordinates(command: Lazy<ListAdminSubordinatesCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(ListAdminSubordinatesCommand.COMMAND_ID) { command.value }

    @Provides @IntoSet
    fun createAdminSubordinate(command: Lazy<CreateAdminSubordinateCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(CreateAdminSubordinateCommand.COMMAND_ID) { command.value }

    @Provides @IntoSet
    fun readAdminSubordinate(command: Lazy<ReadAdminSubordinateCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(ReadAdminSubordinateCommand.COMMAND_ID) { command.value }

    @Provides @IntoSet
    fun replaceAdminSubordinate(command: Lazy<ReplaceAdminSubordinateCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(ReplaceAdminSubordinateCommand.COMMAND_ID) { command.value }

    @Provides @IntoSet
    fun patchAdminSubordinate(command: Lazy<PatchAdminSubordinateCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(PatchAdminSubordinateCommand.COMMAND_ID) { command.value }

    @Provides @IntoSet
    fun deleteAdminSubordinate(command: Lazy<DeleteAdminSubordinateCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(DeleteAdminSubordinateCommand.COMMAND_ID) { command.value }

    @Provides @IntoSet
    fun setAdminSubordinateStatus(command: Lazy<SetAdminSubordinateStatusCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(SetAdminSubordinateStatusCommand.COMMAND_ID) { command.value }

    @Provides @IntoSet
    fun readAdminSubordinateStatement(command: Lazy<ReadAdminSubordinateStatementCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(ReadAdminSubordinateStatementCommand.COMMAND_ID) { command.value }

    @Provides @IntoSet
    fun listAdminTrustMarkTypes(command: Lazy<ListAdminTrustMarkTypesCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(ListAdminTrustMarkTypesCommand.COMMAND_ID) { command.value }

    @Provides @IntoSet
    fun createAdminTrustMarkType(command: Lazy<CreateAdminTrustMarkTypeCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(CreateAdminTrustMarkTypeCommand.COMMAND_ID) { command.value }

    @Provides @IntoSet
    fun readAdminTrustMarkType(command: Lazy<ReadAdminTrustMarkTypeCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(ReadAdminTrustMarkTypeCommand.COMMAND_ID) { command.value }

    @Provides @IntoSet
    fun replaceAdminTrustMarkType(command: Lazy<ReplaceAdminTrustMarkTypeCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(ReplaceAdminTrustMarkTypeCommand.COMMAND_ID) { command.value }

    @Provides @IntoSet
    fun deleteAdminTrustMarkType(command: Lazy<DeleteAdminTrustMarkTypeCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(DeleteAdminTrustMarkTypeCommand.COMMAND_ID) { command.value }

    @Provides @IntoSet
    fun listAdminTrustMarkGrants(command: Lazy<ListAdminTrustMarkGrantsCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(ListAdminTrustMarkGrantsCommand.COMMAND_ID) { command.value }

    @Provides @IntoSet
    fun replaceAdminTrustMarkGrant(command: Lazy<ReplaceAdminTrustMarkGrantCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(ReplaceAdminTrustMarkGrantCommand.COMMAND_ID) { command.value }

    @Provides @IntoSet
    fun deleteAdminTrustMarkGrant(command: Lazy<DeleteAdminTrustMarkGrantCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(DeleteAdminTrustMarkGrantCommand.COMMAND_ID) { command.value }

    @Provides @IntoSet
    fun setAdminTrustMarkGrantStatus(command: Lazy<SetAdminTrustMarkGrantStatusCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(SetAdminTrustMarkGrantStatusCommand.COMMAND_ID) { command.value }

    @Provides @IntoSet
    fun readAdminTrustMarkToken(command: Lazy<ReadAdminTrustMarkTokenCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(ReadAdminTrustMarkTokenCommand.COMMAND_ID) { command.value }

}
