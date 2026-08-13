package dev.paperreader.logic

import android.content.Context
import androidx.core.content.FileProvider
import androidx.room.Room
import dev.paperreader.logic.data.LibraryDatabase
import dev.paperreader.logic.data.LibraryDatabaseMigrations
import dev.paperreader.logic.provider.builtin.ArxivProvider
import dev.paperreader.logic.provider.builtin.CrossrefProvider
import dev.paperreader.logic.network.ProviderHttpClient
import dev.paperreader.logic.provider.MutableProviderManager
import dev.paperreader.logic.provider.ProviderManager
import dev.paperreader.logic.provider.ProviderOrigin
import dev.paperreader.logic.provider.AvailableProviderPlugin
import dev.paperreader.logic.provider.UntrustedProviderPlugin
import dev.paperreader.logic.data.repository.RoomLibraryRepository
import dev.paperreader.logic.data.repository.RoomLocalFileRepository
import dev.paperreader.logic.data.repository.RoomTaskRepository
import dev.paperreader.logic.data.repository.RoomReadingHistoryRepository
import dev.paperreader.logic.data.repository.RoomReadingBookmarkRepository
import dev.paperreader.logic.data.repository.RoomMetadataBackupRepository
import dev.paperreader.logic.data.repository.RoomLocalPdfImportRepository
import dev.paperreader.logic.data.repository.RoomSavedSearchRepository
import dev.paperreader.logic.local.AndroidLocalPdfSourceResolver
import dev.paperreader.logic.domain.LOCAL_PDF_SOURCE_ID
import dev.paperreader.logic.usecase.FederatedPaperSearch
import dev.paperreader.logic.usecase.PaperReaderUseCases
import dev.paperreader.logic.usecase.paperReaderUseCases
import dev.paperreader.logic.task.TaskCoordinator
import dev.paperreader.logic.task.PaperDownloadCoordinator
import dev.paperreader.logic.network.PdfDownloader
import dev.paperreader.logic.network.ArxivReadableResourceFetcher
import dev.paperreader.logic.reader.ArxivReadablePaperLoader
import dev.paperreader.logic.reader.ReadablePaperCache
import dev.paperreader.logic.plugin.AndroidSourceExtensionTransport
import dev.paperreader.logic.plugin.CommunitySourceProvider
import dev.paperreader.logic.plugin.TrustedSourceExtension
import okhttp3.OkHttpClient
import java.io.Closeable

data class PaperReaderConfiguration(
    val databaseName: String = "paper-reader.db",
    val userAgent: String = "PaperReader/0.1 (Android)",
    val contactEmail: String? = null,
    val maximumPdfBytes: Long = 200L * 1024L * 1024L,
    val trustedSourceExtensions: List<TrustedSourceExtension> = emptyList(),
) {
    init {
        require(databaseName.isNotBlank())
        require(userAgent.isNotBlank())
        require(contactEmail == null || contactEmail.isNotBlank())
        require(maximumPdfBytes > 0)
        require(trustedSourceExtensions.map(TrustedSourceExtension::providerId).distinct().size == trustedSourceExtensions.size)
        require(trustedSourceExtensions.map(TrustedSourceExtension::packageName).distinct().size == trustedSourceExtensions.size)
    }
}

/** The supported UI entry point: state and use-cases, without Room DAOs or HTTP details. */
class PaperReaderLogic private constructor(
    val useCases: PaperReaderUseCases,
    val providers: ProviderManager,
    val tasks: TaskCoordinator,
    val downloads: PaperDownloadCoordinator,
    private val database: LibraryDatabase,
) : Closeable {
    override fun close() = database.close()

    companion object {
        fun open(
            context: Context,
            configuration: PaperReaderConfiguration = PaperReaderConfiguration(),
        ): PaperReaderLogic {
            val database = Room.databaseBuilder(
                context.applicationContext,
                LibraryDatabase::class.java,
                configuration.databaseName,
            )
                .addMigrations(*LibraryDatabaseMigrations.ALL)
                .build()
            val transport = ProviderHttpClient(
                userAgent = configuration.userAgent,
                mailto = configuration.contactEmail,
            )
            val applicationContext = context.applicationContext
            val availableCommunityProviders = mutableListOf<AvailableProviderPlugin>()
            val untrustedCommunityProviders = mutableListOf<UntrustedProviderPlugin>()
            val communityProviders = configuration.trustedSourceExtensions.mapNotNull { trustedRelease ->
                val packageInstalled = runCatching {
                    applicationContext.packageManager.getPackageInfo(trustedRelease.packageName, 0)
                }.isSuccess
                if (!packageInstalled) {
                    availableCommunityProviders += AvailableProviderPlugin(
                        packageName = trustedRelease.packageName,
                        displayName = trustedRelease.displayName,
                        versionCode = trustedRelease.versionCode,
                        providerIds = setOf(trustedRelease.providerId),
                    )
                    return@mapNotNull null
                }
                try {
                    val transport = AndroidSourceExtensionTransport(
                        context = applicationContext,
                        descriptor = trustedRelease.descriptor(),
                        trustedRelease = trustedRelease,
                    )
                    transport.verifyInstalledPackage()
                    trustedRelease to CommunitySourceProvider(transport)
                } catch (error: Exception) {
                    untrustedCommunityProviders += UntrustedProviderPlugin(
                        packageName = trustedRelease.packageName,
                        signerSha256 = trustedRelease.signerSha256.lowercase(),
                        reason = error.message?.take(160)?.takeIf(String::isNotBlank)
                            ?: "Provider package failed trust validation",
                    )
                    null
                }
            }
            val providers = MutableProviderManager(
                listOf(
                    ArxivProvider(transport),
                    CrossrefProvider(transport),
                ),
            )
            communityProviders.forEach { (trustedRelease, provider) ->
                try {
                    providers.register(
                        provider = provider,
                        origin = ProviderOrigin.COMMUNITY_PLUGIN,
                        packageName = trustedRelease.packageName,
                        versionCode = trustedRelease.versionCode,
                    )
                } catch (error: IllegalArgumentException) {
                    untrustedCommunityProviders += UntrustedProviderPlugin(
                        packageName = trustedRelease.packageName,
                        signerSha256 = trustedRelease.signerSha256.lowercase(),
                        reason = error.message?.take(160) ?: "Provider ID conflicts with an installed provider",
                    )
                }
            }
            providers.updateAvailable(availableCommunityProviders)
            providers.updateUntrusted(untrustedCommunityProviders)
            val library = RoomLibraryRepository(database)
            val search = FederatedPaperSearch(providers)
            val metadataBackup = RoomMetadataBackupRepository(
                database = database,
                installedProviderIds = {
                    providers.state.value.installed.map { it.descriptor.id }.toSet() + LOCAL_PDF_SOURCE_ID
                },
            )
            val tasks = TaskCoordinator(RoomTaskRepository(database))
            val downloads = PaperDownloadCoordinator(
                library = library,
                localFiles = RoomLocalFileRepository(database),
                tasks = tasks,
                downloader = PdfDownloader(
                    client = OkHttpClient(),
                    userAgent = configuration.userAgent,
                    contactEmail = configuration.contactEmail,
                    maximumBytes = configuration.maximumPdfBytes,
                ),
                filesDirectory = applicationContext.filesDir.toPath(),
                contentUriForFile = { path ->
                    FileProvider.getUriForFile(
                        applicationContext,
                        "${applicationContext.packageName}.files",
                        path.toFile(),
                    ).toString()
                },
            )
            val localPdfImports = RoomLocalPdfImportRepository(
                database = database,
                sourceResolver = AndroidLocalPdfSourceResolver(applicationContext.contentResolver),
                filesDirectory = applicationContext.filesDir.toPath(),
                sessionDirectory = applicationContext.noBackupFilesDir.toPath().resolve("local-pdf-import-session"),
                maximumBytes = configuration.maximumPdfBytes,
            )
            val savedSearches = RoomSavedSearchRepository(database)
            val readablePaperLoader = ArxivReadablePaperLoader(
                fetcher = ArxivReadableResourceFetcher(
                    client = OkHttpClient(),
                    userAgent = configuration.userAgent,
                    contactEmail = configuration.contactEmail,
                ),
                cache = ReadablePaperCache(
                    applicationContext.filesDir.toPath().resolve("readable-cache/arxiv-html"),
                ),
            )
            return PaperReaderLogic(
                useCases = paperReaderUseCases(
                    library,
                    RoomReadingHistoryRepository(database),
                    RoomReadingBookmarkRepository(database),
                    search,
                    localPdfImports,
                    metadataBackup,
                    savedSearches,
                    providers,
                    readablePaperLoader,
                ),
                providers = providers,
                tasks = tasks,
                downloads = downloads,
                database = database,
            )
        }
    }
}
