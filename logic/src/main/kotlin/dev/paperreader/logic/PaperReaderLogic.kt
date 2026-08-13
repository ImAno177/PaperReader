package dev.paperreader.logic

import android.content.Context
import androidx.core.content.FileProvider
import androidx.room.Room
import dev.paperreader.logic.data.LibraryDatabase
import dev.paperreader.logic.data.LibraryDatabaseMigrations
import dev.paperreader.logic.provider.MutableProviderManager
import dev.paperreader.logic.provider.PaperProvider
import dev.paperreader.logic.provider.ProviderManager
import dev.paperreader.logic.data.repository.RoomLibraryRepository
import dev.paperreader.logic.data.repository.RoomLocalFileRepository
import dev.paperreader.logic.data.repository.RoomTaskRepository
import dev.paperreader.logic.data.repository.RoomReadingHistoryRepository
import dev.paperreader.logic.data.repository.RoomReadingBookmarkRepository
import dev.paperreader.logic.data.repository.RoomAnnotationRepository
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
import dev.paperreader.logic.plugin.ExtensionStoreRegistry
import dev.paperreader.logic.plugin.SourceExtensionCoordinator
import dev.paperreader.logic.plugin.TrustedSourceExtension
import okhttp3.OkHttpClient
import java.io.Closeable

data class PaperReaderConfiguration(
    val databaseName: String = "paper-reader.db",
    val userAgent: String = "PaperReader/0.1 (Android)",
    val contactEmail: String? = null,
    val maximumPdfBytes: Long = 200L * 1024L * 1024L,
    val trustedSourceExtensions: List<TrustedSourceExtension> = emptyList(),
    val extensionStoreRegistry: ExtensionStoreRegistry? = null,
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
    val extensionStores: ExtensionStoreRegistry,
    private val sourceExtensionCoordinator: SourceExtensionCoordinator,
    private val database: LibraryDatabase,
) : Closeable {
    suspend fun reconcileSourceExtensions() = sourceExtensionCoordinator.reconcile()

    override fun close() = database.close()

    companion object {
        fun open(
            context: Context,
            builtInProviders: Iterable<PaperProvider>,
            configuration: PaperReaderConfiguration = PaperReaderConfiguration(),
        ): PaperReaderLogic {
            val database = Room.databaseBuilder(
                context.applicationContext,
                LibraryDatabase::class.java,
                configuration.databaseName,
            )
                .addMigrations(*LibraryDatabaseMigrations.ALL)
                .build()
            val applicationContext = context.applicationContext
            val providers = MutableProviderManager(builtInProviders)
            val extensionStores = configuration.extensionStoreRegistry ?: ExtensionStoreRegistry(
                directory = applicationContext.noBackupFilesDir.toPath().resolve("extension-stores"),
                userAgent = configuration.userAgent,
            )
            val sourceExtensionCoordinator = SourceExtensionCoordinator(
                context = applicationContext,
                providers = providers,
                developerExtensions = configuration.trustedSourceExtensions,
                stores = extensionStores,
            )
            sourceExtensionCoordinator.reconcileNow()
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
                    RoomAnnotationRepository(database),
                ),
                providers = providers,
                tasks = tasks,
                downloads = downloads,
                extensionStores = extensionStores,
                sourceExtensionCoordinator = sourceExtensionCoordinator,
                database = database,
            )
        }
    }
}
