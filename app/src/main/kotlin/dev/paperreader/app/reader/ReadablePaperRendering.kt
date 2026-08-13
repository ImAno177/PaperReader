package dev.paperreader.app.reader

import android.content.Context
import android.util.AttributeSet
import android.webkit.WebView
import dev.paperreader.logic.domain.ManifestationId
import dev.paperreader.logic.domain.ReadingLocator
import dev.paperreader.logic.domain.ReadingState
import dev.paperreader.logic.domain.ReadingStatus
import dev.paperreader.logic.domain.WorkId
import java.time.Instant
import kotlin.math.roundToInt

internal data class ReadablePaperPalette(
    val background: String,
    val surface: String,
    val text: String,
    val mutedText: String,
    val border: String,
    val link: String,
    val selection: String,
)

private val READABLE_TEXT_ZOOM_LEVELS = intArrayOf(85, 100, 115, 130, 145, 160, 175, 190, 200)

internal fun nextReadableTextZoom(current: Int, increase: Boolean): Int = if (increase) {
    READABLE_TEXT_ZOOM_LEVELS.firstOrNull { it > current } ?: READABLE_TEXT_ZOOM_LEVELS.last()
} else {
    READABLE_TEXT_ZOOM_LEVELS.lastOrNull { it < current } ?: READABLE_TEXT_ZOOM_LEVELS.first()
}

internal fun renderReadablePaperHtml(
    sanitizedBodyHtml: String,
    palette: ReadablePaperPalette,
    dark: Boolean,
): String {
    listOf(
        palette.background,
        palette.surface,
        palette.text,
        palette.mutedText,
        palette.border,
        palette.link,
        palette.selection,
    ).forEach { require(it.matches(Regex("#[0-9A-Fa-f]{6}"))) }
    return """
        <!doctype html>
        <html lang="en" style="color-scheme: ${if (dark) "dark" else "light"}">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
          <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data:; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-src 'none'; object-src 'none'">
          <style>
            :root {
              --background: ${palette.background};
              --surface: ${palette.surface};
              --text: ${palette.text};
              --muted: ${palette.mutedText};
              --border: ${palette.border};
              --link: ${palette.link};
              --selection: ${palette.selection};
              font-synthesis: none;
            }
            * { box-sizing: border-box; }
            html { background: var(--background); -webkit-text-size-adjust: 100%; }
            body {
              margin: 0;
              background: var(--background);
              color: var(--text);
              font-family: system-ui, -apple-system, sans-serif;
              font-size: 18px;
              line-height: 1.68;
              overflow-wrap: anywhere;
              text-rendering: optimizeLegibility;
            }
            ::selection { background: var(--selection); color: var(--text); }
            .paperreader-document {
              width: min(100%, 48rem);
              margin: 0 auto;
              padding: 24px 20px 112px;
            }
            article, section, nav, figure { display: block; min-width: 0; }
            h1, h2, h3, h4, h5, h6 {
              color: var(--text);
              font-family: ui-serif, Georgia, serif;
              line-height: 1.22;
              letter-spacing: -0.012em;
              margin: 1.65em 0 0.62em;
              scroll-margin-top: 18px;
            }
            h1 { margin-top: 0; font-size: 1.72rem; letter-spacing: -0.025em; }
            h2 { font-size: 1.55rem; }
            h3 { font-size: 1.28rem; }
            h4, h5, h6 { font-size: 1.08rem; }
            p, li, dd { max-width: 72ch; }
            p { margin: 0.88em 0; }
            a { color: var(--link); text-decoration-thickness: 0.08em; text-underline-offset: 0.15em; }
            a:focus { outline: 3px solid var(--link); outline-offset: 3px; }
            .ltx_authors {
              display: flex;
              flex-wrap: wrap;
              gap: 4px 18px;
              margin: 14px 0 20px;
              color: var(--muted);
            }
            .ltx_author_before { display: none; }
            .ltx_creator { display: inline-flex; }
            .ltx_date, .ltx_role_affiliationtext { color: var(--muted); }
            .paperreader-author-notes {
              margin: 0 0 24px;
              border-left: 3px solid var(--border);
              color: var(--muted);
              font-size: 0.88em;
            }
            .paperreader-author-notes > summary {
              min-height: 48px;
              padding: 10px 14px;
              cursor: pointer;
              font-weight: 650;
            }
            .paperreader-author-notes-content { padding: 0 14px 14px; }
            .ltx_abstract {
              margin: 24px 0 30px;
              padding: 18px;
              border-left: 5px solid var(--border);
              background: var(--surface);
            }
            .ltx_title_abstract { margin-top: 0; }
            figure { margin: 30px 0; }
            img {
              display: block;
              max-width: 100%;
              height: auto;
              margin: 0 auto;
              background: white;
              border: 1px solid var(--border);
            }
            figcaption, caption {
              margin-top: 10px;
              color: var(--muted);
              font-size: 0.9em;
              line-height: 1.5;
              text-align: left;
            }
            .paperreader-figure-unavailable {
              display: block;
              padding: 18px;
              border: 1px dashed var(--border);
              color: var(--muted);
              background: var(--surface);
            }
            table {
              display: block;
              width: max-content;
              max-width: 100%;
              margin: 22px 0;
              overflow-x: auto;
              border-collapse: collapse;
              -webkit-overflow-scrolling: touch;
            }
            th, td {
              padding: 9px 11px;
              border: 1px solid var(--border);
              text-align: left;
              vertical-align: top;
            }
            th { background: var(--surface); }
            math {
              max-width: 100%;
              overflow-x: auto;
              overflow-y: hidden;
              padding: 0.16em 0;
            }
            math[display="block"], .ltx_equation, .ltx_equationgroup {
              display: block;
              margin: 1.15em 0;
              overflow-x: auto;
              overflow-y: hidden;
              -webkit-overflow-scrolling: touch;
            }
            pre, code, kbd, samp {
              font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
              font-size: 0.88em;
            }
            pre {
              max-width: 100%;
              padding: 14px;
              overflow-x: auto;
              border: 1px solid var(--border);
              background: var(--surface);
              white-space: pre;
              -webkit-overflow-scrolling: touch;
            }
            blockquote {
              margin: 1.2em 0;
              padding-left: 16px;
              border-left: 4px solid var(--border);
              color: var(--muted);
            }
            .ltx_bibliography { font-size: 0.92em; }
            .ltx_bibitem { margin-bottom: 0.75em; }
            sup, sub { line-height: 0; }
            @media (min-width: 720px) {
              body { font-size: 19px; }
              h1 { font-size: 2rem; }
              .paperreader-document { width: min(100%, 58rem); padding-inline: 36px; }
            }
            @media (prefers-reduced-motion: reduce) {
              * { scroll-behavior: auto !important; }
            }
          </style>
        </head>
        <body>$sanitizedBodyHtml</body>
        </html>
    """.trimIndent()
}

class ReadablePaperWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : WebView(context, attrs) {
    var onProgressionChanged: ((Double) -> Unit)? = null

    override fun onScrollChanged(left: Int, top: Int, oldLeft: Int, oldTop: Int) {
        super.onScrollChanged(left, top, oldLeft, oldTop)
        onProgressionChanged?.invoke(currentProgression())
    }

    fun currentProgression(): Double {
        val maximumScroll = (computeVerticalScrollRange() - height).coerceAtLeast(0)
        if (maximumScroll <= 0) return 0.0
        return (scrollY / maximumScroll.toDouble()).coerceIn(0.0, 1.0)
    }

    fun restoreProgression(progression: Double) {
        val bounded = progression.coerceIn(0.0, 1.0)
        post {
            val maximumScroll = (computeVerticalScrollRange() - height).coerceAtLeast(0)
            scrollTo(0, (maximumScroll * bounded).roundToInt())
        }
    }
}

internal fun readableStateForOpen(
    existing: ReadingState?,
    workId: WorkId,
    manifestationId: ManifestationId,
    documentSha256: String,
    now: Instant,
): ReadingState = ReadingState(
    workId = workId,
    manifestationId = manifestationId,
    locator = if (existing.matchesReadable(manifestationId, documentSha256)) {
        existing!!.locator.copy(pageIndex = null)
    } else {
        ReadingLocator(documentSha256 = documentSha256)
    },
    status = if (existing?.status == ReadingStatus.FINISHED) ReadingStatus.FINISHED else ReadingStatus.READING,
    updatedAt = now,
)

internal fun readableStateForProgress(
    existing: ReadingState?,
    workId: WorkId,
    manifestationId: ManifestationId,
    documentSha256: String,
    progression: Double,
    now: Instant,
): ReadingState = ReadingState(
    workId = workId,
    manifestationId = manifestationId,
    locator = ReadingLocator(
        documentSha256 = documentSha256,
        progression = progression.coerceIn(0.0, 1.0),
    ),
    status = if (existing?.status == ReadingStatus.FINISHED) ReadingStatus.FINISHED else ReadingStatus.READING,
    updatedAt = now,
)

internal fun restorableReadableProgress(
    state: ReadingState?,
    manifestationId: ManifestationId,
    documentSha256: String,
): Double? = state
    ?.takeIf { it.matchesReadable(manifestationId, documentSha256) }
    ?.locator
    ?.progression

private fun ReadingState?.matchesReadable(
    manifestationId: ManifestationId,
    documentSha256: String,
): Boolean = this?.manifestationId == manifestationId &&
    locator.documentSha256?.equals(documentSha256, ignoreCase = true) == true
