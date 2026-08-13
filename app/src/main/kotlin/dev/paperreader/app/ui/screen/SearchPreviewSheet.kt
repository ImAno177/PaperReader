package dev.paperreader.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.paperreader.app.R
import dev.paperreader.app.ui.components.PaperMetaRow
import dev.paperreader.app.ui.components.PaperPrimaryButton
import dev.paperreader.app.ui.components.PaperSecondaryButton
import dev.paperreader.app.ui.components.PaperSectionHeader
import dev.paperreader.app.ui.components.PaperSurface
import dev.paperreader.app.ui.model.SearchManifestationUi
import dev.paperreader.app.ui.model.SearchPaperUi
import dev.paperreader.app.ui.model.displayValue
import dev.paperreader.app.ui.model.safeWebUrlOrNull
import dev.paperreader.app.ui.model.toEnglishDisplayDateTime
import dev.paperreader.app.ui.theme.PaperTheme
import dev.paperreader.logic.domain.ManifestationType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchPreviewSheet(
    result: SearchPaperUi,
    saving: Boolean,
    savedWorkId: String?,
    saveFailed: Boolean,
    linkOpenFailed: Boolean,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onOpenSaved: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = PaperTheme.tokens.canvas,
        contentColor = PaperTheme.tokens.ink,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PaperMetaRow(
                source = result.sourceDisplayNames.joinToString(),
                year = result.publishedDate?.year?.toString(),
                identifier = result.primaryIdentifier?.displayValue(),
            )
            Text(result.title, style = MaterialTheme.typography.headlineSmall)
            Text(
                result.authors.joinToString().ifBlank { stringResource(R.string.unknown_authors) },
                color = PaperTheme.tokens.inkMuted,
                style = MaterialTheme.typography.bodyLarge,
            )
            result.citationMetrics?.let { metrics ->
                PaperSurface(contentPadding = PaddingValues(12.dp)) {
                    Text(
                        pluralStringResource(R.plurals.search_preview_citations, metrics.count, metrics.count),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(
                            R.string.search_preview_citation_source,
                            metrics.sourceDisplayName,
                            metrics.observedAt.toEnglishDisplayDateTime(),
                        ),
                        color = PaperTheme.tokens.inkMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            SearchPreviewSection(stringResource(R.string.search_preview_abstract)) {
                Text(
                    result.abstractText ?: stringResource(R.string.search_preview_no_abstract),
                    color = if (result.abstractText == null) PaperTheme.tokens.inkMuted else PaperTheme.tokens.ink,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            if (result.identifiers.isNotEmpty()) {
                SearchPreviewSection(stringResource(R.string.search_preview_identifiers)) {
                    Text(result.identifiers.joinToString(separator = "\n") { it.displayValue() })
                }
            }
            if (result.subjects.isNotEmpty()) {
                SearchPreviewSection(stringResource(R.string.search_preview_subjects)) {
                    Text(result.subjects.joinToString(), color = PaperTheme.tokens.inkMuted)
                }
            }
            if (result.manifestations.isNotEmpty()) {
                SearchPreviewSection(stringResource(R.string.search_preview_versions)) {
                    result.manifestations.forEach { manifestation ->
                        SearchManifestationPreview(manifestation, onOpenUrl)
                    }
                }
            }
            if (savedWorkId == null) {
                PaperPrimaryButton(
                    onClick = onSave,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (saving) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(stringResource(if (saving) R.string.saving_paper else R.string.save_paper))
                }
            } else {
                PaperPrimaryButton(
                    onClick = { onOpenSaved(savedWorkId) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.open_saved_paper))
                }
            }
            if (saveFailed) {
                Text(stringResource(R.string.save_failed), color = PaperTheme.tokens.danger)
            }
            if (linkOpenFailed) {
                Text(stringResource(R.string.search_preview_link_failed), color = PaperTheme.tokens.danger)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SearchPreviewSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PaperSectionHeader(title)
        content()
    }
}

@Composable
private fun SearchManifestationPreview(
    manifestation: SearchManifestationUi,
    onOpenUrl: (String) -> Unit,
) {
    val landingUrl = manifestation.landingPageUrl?.safeWebUrlOrNull()
    val pdfUrl = manifestation.pdfUrl?.safeWebUrlOrNull()
    PaperSurface(contentPadding = PaddingValues(12.dp)) {
        Text(
            listOfNotNull(
                manifestation.sourceDisplayName,
                manifestationTypeLabel(manifestation.type),
                manifestation.version,
            ).joinToString(" | "),
            style = MaterialTheme.typography.titleSmall,
        )
        manifestation.license?.let { license ->
            Text(
                stringResource(R.string.search_preview_license, license),
                color = PaperTheme.tokens.inkMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (landingUrl != null || pdfUrl != null) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                landingUrl?.let { url ->
                    PaperSecondaryButton(
                        onClick = { onOpenUrl(url) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.search_preview_source_page))
                    }
                }
                pdfUrl?.let { url ->
                    PaperSecondaryButton(
                        onClick = { onOpenUrl(url) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.search_preview_pdf))
                    }
                }
            }
        }
    }
}

@Composable
private fun manifestationTypeLabel(type: ManifestationType): String = stringResource(
    when (type) {
        ManifestationType.PREPRINT -> R.string.manifestation_preprint
        ManifestationType.ACCEPTED_MANUSCRIPT -> R.string.manifestation_accepted
        ManifestationType.VERSION_OF_RECORD -> R.string.manifestation_record
        ManifestationType.OTHER -> R.string.manifestation_other
    },
)
