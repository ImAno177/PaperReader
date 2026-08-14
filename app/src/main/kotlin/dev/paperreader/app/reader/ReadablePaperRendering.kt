package dev.paperreader.app.reader

import dev.paperreader.logic.domain.Annotation

internal data class ReadablePaperPalette(
    val background: String,
    val surface: String,
    val text: String,
    val mutedText: String,
    val border: String,
    val link: String,
    val selection: String,
)

internal data class ReadablePaperLayout(
    val spacing: ReadableTextSpacing = ReadableTextSpacing.COMFORTABLE,
    val sideMargin: ReadableSideMargin = ReadableSideMargin.COMFORTABLE,
)

internal enum class ReadableTextSpacing(
    val storageKey: String,
    val lineHeightCss: String,
    val paragraphMarginCss: String,
) {
    COMPACT("compact", "1.50", "0.78em"),
    COMFORTABLE("comfortable", "1.68", "0.92em"),
    RELAXED("relaxed", "1.85", "1.18em"),
    ;

    companion object {
        fun fromStorageKey(value: String?): ReadableTextSpacing = entries
            .firstOrNull { it.storageKey == value }
            ?: COMFORTABLE
    }
}

internal enum class ReadableSideMargin(
    val storageKey: String,
    val cssPixels: Int,
) {
    NARROW("narrow", 12),
    COMFORTABLE("comfortable", 20),
    WIDE("wide", 28),
    ;

    companion object {
        fun fromStorageKey(value: String?): ReadableSideMargin = entries
            .firstOrNull { it.storageKey == value }
            ?: COMFORTABLE
    }
}

private val READABLE_TEXT_ZOOM_LEVELS = intArrayOf(85, 100, 115, 130, 145, 160, 175, 190, 200)
private val BIBLIOGRAPHY_HREF = Regex(
    """href\s*=\s*(["'])#(bib\.[A-Za-z0-9._:-]{1,150})\1""",
    RegexOption.IGNORE_CASE,
)

internal fun nextReadableTextZoom(current: Int, increase: Boolean): Int = if (increase) {
    READABLE_TEXT_ZOOM_LEVELS.firstOrNull { it > current } ?: READABLE_TEXT_ZOOM_LEVELS.last()
} else {
    READABLE_TEXT_ZOOM_LEVELS.lastOrNull { it < current } ?: READABLE_TEXT_ZOOM_LEVELS.first()
}

internal fun readableSectionNavigationScript(anchor: String): String {
    require(anchor.matches(Regex("[A-Za-z0-9._:-]{1,160}")))
    return """
        (() => {
          const target = document.getElementById('$anchor');
          if (!target) return false;
          target.scrollIntoView({ block: 'start', behavior: 'auto' });
          return true;
        })()
    """.trimIndent()
}

internal data class ReadableTextSelection(
    val blockId: String,
    val startOffset: Int,
    val endOffset: Int,
    val quotePrefix: String,
    val quoteExact: String,
    val quoteSuffix: String,
)

internal enum class ReadableSelectionFailure {
    EMPTY,
    CROSS_BLOCK,
    TOO_LONG,
    INVALID,
}

internal sealed interface ReadableSelectionResult {
    data class Ready(val selection: ReadableTextSelection) : ReadableSelectionResult
    data class Unavailable(val reason: ReadableSelectionFailure) : ReadableSelectionResult
}

internal fun readableSelectionCaptureScript(): String = """
    (() => {
      const selection = window.getSelection();
      if (!selection || selection.rangeCount !== 1 || selection.isCollapsed) return { status: 'empty' };
      const range = selection.getRangeAt(0);
      const blockFor = node => {
        const element = node.nodeType === Node.ELEMENT_NODE ? node : node.parentElement;
        return element ? element.closest('[data-paperreader-block-id]') : null;
      };
      const startBlock = blockFor(range.startContainer);
      const endBlock = blockFor(range.endContainer);
      if (!startBlock || startBlock !== endBlock) return { status: 'cross_block' };
      const before = range.cloneRange();
      before.selectNodeContents(startBlock);
      before.setEnd(range.startContainer, range.startOffset);
      const start = before.toString().length;
      const exact = range.toString();
      const end = start + exact.length;
      if (!exact.trim()) return { status: 'empty' };
      if (exact.length > 2000) return { status: 'too_long' };
      const full = startBlock.textContent || '';
      if (start < 0 || end > full.length || full.slice(start, end) !== exact) return { status: 'invalid' };
      return {
        status: 'ready',
        blockId: startBlock.getAttribute('data-paperreader-block-id'),
        startOffset: start,
        endOffset: end,
        quotePrefix: full.slice(Math.max(0, start - 64), start),
        quoteExact: exact,
        quoteSuffix: full.slice(end, Math.min(full.length, end + 64))
      };
    })()
""".trimIndent()

internal fun readableAnnotationRenderScript(annotations: List<Annotation>): String {
    val safe = annotations
        .asSequence()
        .filter { it.id.matches(SAFE_READER_ID) && it.blockId.matches(SAFE_READER_ID) }
        .filter { it.startOffset >= 0 && it.endOffset > it.startOffset }
        .sortedWith(compareBy<Annotation> { it.blockId }.thenByDescending { it.startOffset })
        .map { annotation ->
            "{id:'${annotation.id}',blockId:'${annotation.blockId}',start:${annotation.startOffset},end:${annotation.endOffset}}"
        }
        .joinToString(",")
    return """
        (() => {
          document.querySelectorAll('mark.paperreader-highlight').forEach(mark => {
            const parent = mark.parentNode;
            while (mark.firstChild) parent.insertBefore(mark.firstChild, mark);
            parent.removeChild(mark);
            parent.normalize();
          });
          const annotations = [$safe];
          let applied = 0;
          const textNodes = block => {
            const walker = document.createTreeWalker(block, NodeFilter.SHOW_TEXT);
            const nodes = [];
            while (walker.nextNode()) nodes.push(walker.currentNode);
            return nodes;
          };
          annotations.forEach(annotation => {
            const block = document.querySelector('[data-paperreader-block-id="' + annotation.blockId + '"]');
            if (!block || annotation.start < 0 || annotation.end <= annotation.start || annotation.end > block.textContent.length) return;
            let cursor = 0;
            let wrapped = false;
            textNodes(block).forEach(node => {
              const nodeStart = cursor;
              const nodeEnd = cursor + node.data.length;
              cursor = nodeEnd;
              if (nodeEnd <= annotation.start || nodeStart >= annotation.end) return;
              const localStart = Math.max(0, annotation.start - nodeStart);
              const localEnd = Math.min(node.data.length, annotation.end - nodeStart);
              if (localEnd <= localStart) return;
              const selected = node.splitText(localStart);
              selected.splitText(localEnd - localStart);
              const mark = document.createElement('mark');
              mark.className = 'paperreader-highlight';
              mark.setAttribute('data-paperreader-annotation-id', annotation.id);
              mark.setAttribute('title', 'Highlighted passage');
              selected.parentNode.replaceChild(mark, selected);
              mark.appendChild(selected);
              wrapped = true;
            });
            if (wrapped) applied += 1;
          });
          return applied;
        })()
    """.trimIndent()
}

internal fun readableAnnotationNavigationScript(id: String): String {
    require(id.matches(SAFE_READER_ID))
    return """
        (() => {
          const target = document.querySelector('[data-paperreader-annotation-id="$id"]');
          if (!target) return false;
          target.scrollIntoView({ block: 'center', behavior: 'auto' });
          return true;
        })()
    """.trimIndent()
}

internal fun List<Annotation>.hasSameRenderedAnchors(other: List<Annotation>): Boolean =
    size == other.size && indices.all { index ->
        val first = this[index]
        val second = other[index]
        first.id == second.id &&
            first.blockId == second.blockId &&
            first.startOffset == second.startOffset &&
            first.endOffset == second.endOffset
    }

internal fun renderReadablePaperHtml(
    sanitizedBodyHtml: String,
    palette: ReadablePaperPalette,
    dark: Boolean,
    layout: ReadablePaperLayout = ReadablePaperLayout(),
    rewriteCitationLinks: Boolean = true,
    exportMetadata: ReadablePaperExportMetadata? = null,
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
    val rendererBodyHtml = if (rewriteCitationLinks) {
        rewriteBibliographyLinks(sanitizedBodyHtml)
    } else {
        sanitizedBodyHtml
    }
    val exportHeadMetadata = exportMetadata?.toHeadMetadata().orEmpty()
    val exportProvenance = exportMetadata?.toProvenanceMarkup().orEmpty()
    return """
        <!doctype html>
        <html lang="en" style="color-scheme: ${if (dark) "dark" else "light"}">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
          <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data:; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-src 'none'; object-src 'none'">
          $exportHeadMetadata
          <style>
            :root {
              --background: ${palette.background};
              --surface: ${palette.surface};
              --text: ${palette.text};
              --muted: ${palette.mutedText};
              --border: ${palette.border};
              --link: ${palette.link};
              --selection: ${palette.selection};
              --reader-line-height: ${layout.spacing.lineHeightCss};
              --reader-paragraph-margin: ${layout.spacing.paragraphMarginCss};
              --reader-side-margin: ${layout.sideMargin.cssPixels}px;
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
              line-height: var(--reader-line-height);
              overflow-wrap: anywhere;
              text-rendering: optimizeLegibility;
            }
            ::selection { background: var(--selection); color: var(--text); }
            mark.paperreader-highlight {
              background: var(--selection);
              color: inherit;
              padding: 0.04em 0;
              border-bottom: 2px solid var(--link);
            }
            .paperreader-document {
              width: min(100%, 48rem);
              margin: 0 auto;
              padding: 24px var(--reader-side-margin) 112px;
            }
            .paperreader-export-provenance {
              width: min(100%, 48rem);
              margin: 0 auto;
              padding: 14px var(--reader-side-margin) 0;
              color: var(--muted);
              font-size: 0.78em;
              line-height: 1.5;
            }
            .paperreader-export-provenance strong { color: var(--text); }
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
            p { margin: var(--reader-paragraph-margin) 0; }
            a { color: var(--link); text-decoration-thickness: 0.08em; text-underline-offset: 0.15em; }
            a:focus { outline: 3px solid var(--link); outline-offset: 3px; }
            .ltx_authors {
              display: grid;
              grid-template-columns: repeat(2, minmax(0, 1fr));
              gap: 14px 18px;
              margin: 14px 0 20px;
              color: var(--muted);
            }
            .ltx_author_before { display: none; }
            .ltx_creator, .paperreader-author { display: block; min-width: 0; margin: 0; }
            .ltx_personname, .paperreader-author-name { display: inline; color: var(--text); font-weight: 650; }
            .ltx_author_notes, .paperreader-author-details { display: block; margin: 4px 0 0; color: var(--muted); font-size: 0.88em; overflow-wrap: anywhere; }
            .ltx_contact { display: block; margin: 2px 0; }
            .ltx_contact_name { font-weight: 600; }
            .ltx_role_footnotemark .ltx_note_outer,
            .ltx_role_footnotemark .ltx_note_content { display: none; }
            .ltx_role_footnotemark .ltx_note_mark { font-size: 0.7em; vertical-align: super; }
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
            .paperreader-table-scroll {
              width: 100%;
              max-width: 100%;
              margin: 22px 0;
              overflow-x: auto;
              -webkit-overflow-scrolling: touch;
              overscroll-behavior-x: contain;
            }
            .paperreader-table-scroll > table {
              width: max-content;
              min-width: 100%;
              max-width: none;
              margin: 0;
              border-collapse: collapse;
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
              .paperreader-document {
                width: min(100%, 58rem);
                padding-inline: calc(var(--reader-side-margin) + 16px);
              }
            }
            @media (prefers-reduced-motion: reduce) {
              * { scroll-behavior: auto !important; }
            }
          </style>
        </head>
        <body>$exportProvenance$rendererBodyHtml</body>
        </html>
    """.trimIndent()
}

internal fun rewriteBibliographyLinks(sanitizedBodyHtml: String): String =
    BIBLIOGRAPHY_HREF.replace(sanitizedBodyHtml) { match ->
        val quote = match.groupValues[1]
        "href=$quote$CITATION_SCHEME://anchor/${match.groupValues[2]}$quote"
    }

private val SAFE_READER_ID = Regex("[A-Za-z0-9._:-]{1,160}")
