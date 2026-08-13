package dev.paperreader.logic.data

import dev.paperreader.logic.domain.IdentifierType
import dev.paperreader.logic.domain.ManifestationType
import dev.paperreader.logic.domain.PaperAuthor
import dev.paperreader.logic.domain.PaperIdentifier
import dev.paperreader.logic.provider.RemoteManifestation
import dev.paperreader.logic.provider.RemotePaper
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Versioned, deterministic snapshot used only inside Room saved-search rows. */
internal object SavedSearchRecordCodec {
    private val json = Json { encodeDefaults = true }

    fun encode(record: RemotePaper): String {
        requireBounded(record)
        val payload = json.encodeToString(Snapshot.from(record))
        require(payload.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) {
            "Saved-search provider record exceeds the local snapshot limit"
        }
        return payload
    }

    fun decode(payload: String): RemotePaper {
        require(payload.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) {
            "Saved-search provider record exceeds the local snapshot limit"
        }
        return json.decodeFromString<Snapshot>(payload).toRemotePaper().also(::requireBounded)
    }

    fun fingerprint(payload: String): String = MessageDigest.getInstance("SHA-256")
        .digest(payload.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun requireBounded(record: RemotePaper) {
        require(record.authors.size <= MAX_AUTHORS)
        require(record.identifiers.size <= MAX_IDENTIFIERS)
        require(record.subjects.size <= MAX_SUBJECTS)
        require(record.manifestations.size <= MAX_MANIFESTATIONS)
        var characters = 0L
        fun account(value: String?) {
            characters += value?.length?.toLong() ?: 0L
            require(characters <= MAX_INPUT_CHARACTERS) {
                "Saved-search provider record exceeds the local input limit"
            }
        }
        account(record.providerId)
        account(record.providerRecordId)
        account(record.title)
        account(record.abstractText)
        record.authors.forEach { author ->
            account(author.displayName)
            account(author.givenName)
            account(author.familyName)
            account(author.orcid)
        }
        record.identifiers.forEach { identifier ->
            account(identifier.value)
            account(identifier.authority)
        }
        record.subjects.forEach(::account)
        record.manifestations.forEach { manifestation ->
            account(manifestation.version)
            account(manifestation.landingPageUrl)
            account(manifestation.pdfUrl)
            account(manifestation.license)
        }
    }

    @Serializable
    private data class Snapshot(
        val schemaVersion: Int = SCHEMA_VERSION,
        val providerId: String,
        val providerRecordId: String,
        val title: String,
        val abstractText: String? = null,
        val authors: List<AuthorSnapshot> = emptyList(),
        val identifiers: List<IdentifierSnapshot> = emptyList(),
        val subjects: List<String> = emptyList(),
        val publishedDateEpochDay: Long? = null,
        val updatedAtEpochMillis: Long? = null,
        val manifestations: List<ManifestationSnapshot> = emptyList(),
    ) {
        fun toRemotePaper(): RemotePaper {
            require(schemaVersion == SCHEMA_VERSION) { "Unsupported saved-search snapshot version" }
            return RemotePaper(
                providerId = providerId,
                providerRecordId = providerRecordId,
                title = title,
                abstractText = abstractText,
                authors = authors.map(AuthorSnapshot::toDomain),
                identifiers = identifiers.map(IdentifierSnapshot::toDomain).toSet(),
                subjects = subjects.toSet(),
                publishedDate = publishedDateEpochDay?.let(LocalDate::ofEpochDay),
                updatedAt = updatedAtEpochMillis?.let(Instant::ofEpochMilli),
                manifestations = manifestations.map(ManifestationSnapshot::toDomain),
            )
        }

        companion object {
            fun from(record: RemotePaper) = Snapshot(
                providerId = record.providerId,
                providerRecordId = record.providerRecordId,
                title = record.title,
                abstractText = record.abstractText,
                authors = record.authors.map(AuthorSnapshot::from),
                identifiers = record.identifiers
                    .map(IdentifierSnapshot::from)
                    .sortedWith(compareBy(IdentifierSnapshot::type, IdentifierSnapshot::value, IdentifierSnapshot::authority)),
                subjects = record.subjects.sorted(),
                publishedDateEpochDay = record.publishedDate?.toEpochDay(),
                updatedAtEpochMillis = record.updatedAt?.toEpochMilli(),
                manifestations = record.manifestations
                    .map(ManifestationSnapshot::from)
                    .sortedBy(ManifestationSnapshot::stableKey),
            )
        }
    }

    @Serializable
    private data class AuthorSnapshot(
        val displayName: String,
        val givenName: String? = null,
        val familyName: String? = null,
        val orcid: String? = null,
    ) {
        fun toDomain() = PaperAuthor(displayName, givenName, familyName, orcid)

        companion object {
            fun from(author: PaperAuthor) = AuthorSnapshot(
                author.displayName,
                author.givenName,
                author.familyName,
                author.orcid,
            )
        }
    }

    @Serializable
    private data class IdentifierSnapshot(
        val type: String,
        val value: String,
        val authority: String? = null,
    ) {
        fun toDomain() = PaperIdentifier(IdentifierType.valueOf(type), value, authority)

        companion object {
            fun from(identifier: PaperIdentifier) = IdentifierSnapshot(
                identifier.type.name,
                identifier.value,
                identifier.authority,
            )
        }
    }

    @Serializable
    private data class ManifestationSnapshot(
        val type: String,
        val version: String? = null,
        val landingPageUrl: String? = null,
        val pdfUrl: String? = null,
        val license: String? = null,
        val publishedDateEpochDay: Long? = null,
    ) {
        val stableKey: String
            get() = listOf(type, version, landingPageUrl, pdfUrl, license, publishedDateEpochDay).joinToString("|")

        fun toDomain() = RemoteManifestation(
            type = ManifestationType.valueOf(type),
            version = version,
            landingPageUrl = landingPageUrl,
            pdfUrl = pdfUrl,
            license = license,
            publishedDate = publishedDateEpochDay?.let(LocalDate::ofEpochDay),
        )

        companion object {
            fun from(manifestation: RemoteManifestation) = ManifestationSnapshot(
                manifestation.type.name,
                manifestation.version,
                manifestation.landingPageUrl,
                manifestation.pdfUrl,
                manifestation.license,
                manifestation.publishedDate?.toEpochDay(),
            )
        }
    }

    private const val SCHEMA_VERSION = 1
    private const val MAX_PAYLOAD_BYTES = 512 * 1024
    private const val MAX_INPUT_CHARACTERS = 256L * 1024L
    private const val MAX_AUTHORS = 512
    private const val MAX_IDENTIFIERS = 256
    private const val MAX_SUBJECTS = 512
    private const val MAX_MANIFESTATIONS = 128
}
