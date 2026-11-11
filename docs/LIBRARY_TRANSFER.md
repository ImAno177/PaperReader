# Library transfer

Status: implemented on the current branch. This guide defines the formats available in More >
Backup. Every import shows a preview and requires confirmation before changing the library.

## Backup formats

Metadata backup preserves library metadata, collections, reading state, bookmarks, and annotations.
Its existing format remains supported. It excludes PDFs, readable HTML caches, extension APKs,
credentials, and local filesystem paths.

Full library backup contains `library.json`, `metadata.backup`, and generated `documents/*.pdf`
entries. PDFs are streamed and checked against their recorded byte length and SHA-256. Restore
validates the entire archive before showing a preview, then commits metadata and PDF references in
one database transaction. Existing local PDFs are preserved even when the incoming hash differs.
A stale database path whose file no longer exists is repaired from the backup. Restore preserves
the local reference when file existence cannot be determined.
Annotations retain their exact hash and anchors; they become usable only with the matching document.
Readable HTML and its assets are excluded and may need to be acquired again.

Full backups allow at most 1,000 PDFs, 200 MiB per PDF, 512 KiB of manifest data, 10 MiB of metadata,
and 2 GiB each of compressed and expanded archive data. Retained staging ZIPs share a 4 GiB quota;
one active extraction can additionally require up to 2 GiB. A staged restore expires after 24 hours
and can be recovered after process recreation. Keep sufficient app-private storage available.

Android's document picker selects the external destination. A failed write may leave an incomplete
external file; remove it before retrying. The app does not promise atomic replacement of a document
provider's file. Backup archives are not encrypted; choose a destination appropriate for your papers
and notes.

## Bibliographies

BibTeX and RIS exchange titles, authors, canonical DOI/arXiv/PMID/PMCID identifiers, abstracts,
publication dates, and safe HTTP(S) source links. Imports and exports are bounded to 8 MiB and
20,000 records. These are metadata exchanges; they do not transfer PDFs or annotations.

The supported BibTeX subset uses literal field values. It does not execute TeX or expand macros,
string concatenation, or external resources. Malformed, conflicting, oversized, or unsupported input
fails before import. RIS supports repeated authors, multiline abstracts, and consistent alternate
title/date fields. Unknown fields are not preserved by a round trip.

Exact canonical aliases determine matches. Similar titles never cause an automatic merge; records
whose aliases refer to multiple existing works are skipped. Records without scholarly identifiers
receive a stable local import identity, so importing the same record again does not create a copy.
Imports recheck identity at confirmation and process records individually; a failure after partial
progress reports that state and can be retried.

Full publication dates and year-only values are preserved. A month without a day is reduced to its
known year with a preview warning because the domain does not store month-only dates. No missing
date component is invented.

## Notes

Markdown export includes annotated papers, note text, exact quotes, canonical identifiers, document
SHA-256, block/offset anchors, page numbers when known, and surrounding quote context. Text is escaped
as literal blocks. It is a read-only export, not a note-import or cross-revision re-anchoring format.

## References

- [Android document access](https://developer.android.com/training/data-storage/shared/documents-files)
  describes the system picker and document-provider behavior.
- [Android ZIP path traversal guidance](https://developer.android.com/privacy-and-security/risks/zip-path-traversal)
  explains why extraction uses bounded generated paths in a private empty directory.
- [BibTeX documentation](https://mirror.math.princeton.edu/pub/CTAN/biblio/bibtex/base/btxdoc.pdf)
  describes the broader BibTeX language; PaperReader intentionally implements the subset above.
