package dev.paperreader.logic.domain

import java.time.Instant

@JvmInline
value class CollectionId(val value: Long) {
    init {
        require(value > 0) { "Collection ID must be positive" }
    }
}

data class PaperCollection(
    val id: CollectionId,
    val name: String,
    val sortOrder: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(name == normalizeCollectionName(name)) { "Collection name must be normalized" }
        require(sortOrder >= 0) { "Collection sort order cannot be negative" }
    }
}

fun normalizeCollectionName(value: String): String? {
    val normalized = value.trim().replace(WHITESPACE, " ")
    return normalized.takeIf { it.isNotEmpty() && it.length <= MAX_COLLECTION_NAME_LENGTH }
}

private val WHITESPACE = Regex("\\s+")
const val MAX_COLLECTION_NAME_LENGTH = 80
