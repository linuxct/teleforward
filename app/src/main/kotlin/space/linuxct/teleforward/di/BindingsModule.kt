package space.linuxct.teleforward.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import space.linuxct.teleforward.data.repo.AppCatalogRepository
import space.linuxct.teleforward.data.repo.AppCatalogRepositoryImpl
import space.linuxct.teleforward.data.repo.IntakeRepository
import space.linuxct.teleforward.data.repo.IntakeRepositoryImpl
import space.linuxct.teleforward.data.repo.OutboxRepository
import space.linuxct.teleforward.data.repo.OutboxRepositoryImpl
import space.linuxct.teleforward.data.repo.RulesRepository
import space.linuxct.teleforward.data.repo.RulesRepositoryImpl
import space.linuxct.teleforward.data.repo.SeenChannelRepository
import space.linuxct.teleforward.data.repo.SeenChannelRepositoryImpl
import space.linuxct.teleforward.data.repo.SeenConversationRepository
import space.linuxct.teleforward.data.repo.SeenConversationRepositoryImpl
import space.linuxct.teleforward.data.link.LinkResolver
import space.linuxct.teleforward.data.link.LinkResolverImpl
import space.linuxct.teleforward.data.secret.KeystoreSecretStore
import space.linuxct.teleforward.data.secret.SecretStore
import space.linuxct.teleforward.data.settings.SettingsRepository
import space.linuxct.teleforward.data.settings.SettingsRepositoryImpl
import space.linuxct.teleforward.data.telegram.MessageBuilder
import space.linuxct.teleforward.data.telegram.MessageBuilderImpl
import space.linuxct.teleforward.data.telegram.PairingRepository
import space.linuxct.teleforward.data.telegram.PairingRepositoryImpl
import space.linuxct.teleforward.data.telegram.TelegramSender
import space.linuxct.teleforward.data.telegram.TelegramSenderImpl
import space.linuxct.teleforward.data.update.UpdateRepository
import space.linuxct.teleforward.data.update.UpdateRepositoryImpl
import space.linuxct.teleforward.diag.DiagExporter
import space.linuxct.teleforward.diag.DiagExporterImpl
import space.linuxct.teleforward.diag.DiagStore
import space.linuxct.teleforward.diag.DiagStoreImpl
import space.linuxct.teleforward.service.NotificationMapper
import space.linuxct.teleforward.service.NotificationMapperImpl
import javax.inject.Singleton

/**
 * Binds every interface to its (Wave 0 placeholder) implementation. Wave 1 rewrites the impl
 * bodies only — these bindings, like the interfaces, are frozen.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {

    @Binds
    @Singleton
    abstract fun bindSecretStore(impl: KeystoreSecretStore): SecretStore

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindRulesRepository(impl: RulesRepositoryImpl): RulesRepository

    @Binds
    @Singleton
    abstract fun bindSeenChannelRepository(impl: SeenChannelRepositoryImpl): SeenChannelRepository

    @Binds
    @Singleton
    abstract fun bindSeenConversationRepository(
        impl: SeenConversationRepositoryImpl,
    ): SeenConversationRepository

    @Binds
    @Singleton
    abstract fun bindOutboxRepository(impl: OutboxRepositoryImpl): OutboxRepository

    @Binds
    @Singleton
    abstract fun bindIntakeRepository(impl: IntakeRepositoryImpl): IntakeRepository

    @Binds
    @Singleton
    abstract fun bindAppCatalogRepository(impl: AppCatalogRepositoryImpl): AppCatalogRepository

    @Binds
    @Singleton
    abstract fun bindMessageBuilder(impl: MessageBuilderImpl): MessageBuilder

    @Binds
    @Singleton
    abstract fun bindTelegramSender(impl: TelegramSenderImpl): TelegramSender

    @Binds
    @Singleton
    abstract fun bindLinkResolver(impl: LinkResolverImpl): LinkResolver

    @Binds
    @Singleton
    abstract fun bindPairingRepository(impl: PairingRepositoryImpl): PairingRepository

    @Binds
    @Singleton
    abstract fun bindNotificationMapper(impl: NotificationMapperImpl): NotificationMapper

    @Binds
    @Singleton
    abstract fun bindUpdateRepository(impl: UpdateRepositoryImpl): UpdateRepository

    @Binds
    @Singleton
    abstract fun bindDiagStore(impl: DiagStoreImpl): DiagStore

    @Binds
    @Singleton
    abstract fun bindDiagExporter(impl: DiagExporterImpl): DiagExporter
}
