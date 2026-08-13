package dev.paperreader.logic.local

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import java.io.InputStream

internal data class LocalPdfSourceMetadata(
    val displayName: String?,
    val declaredByteLength: Long?,
)

internal sealed class LocalPdfSourceException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class InvalidUri : LocalPdfSourceException("Only content URIs can be imported")
    class Unavailable(cause: Throwable? = null) : LocalPdfSourceException("The selected source is unavailable", cause)
}

internal interface LocalPdfSourceResolver {
    fun inspect(sourceUri: String): LocalPdfSourceMetadata
    fun open(sourceUri: String): InputStream
}

internal class AndroidLocalPdfSourceResolver(
    private val contentResolver: ContentResolver,
) : LocalPdfSourceResolver {
    override fun inspect(sourceUri: String): LocalPdfSourceMetadata {
        val uri = sourceUri.validContentUri()
        return try {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use LocalPdfSourceMetadata(null, null)
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                LocalPdfSourceMetadata(
                    displayName = nameIndex.takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString),
                    declaredByteLength = sizeIndex.takeIf { it >= 0 && !cursor.isNull(it) }
                        ?.let(cursor::getLong)
                        ?.takeIf { it >= 0 },
                )
            } ?: LocalPdfSourceMetadata(null, null)
        } catch (error: SecurityException) {
            throw LocalPdfSourceException.Unavailable(error)
        } catch (error: RuntimeException) {
            throw LocalPdfSourceException.Unavailable(error)
        }
    }

    override fun open(sourceUri: String): InputStream {
        val uri = sourceUri.validContentUri()
        return try {
            contentResolver.openInputStream(uri) ?: throw LocalPdfSourceException.Unavailable()
        } catch (error: FileNotFoundException) {
            throw LocalPdfSourceException.Unavailable(error)
        } catch (error: SecurityException) {
            throw LocalPdfSourceException.Unavailable(error)
        } catch (error: RuntimeException) {
            throw LocalPdfSourceException.Unavailable(error)
        }
    }
}

private fun String.validContentUri(): Uri {
    val uri = runCatching(Uri::parse).getOrNull() ?: throw LocalPdfSourceException.InvalidUri()
    if (uri.scheme != ContentResolver.SCHEME_CONTENT || uri.authority.isNullOrBlank()) {
        throw LocalPdfSourceException.InvalidUri()
    }
    return uri
}
