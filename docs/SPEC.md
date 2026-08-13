# PaperReader product specification and technical research

> Trạng thái: vertical slice UI/logic đã có live search, Room library, PDF download thật, Original PDF reader nội bộ và mobile reader từ official arXiv HTML; PDF-to-reflow/OCR vẫn đang triển khai
> Ngày chốt nguồn: 2026-08-11
> Quyết định đã chốt với người dùng: local-first, ưu tiên GitHub/F-Droid, minSdk 28, Apache-2.0, reader reflow là mặc định, PDF gốc là chế độ đối chiếu, annotation đầu tiên là highlight + note sidecar; mốc hiện tại là spec
> Tên dự án trong tài liệu chỉ là “Paper Reader”; tên thương hiệu và package ID chưa chốt.
> Quyết định UI hiện tại (2026-08-11): app chỉ đóng gói nội dung tiếng Anh; các locale khác được hoãn cho tới khi người dùng mở lại phạm vi localization.

## 1. Kết luận điều hành

Không nên fork nguyên Mihon rồi đổi “manga/chapter/page” thành “paper/version/PDF page”. Cách đó có vẻ nhanh ở tuần đầu nhưng làm sai mô hình dữ liệu, reader và quyền nội dung. Hướng phù hợp là:

1. Tái sử dụng các mẫu đã chứng minh của Mihon: thư viện local-first, source/provider abstraction, hàng đợi tải, WorkManager, backup có version, migration, store/index có chữ ký, UI Compose và luồng browse–library–updates–history.
2. Dùng thư viện Android có sẵn cho phần nền: Compose, Room, Paging, WorkManager, OkHttp/Retrofit, Kotlin Serialization, Coil, DataStore.
3. Xây mô hình khoa học đúng domain: Work là công trình trí tuệ; Manifestation là preprint, accepted manuscript, version of record hoặc một PDF cụ thể; mọi DOI, PMID, PMCID, arXiv ID và provider ID là alias có provenance.
4. Không nạp plugin cộng đồng vào process của app như Mihon. Plugin paper là APK service chạy dưới UID riêng và giao tiếp qua SDK/AIDL có giới hạn. Store vẫn dùng index ký số và kiểm tra chứng thư package.
5. Trải nghiệm đọc mặc định là **reflow**. App ưu tiên full text có cấu trúc do provider cung cấp (đặc biệt HTML chính thức của arXiv khi có), fetch/validate/sanitize rồi normalize vào `DocumentModel` có version, provenance và content hash trước khi cache local; raw HTML remote không bao giờ render trực tiếp. Nếu không có, `pdf-inspector`/extractor chạy cục bộ để tạo Markdown có page/source map và app sinh HTML an toàn. PDF gốc luôn được giữ bất biến và mở bằng AndroidX PDF/PdfRenderer khi cần đối chiếu công thức, hình, bảng hoặc trang trích xuất kém. TeX chỉ là input tương lai trong worker bị cô lập/giới hạn vì macro active/Turing-complete và archive traversal/bomb; không compile trong host process. Port Rust/NDK của `pdf-inspector` vẫn là P0 gate vì upstream chưa phát hành Android artifact.
6. Không giả định “có URL PDF” đồng nghĩa “được phép mirror”. Metadata và file phải lưu quyền, license, nguồn và thời điểm truy xuất riêng. Đặc biệt, arXiv cho phép tái sử dụng metadata CC0 nhưng phần lớn e-print không cho app/server tự do phân phối lại.

## 2. Mục tiêu, phạm vi và phần không làm

### 2.1 Mục tiêu sản phẩm

- Tìm paper từ nhiều nguồn công khai mà không bắt người dùng tạo tài khoản app.
- Gom các bản ghi trùng thành một Work nhưng vẫn giữ từng Manifestation và provenance.
- Thêm paper/PDF vào thư viện, tự trích xuất để đọc reflow offline, lưu tiến độ, tìm trong nội dung và ghi chú; luôn chuyển được về đúng trang PDF gốc.
- Theo dõi paper mới, revision mới và saved search giống luồng Updates của Mihon.
- Import/export dữ liệu học thuật bằng DOI, CSL JSON, BibTeX và RIS.
- Cho cộng đồng viết provider plugin, có store/index/update tương tự Mihon nhưng an toàn hơn.
- Chạy hoàn toàn local với các API công khai; backend chỉ là tùy chọn cho tác vụ nặng hoặc quota.

### 2.2 Không thuộc MVP

- Không vượt paywall, DRM, CAPTCHA hoặc điều khoản của publisher.
- Không crawl web tùy ý trong core app.
- Không có chatbot, tóm tắt AI, recommendation ML hoặc social network trước khi reader/library ổn định.
- Không sửa trực tiếp file PDF gốc; annotation trước tiên là lớp dữ liệu sidecar và chỉ ghi vào một bản export mới khi người dùng yêu cầu.
- Không có sync cloud bắt buộc. Backup qua Storage Access Framework hoạt động trước; WebDAV/Zotero sync là giai đoạn sau.
- Không hỗ trợ plugin DEX/JAR/JavaScript/WASM tùy ý chạy trong process của app.

## 3. Ảnh chụp Mihon đã khảo sát

Khảo sát mã nguồn Mihon được pin tại commit
[2506b049642af2211c1ef81e7369f752363f655d](https://github.com/mihonapp/mihon/tree/2506b049642af2211c1ef81e7369f752363f655d)
ngày 2026-08-09; cấu hình ứng dụng tại commit này ghi phiên bản 0.20.4.
Mihon dùng Apache-2.0 và yêu cầu Android 8 trở lên.

Các nguồn chính:

- [README và giấy phép Mihon](https://github.com/mihonapp/mihon/blob/2506b049642af2211c1ef81e7369f752363f655d/README.md)
- [Danh sách module](https://github.com/mihonapp/mihon/blob/2506b049642af2211c1ef81e7369f752363f655d/settings.gradle.kts)
- [Cấu hình app](https://github.com/mihonapp/mihon/blob/2506b049642af2211c1ef81e7369f752363f655d/app/build.gradle.kts)
- [Source API](https://github.com/mihonapp/mihon/tree/2506b049642af2211c1ef81e7369f752363f655d/source-api/src/main/kotlin/eu/kanade/tachiyomi/source)
- [Extension loader](https://github.com/mihonapp/mihon/blob/2506b049642af2211c1ef81e7369f752363f655d/app/src/main/java/eu/kanade/tachiyomi/extension/util/ExtensionLoader.kt)
- [Extension manager](https://github.com/mihonapp/mihon/blob/2506b049642af2211c1ef81e7369f752363f655d/app/src/main/java/eu/kanade/tachiyomi/extension/ExtensionManager.kt)
- [Extension store service](https://github.com/mihonapp/mihon/blob/2506b049642af2211c1ef81e7369f752363f655d/data/src/main/java/mihon/data/extension/service/ExtensionStoreService.kt)
- [Android manifest](https://github.com/mihonapp/mihon/blob/2506b049642af2211c1ef81e7369f752363f655d/app/src/main/AndroidManifest.xml)
- [Getting started](https://mihon.app/docs/guides/getting-started)
- [Reader settings](https://mihon.app/docs/guides/reader-settings)
- [Backups](https://mihon.app/docs/guides/backups)
- [Cảnh báo extension](https://mihon.app/docs/faq/browse/extensions)

### 3.1 Kiến trúc Mihon có thể học

Mihon tách app, core, data, domain, source-api, source-local, presentation, i18n và telemetry. Dữ liệu dùng SQLDelight; job nền dùng WorkManager; network dùng OkHttp; UI chủ yếu Compose; reader còn kết hợp View và Compose. Downloader kiểm soát đồng thời theo source, chạy foreground khi cần và lưu qua Storage Access Framework. Backup có schema/version, chọn phần cần lưu và báo extension/tracker còn thiếu khi restore.

Đây là các pattern nên mang sang:

- Domain/data/presentation tách trách nhiệm, không để model API chảy thẳng vào UI.
- Database là nguồn sự thật; network cập nhật database, UI quan sát Flow.
- Provider có ID ổn định, capability và pagination rõ ràng.
- Hàng đợi tải có pause, retry, reorder, network constraint và per-host rate policy.
- Backup có migration, restore preview và không mặc định nhét file tải lớn vào backup.
- Store extension có index versioned, signing key, compatibility version và trạng thái installed/update/orphaned.

### 3.2 Phần không bê nguyên

- Manga, chapter, scanlator, Page image và CBZ không tương đương paper, revision, manifestation và PDF.
- Reader ảnh của Mihon không có text layer, outline, citation, annotation theo tọa độ hoặc OCR.
- Trackers manga không tương đương Zotero, ORCID hoặc citation manager.
- WebView scraping và QuickJS không nên là mặc định cho nguồn khoa học.
- Extension Mihon là APK được nạp vào process app. Tài liệu Mihon cảnh báo extension có toàn quyền với app; đây là rủi ro không chấp nhận được với token, PDF riêng và annotation.
- Repo extension cộng đồng không do Mihon bảo chứng. Mã extension có thể mang license riêng theo từng file; không được coi cả repo là Apache chỉ vì file gốc của một dự án dùng Apache.

## 4. Ma trận tính năng Mihon → Paper Reader

Ký hiệu: P1 là vertical MVP; P2 là workflow nghiên cứu; P3 là hệ sinh thái; X là bỏ.

| Nhóm | Hành vi Mihon đã xác minh | Tương đương cho paper | Mốc | Quyết định tái sử dụng |
| --- | --- | --- | --- | --- |
| Library | Thêm series vào thư viện | Thêm Work/PDF/DOI vào thư viện | P1 | Giữ luồng, đổi domain |
| Library | Category | Collection và smart collection | P1/P2 | Giữ pattern |
| Library | Grid/list, filter, sort, badge | Grid/list; filter theo năm, tác giả, trạng thái đọc, offline, annotated | P1 | Giữ pattern |
| Library | Unread/download badge | Inbox/unread, downloaded, has-notes, OA status | P1 | Adapt |
| Library | Notes/memo và metadata edit | Research note, tag, metadata correction cục bộ | P2 | Adapt |
| Browse | Popular/latest theo source | Trending/recent theo provider hoặc saved query | P1/P2 | Adapt |
| Browse | Search và filter trong source | Search provider với filter field/year/type/OA | P1 | Adapt |
| Browse | Global search nhiều source | Federated search có dedupe tăng dần | P2 | Adapt, thêm rate control |
| Browse | Local source | Import PDF, BibTeX, RIS, CSL JSON, DOI/share intent | P1/P2 | Giữ ý tưởng |
| Browse | WebView/source settings | OAuth/browser login và provider settings khi thật sự cần | P3 | Không dùng scraping mặc định |
| Detail | Metadata và danh sách chapter | Metadata, abstract, identifiers, versions, OA locations, files | P1 | Viết model mới |
| Detail | Add/remove library | Add/remove Work, chọn collection/tag | P1 | Giữ luồng |
| Updates | Scheduled library update | Revision/new version/new citation/saved-search update | P2 | Adapt |
| Updates | Smart update và network constraint | TTL, ETag, Wi-Fi/charging, provider quota, backoff | P2 | Adapt |
| Updates | Upcoming | Expected issue/conference feed hoặc scheduled query | P3 | Chỉ làm khi có nguồn đáng tin |
| Updates | Notification | New revision, download complete, plugin update | P1/P2 | Giữ pattern |
| History | Read history và resume | Last opened, last page, reading state, time | P1 | Giữ pattern |
| Reader | Paged/vertical/long strip | Reflow dọc từ Markdown/HTML, typography/font size/line height/theme | P1 | Giữ continuous reading, đổi renderer |
| Reader | Zoom, rotation, fullscreen, background | Chế độ Original PDF: zoom, fit width/page, rotation, fullscreen | P1 | Chỉ dùng khi cần fidelity |
| Reader | Tap zones, volume keys, keep screen on | Điều hướng trang và accessibility shortcuts | P2 | Adapt |
| Reader | E-ink flash | E-ink profile | P3 | Giữ khi có nhu cầu |
| Reader | Crop/split/share/save page | Crop margin, share quote/page image, export copy | P2 | Adapt |
| Reader | Per-series reader settings | Per-document/per-journal reading profile | P2 | Adapt |
| Reader | Bookmark/read status | Bookmark block/page, read status, progress bằng locator bền vững | P1 | Adapt |
| Reader | Không có dual-page ổn định | Dual-page chỉ sau khi engine hỗ trợ tốt | P3 | Không tự viết sớm |
| Reader | Không có PDF text layer | Search, select, copy, heading/TOC/link trong reflow | P1 | Tính năng domain mới |
| Reader | Không có source mapping | Chip trang và nhảy Reflow ↔ Original cùng vị trí | P1 | Tính năng domain mới |
| Reader | Reader ảnh giữ fidelity từng trang | Công thức/hình/bảng trích xuất kém hiển thị cảnh báo + mở đúng vùng/trang PDF | P1/P2 | Không giả vờ fidelity |
| Reader | Không có scholarly annotation | Highlight + note sidecar theo exact sanitized document + block/text offsets đã có trong mobile reader; PDF geometry, ink và export để sau | P1/P2 | Tính năng domain mới |
| Downloads | Queue, pause, retry, reorder | PDF/attachment download queue | P1 | Giữ pattern |
| Downloads | Không tải song song cùng source | Token bucket theo host, Retry-After và giới hạn provider | P1 | Giữ nguyên tắc |
| Downloads | SAF và reindex | App-private store, SAF import/export, integrity scan | P1 | Adapt |
| Downloads | Cache cleanup | LRU thumbnail/text cache, explicit PDF cleanup | P1 | Adapt |
| Migration | Chuyển series giữa source | Merge provider records và chuyển canonical manifestation | P2 | Viết resolver mới |
| Tracking | MAL/AniList/... | Zotero/export/reference-manager connector | P2/P3 | Không bê adapter manga |
| Backup | Manual/automatic selective backup | Metadata, progress, annotations, settings, store config | P1 | Giữ pattern |
| Backup | Không kèm extension/download mặc định | Không kèm APK/PDF mặc định; attachment là tùy chọn rõ ràng | P1 | Giữ nguyên tắc |
| Backup | Không có native cross-device sync | SAF backup trước; WebDAV/Syncthing folder sau | P1/P3 | Không dựng backend sớm |
| Extensions | Store/index/install/update/trust | Provider store, compatibility, signature, update, orphan status | P2/P3 | Giữ lifecycle |
| Extensions | APK code chạy trong host | APK service chạy UID riêng qua IPC | P2 | Thay execution model |
| Settings | Theme, locale, reader/download/source settings | Material 3, English hiện tại; localization và reader/storage/provider/privacy settings sau | P1 | Giữ pattern |
| Diagnostics | Logs, source failure, update errors | Per-provider health, quota, provenance và redacted diagnostics | P2 | Adapt |

## 5. Mô hình dữ liệu bắt buộc

### 5.1 Thực thể

| Thực thể | Vai trò và trường tối thiểu |
| --- | --- |
| Work | ID nội bộ; title canonical; abstract; year; type; venue; createdAt/updatedAt |
| WorkIdentifier | workId; scheme; normalizedValue; rawValue; provider; verifiedAt |
| Author | ID nội bộ; displayName; ORCID nếu có |
| Authorship | workId; authorId; position; role; rawAffiliation |
| Manifestation | workId; kind; version; publishedAt; license; accessStatus; provenance |
| ProviderRecord | providerId; externalId; workId/manifestationId; raw URL; fetchedAt; etag |
| FileAsset | manifestationId; URI; SHA-256; MIME; bytes; pages; localState; source URL; rights |
| Extraction | file hash; schema; parser commit/options; state; confidence; page count; OCR/layout/encoding flags; artifact checksums |
| Collection | name; order; optional smart query |
| Tag | user-defined label |
| ReadingState | file hash; mode; block ID + text offset + quote; PDF page hint; status; lastOpenedAt |
| Annotation | file hash; extraction version; block/source/text range; quote prefix/exact/suffix; page + optional verified PDF geometry; note; color; createdAt |
| SavedSearch | provider set; query/filter; refresh policy; last cursor/check |
| ExtensionStore | store ID; index URL; public key; contact; trust state |
| Extension | package; SDK range; signing cert; capabilities; version; state |

### 5.2 Work khác Manifestation

Một công trình có thể xuất hiện lần lượt dưới dạng arXiv v1, arXiv v2, accepted manuscript và version of record có DOI. Chúng có thể chung Work nhưng không được ghi đè lẫn nhau. Progress và annotation gắn với hash của file; khi đổi PDF, app chỉ chuyển annotation nếu kiểm tra quote/page mapping thành công và luôn giữ bản gốc.

### 5.3 Chuẩn hóa và dedupe

Ưu tiên định danh:

1. DOI chuẩn hóa: lowercase, bỏ doi.org prefix và dấu câu cuối.
2. PMID, sau đó PMCID.
3. arXiv ID không có version cho Work; version được giữ ở Manifestation.
4. Semantic Scholar, OpenAlex, CORE và provider ID làm alias.
5. Title + first author + year chỉ là fuzzy candidate, không phải bằng chứng merge tự động.

Quy tắc an toàn:

- DOI trùng vẫn kiểm tra title/author/year trước khi merge.
- Không merge mù arXiv version với DOI chỉ vì title gần giống.
- Mọi merge tự động phải đảo ngược được.
- Mỗi field giữ provenance và fetchedAt; metadata sửa tay có ưu tiên nhưng không xóa raw provider value.
- Full-text location luôn giữ host, license/access status và thời điểm kiểm tra.

## 6. Public API và vai trò trong sản phẩm

Rate/quota dưới đây là ảnh chụp ngày 2026-08-11 và phải đọc lại header/tài liệu khi triển khai.

| Nguồn | Dữ liệu phù hợp | Auth/quota hiện hành | Full text và quyền | Vai trò |
| --- | --- | --- | --- | --- |
| [arXiv](https://info.arxiv.org/help/api/user-manual.html) | Preprint, version, category, Atom search; OAI-PMH cho bulk metadata | Không key; legacy API tối đa 1 request mỗi 3 giây và 1 connection | Có PDF URL nhưng license theo từng e-print; metadata CC0 | Built-in P1 |
| [Crossref](https://crossref.org/documentation/retrieve-metadata/rest-api/access-and-authentication/) | DOI metadata, reference, updates, license, ORCID/ROR | Public 5 req/s và concurrency 1; polite mailto 10 req/s và concurrency 3 | URL không đảm bảo quyền truy cập; không phải kho PDF | Built-in P1 |
| [OpenAlex](https://developers.openalex.org/guides/authentication) | Discovery rộng, authors, institutions, citations, OA locations | Không key có ngân sách nhỏ; free key hiện cho 1 USD/ngày, gồm 1.000 search hoặc 10.000 list/filter theo bảng giá hiện hành | Metadata/snapshot mở; PDF vẫn theo license nguồn | Optional built-in P2 |
| [Europe PMC](https://dev.europepmc.org/RestfulWebService) | Biomedical metadata, abstract, MeSH, links; fullTextXML cho PMC OA | Không key; tài liệu đã kiểm tra không công bố số rate cố định | Full text chỉ subset OA và license theo record | Built-in P2 |
| [NCBI E-utilities](https://www.ncbi.nlm.nih.gov/books/NBK25497/) | PubMed search, summary, PMID/PMCID linking | 3 req/s không key; 10 req/s với key; khai báo tool/email | Abstract có thể có copyright; không phải mọi record có PDF | Provider tùy chọn P2 |
| [Unpaywall](https://data.unpaywall.org/products/api) | DOI → OA locations, version, host, license | Email parameter; tài liệu hiện ghi 100.000 call/ngày | Resolver, không phải search engine; quyền theo location | Resolver P2 |
| [Semantic Scholar](https://www.semanticscholar.org/product/api) | Search, citation graph, recommendations, OA PDF URL | Quota phụ thuộc pooled access/API key và có thể đổi; phải backoff theo phản hồi | OA URL không chuyển license cho app | Plugin/optional P2 |
| [CORE](https://core.ac.uk/documentation/api) | Repository metadata và OA full text | API key; quota endpoint cụ thể, search batch chậm hơn single | Quyền kế thừa provider, không có blanket license | Plugin P3 |

### 6.1 Điều khoản arXiv ảnh hưởng trực tiếp kiến trúc

[Terms of Use arXiv](https://info.arxiv.org/help/api/tou.html) nói rõ metadata mô tả là CC0 nhưng e-print chịu copyright; đa số dùng quyền phân phối không độc quyền của arXiv. Vì vậy:

- App có thể cache PDF cục bộ cho việc đọc cá nhân/nghiên cứu.
- Backend tùy chọn không được mặc định mirror/serve mọi PDF arXiv.
- Backend chỉ nên cache metadata và redirect về nguồn, trừ khi license từng record cho phép.
- UI phải hiển thị source, version và license/access status.

### 6.2 Federated search

- Typeahead chỉ tìm local cache; remote search chạy khi submit hoặc sau debounce rõ ràng.
- Input là DOI hợp lệ phải dùng exact Crossref `filter=doi`, không dùng `query.bibliographic`; input là arXiv ID hợp lệ phải dùng `id_list`. Live validation ngày 2026-08-12 đã bắt và sửa trường hợp một DOI chính xác trả 18 kết quả nhiễu.
- Người dùng chọn provider hoặc một nhóm nhỏ; không fan-out tất cả API cho mỗi ký tự.
- PDF URL trong metadata vẫn có thể trả 401/403/404. Task phải giữ mã lỗi có cấu trúc, còn UI phải giải thích bằng tiếng Anh và dẫn người dùng mở nguồn trong browser; không tự thay URL hoặc xem URL là bằng chứng redistributable.
- Mỗi provider có token bucket, concurrency, Retry-After, exponential backoff và circuit breaker đơn giản.
- Kết quả hiện dần; IdentityResolver dedupe theo alias trong khi vẫn cho xem từng source record.
- Ranking P1 là deterministic: exact identifier, exact title, title relevance, recency. Không cần ML.
- Cache metadata có ETag/Last-Modified khi provider hỗ trợ; TTL theo provider.
- Không nhúng shared secret trong APK. API key là do người dùng nhập hoặc backend tùy chọn giữ.

### 6.3 Provider backlog

DataCite, DOAJ, bioRxiv/medRxiv, HAL, OpenReview, ORCID, institutional OAI-PMH và Zotero Web API là ứng viên plugin. Mỗi nguồn phải có một phiếu kiểm tra riêng về API, rate, auth, license và redistribution trước khi đưa vào core.

## 7. Reflow reader, PDF gốc, extraction và OCR

### 7.1 Quyết định trải nghiệm đọc

- **Reflow là mặc định** cho paper có text: một cột, cỡ chữ/line height/margin/theme điều chỉnh được, link và bảng không ép theo khổ trang giấy.
- **Original là chế độ fidelity**, không phải reader chính: PDF gốc luôn bất biến và dùng để kiểm tra công thức, hình, bảng, footnote hoặc trang có confidence thấp.
- Toolbar có nút `Reflow | Original`. Mỗi khối reflow giữ page hint; chuyển mode phải tới cùng trang gần nhất thay vì mở lại đầu tài liệu. Nhảy đúng vùng trong trang chỉ bật khi mapping đã được xác minh.
- Nếu extraction thất bại, bị mã hóa, cần OCR hoặc một cấu trúc không thể biểu diễn trung thực, UI nói rõ lý do và đưa hành động `Mở trang gốc`; không tạo nội dung đoán.

Vertical slice hiện thực ngày 2026-08-12 áp dụng quyết định này cho arXiv: detail ưu tiên
`Read mobile version` khi manifestation có revision chính xác; app lấy official versioned arXiv HTML,
sanitize và cache local trước khi render. Reader có native TOC, search, selectable text, text zoom
85–200%, MathML, figure/table responsive, theme sáng/tối, progress theo document hash và Original PDF
fallback. Progress giữa HTML và PDF chưa có page/block source map nên hai mode chưa quảng cáo nhảy cùng
vị trí. Paper ngoài arXiv hoặc arXiv không có official HTML vẫn nhận fallback có lý do, không nhận text
được suy đoán.

### 7.2 Pipeline local-first

The pipeline below is the PDF fallback path. Before it runs, the resolver probes provider full-text
locations and chooses a structured source only after rights, size, content, and sanitizer checks. A
validated arXiv HTML document is normalized into the same versioned local `DocumentModel`; it is not
treated as an authority to execute arbitrary HTML. TeX conversion remains deferred and isolated.

1. Resolve một full-text location có access status/license phù hợp.
2. Tải vào file tạm với HTTPS, redirect policy, content-length limit, MIME và PDF magic check.
3. Tính SHA-256, ghi provenance và chuyển atomically vào app-private storage; PDF gốc là immutable.
4. Gửi `ParcelFileDescriptor` sang parser service riêng; không chuyển toàn PDF bằng Kotlin `ByteArray` qua JNI và không chạy parse trên main thread.
5. `pdf-inspector` phân loại file và trích Markdown theo trang, positioned text, layout/OCR flags và tagged-PDF roles nếu có.
6. Ghép Markdown deterministically với page marker nội bộ; tạo `DocumentModel`/CommonMark AST có block ID, plain-text offsets và page/source map.
7. Ghi Room FTS theo block/page. Sinh HTML local đã escape/sanitize làm **render cache**, không làm dữ liệu chuẩn.
8. Reader mở reflow trước. Khối/trang confidence thấp, công thức/hình không bảo toàn hoặc trang rỗng có chip nhảy sang Original.
9. P2 chỉ OCR những trang `pages_needing_ocr`; kết quả OCR có provenance riêng và thay thế khối rỗng, không ghi đè PDF/Markdown gốc.
10. Cache key là `(pdfSha256, extractionSchema, parserCommit, parseOptionsHash, rendererVersion)`; đổi parser làm invalid cache nhưng không âm thầm di chuyển annotation.

Extraction foundation hiện đã validate cache identity, Markdown checksum, page count, block ID/content,
source-range bounds và parser output trước khi dùng lại hoặc ghi artifact; artifact hỏng đi theo fallback
reason thay vì được render. Điều này không biến `PdfTextExtractor` thành production parser.

### 7.3 Artifact chuẩn và locator

Mỗi PDF có các artifact app-private sau:

| Artifact | Vai trò |
| --- | --- |
| `original.pdf` | Nguồn bất biến, đối chiếu fidelity và export |
| `document.md` | Text chuẩn có page marker ổn định; có thể export cho người dùng |
| `extraction-manifest.json` | PDF hash, schema, commit/parser/options, page convention, confidence, OCR/layout/encoding flags và checksum artifact |
| `source-map.json.gz` | Block/source/plain-text range → PDF page; raw parser geometry, `mcid`/structure role và page transform khi thật sự có |
| `document.html` | Cache render local; xóa/tạo lại được và không đưa vào backup mặc định |

`DocumentModel` là sidecar versioned sinh từ Markdown hoặc một structured provider document, không phải
một format tác giả thứ hai. Mọi normalized document lưu source/provenance, content hash và renderer
version. Block ID phải deterministic từ page + loại node + occurrence/hash chuẩn hóa. Progress dùng
`{blockId, textOffset, quote, pageHint}`; chỉ tính pixel/rectangle lúc layout. PDF fallback dùng
`{pageIndex, normalizedOffset}`.

Liên kết Markdown block với `TextItem` là best-effort bằng page + normalized-text alignment và `(page, mcid)` khi có. Page-level mapping là bắt buộc; region-level mapping chỉ được ghi là `verified` nếu alignment không mơ hồ. Highlight trong reflow vẫn hoạt động bằng source/text anchor ngay cả khi không có PDF rectangle.

### 7.4 Firecrawl pdf-inspector trên Android

Snapshot đã audit được pin tại commit
[a67ee032695388f8b7bbfd029783bd255ebbb8a4](https://github.com/firecrawl/pdf-inspector/tree/a67ee032695388f8b7bbfd029783bd255ebbb8a4),
package `0.1.8`, MIT. Upstream hiện có Rust core, Python, Node NAPI và browser WASM nhưng **không có Android/JNI/UniFFI/cargo-ndk artifact**. NAPI prebuilt chỉ có desktop/server; WASM được mô tả cho browser và không phải Android SDK.

Contract hữu ích đã xác minh:

- `PdfProcessResult`: loại PDF, Markdown, page count, confidence, title, `pages_needing_ocr`, lý do OCR, table/column layout và encoding issues.
- Extraction theo trang trả page Markdown, `needs_ocr` và lý do. API này dùng page index 0-based, trong khi một số mảng OCR/layout và `TextItem.page` là 1-based; wrapper phải chuẩn hóa một lần sang `pageIndex0`.
- `TextItem` có text, x/y/width/height, font/style, page, loại Text/Image/Link/FormField và `mcid`; tagged PDF có structure role. `(page, mcid)` có thể nối heading/table/caption với text item khi PDF thật sự tagged.
- Markdown hỗ trợ heading/list/code/link/table/column/RTL và tùy chọn page/image placeholder. Scanned/image/vector/gibberish có thể trả trang Markdown rỗng và cờ OCR.

Giới hạn không được che giấu:

- Không OCR và không chuyển công thức PDF thành LaTeX/MathML. Figure/caption role hoặc image placeholder không đồng nghĩa đã trích được ảnh/công thức trung thực.
- Public contract không trả MediaBox/CropBox/page rotation. `x/y/width/height` chỉ là raw parser geometry: với trang thường `y` theo PDF user space từ bottom-left, width là text advance và height xấp xỉ font size, không phải bounding box bảo đảm. Không được tự chia cho một page size giả để tạo normalized rectangle; wrapper phải đọc page box + rotation/transform riêng nếu cần mapping vùng.
- Benchmark README chạy trên Apple M4, không phải bằng chứng hiệu năng Android.
- Upstream native `ToUnicode` CMaps hiện được đọc runtime từ `external/bcmaps` qua đường dẫn source tree;
  một `.so` Android không có đường dẫn đó. Spike wrapper đã embed CMaps, nhưng CJK/Type0 vẫn là
  acceptance blocker cho tới khi attribution, checksum, packaging và corpus pass được kiểm chứng.
- Main branch không phải release Android ổn định; phải pin commit, `Cargo.lock`, checksum và license inventory.

Tích hợp P0 được chọn là một Rust facade rất mỏng, build từ source bằng `cargo-ndk`, expose C ABI/JNI hẹp và trả file artifact thay vì object graph khổng lồ. Facade nhận FD/app-private file, cap concurrency của `rayon`, map panic/error thành kết quả có cấu trúc và chạy trong parser service riêng. P0 ưu tiên `arm64-v8a` + `x86_64` emulator; quyết định `armeabi-v7a` dựa trên device coverage trước release. NDK r28/AGP hỗ trợ 16 KB page phải được xác minh bằng ELF alignment và `zipalign`.

Spike hiện nằm dưới `build/native-spike/`: wrapper JNI hẹp nhận FD + flags và trả bytes PRX1 deterministic có giới hạn; CMaps được embed. Build `cargo-ndk` cho `arm64-v8a` và `x86_64` ở API 28 với ELF alignment 16 KiB đã pass. Đây vẫn chỉ là spike, chưa được app hoặc APK production tích hợp. Release bị chặn bởi cancellation, isolated-process execution, bounded input/memory, corpus validation, provenance/checksum reporting, target-filtered SBOM/license gate, attribution Adobe CMap/Korea1/AGL và packaging chỉ wrapper; không được quảng bá reflow runnable.

CMap phải được giải quyết trước khi chọn dependency: ưu tiên patch Android-only để embed bộ CMaps vào binary; phương án hai là copy asset có checksum vào no-backup storage và truyền đường dẫn bằng API rõ ràng. Không dựa vào `CARGO_MANIFEST_DIR` hay environment global trong production.

Backend parse không thuộc đường mặc định. Chỉ có thể thêm như opt-in riêng sau này cho OCR/tác vụ nặng, với consent từng file; không được dùng để né P0 gate hay mirror PDF trái license.

### 7.5 Markdown → HTML reflow

Đường render được đề xuất là [commonmark-java](https://github.com/commonmark/commonmark-java) → HTML tĩnh → Android WebView qua [WebViewAssetLoader](https://developer.android.com/reference/androidx/webkit/WebViewAssetLoader). Đây là dùng parser/layout engine có sẵn, không tự viết Markdown renderer. CommonMark AST/source spans cũng là nơi gắn block ID và source range.

Quy tắc bắt buộc:

- Chỉ load origin local `https://appassets.androidplatform.net`; hình/font/CSS đều bundle hoặc app-private, không dùng CDN.
- Chuẩn hóa riêng markup `<u>` do parser sinh thành node cho phép; escape mọi raw HTML khác, sanitize URL/protocol, chặn remote image/navigation, tắt file/content access, universal file URL và mixed content.
- P1 không cho document script chạy và dùng CSP `default-src 'none'`; link ngoài đi qua allowlist + explicit intent. HTML do provider trả về không bao giờ render trực tiếp. Native TOC chỉ bật JavaScript tạm thời cho lệnh app-owned `getElementById(safeAnchor).scrollIntoView()`, rồi tắt trong callback hoặc timeout; anchor đã qua allowlist ký tự và không nhận source script.
- Selection/highlight chỉ dùng lệnh app-owned cố định qua `evaluateJavascript` trên origin local đã xác minh; không nhận source script, không dùng `addJavascriptInterface`, và buộc tắt JavaScript sau callback hoặc timeout. Dữ liệu note/quote không được nội suy vào renderer script.
- `document.html` là cache theo renderer version. Annotation/progress không lưu DOM selector hoặc pixel vì HTML/CSS/WebView có thể đổi.
- Bảng rộng cuộn ngang trong block; figure thiếu dùng placeholder + caption/alt + hành động mở trang gốc. KaTeX không vào P1 vì `pdf-inspector` không sinh LaTeX; chỉ xét khi có nguồn TeX/MathML thật.

[Markwon](https://github.com/noties/Markwon) + TextView là fallback P0 nếu WebView không đạt accessibility/performance. Nó Apache-2.0 và ít lớp hơn, nhưng release mới nhất đã từ 2021, table/long-document/selection cần corpus test và sẽ tạo maintenance burden. Không mang cả hai renderer vào production: P0 chọn một qua adapter `ReflowRenderer`.

Provider-HTML slice đã chọn cùng một local WebView renderer nhưng không đi qua CommonMark: jsoup
parse official arXiv LaTeXML HTML, giữ semantic headings/tables/citations/MathML, chỉ nhúng raster
figure cùng host/path dưới ngân sách byte/count, và xuất sanitized body fragment. WebView từ chối
document JavaScript, file/content access, storage, mixed content và network loads; CSP mặc định từ chối mọi
resource ngoại trừ inline style do app tạo và data-image đã kiểm tra. HTML nguồn, cache và document
hash là ba khái niệm riêng; progress chỉ khôi phục khi manifestation và sanitized document hash đều
khớp. Đây là đường production cho official arXiv HTML, không làm thay đổi quy tắc Markdown là artifact
chuẩn của nhánh PDF extraction tương lai.

Với arXiv, ngày của `PaperWork` lấy từ Atom `published`, còn ngày của một manifestation có version
chính xác lấy từ Atom `updated` của version đó. License không được suy đoán từ metadata Atom: detail
chỉ báo rằng license sẽ xuất hiện sau khi mobile source được xác minh, rồi reader hiển thị đúng license
từ official HTML. Sanitizer chỉ chuẩn hóa đúng mẫu LaTeXML circled-step đã biết, giữ nguyên TeX lạ và
hiển thị warning rằng artifact nguồn đã được normalize; PDF và trang nguồn luôn còn để đối chiếu.

### 7.6 Original PDF mode

[AndroidX PDF 1.0.0-alpha19](https://developer.android.com/jetpack/androidx/releases/pdf) đang được dùng cho Original mode. Artifact khai báo minSdk 28 và nhánh read/render đã được backport, nhưng alpha19 yêu cầu compile Android 16 QPR2 (API 36.1 / extension 19+); `:app` vì vậy compile bằng SDK 36.1 trong khi `:logic` vẫn ở compileSdk 36, minSdk toàn project vẫn là 28 và targetSdk là 36. Viewer hiện có search, zoom/scroll, page restore theo exact PDF SHA-256, bookmark trang và system-viewer fallback. Bookmark Room v3 gắn với Work + manifestation + SHA-256 + page index zero-based; toolbar toggle trang hiện tại, list/jump theo thứ tự, và từ chối artifact không còn local hoặc sai hash. Preset Doodle/Retro/Neobrutalism cùng light/dark palette được giữ khi đi từ Compose sang reader View. Rotation không tách giả một lần đọc thành nhiều history session; thời gian paused/background không được cộng. Original mode vẫn chưa có highlight/note vì AndroidX PDF chưa cung cấp selection/source-map contract đủ tin cậy; highlight + note sidecar hiện chỉ bật trong mobile HTML reader. Không chọn fork AndroidPdfViewer/Pdfium chưa audit chỉ vì API tiện; MuPDF chỉ phù hợp nếu đổi license toàn app sang AGPL hoặc mua license.

Vertical slice tải PDF hiện dùng Room làm queue source of truth và WorkManager chỉ làm executor. UI có action theo state: active → Cancel, failed/cancelled → Retry/Clear, completed → Clear; Clear không xóa PDF. Cancel ghi `CANCELLED` bằng compare-and-set trước khi hủy unique work, manual retry reset attempt/progress/failure sau khi dọn chain cũ, và worker không được ghi `SUCCEEDED` đè lên cancellation thắng race. Global pause/resume, reorder và bulk action vẫn là parity gap P1 đã ghi rõ, chưa được coi là ngang Mihon.

Corpus phải có native text, scanned, mixed, multi-column, table, RTL, CJK/Type0/CID, formula, figure/caption, encrypted, malformed, form, annotation cũ, file 100k+ ký tự, file lớn và object bomb. Android docs khuyến nghị xử lý PDF không tin cậy trong isolated process và worker thread.

### 7.7 OCR và annotation

- Bản F-Droid không dùng Google ML Kit làm dependency lõi. Ứng viên FLOSS là [Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android), Apache-2.0, chạy theo trang ở P2; language pack tải có consent/checksum/license và job cancel được.
- Mốc annotation đầu tiên đã có trong mobile HTML reader: highlight + optional note sidecar, gắn với exact sanitized-document SHA-256, sanitizer block ID, UTF-16 offsets và quote context. Selection chỉ được chấp nhận trong một block; overlap bị từ chối; đổi document không tự re-anchor. PDF geometry, ink và export một PDF copy có annotation vẫn để sau.
- Annotation giữ PDF SHA-256, extraction version, block ID, source/plain-text range, quote prefix/exact/suffix, page và optional verified PDF geometry. Re-anchor theo exact source rồi quote+context; confidence thấp trở thành orphan cần người dùng xử lý, không tự gắn sai sang revision/parser mới.

## 8. Plugin cộng đồng

### 8.1 Điều học từ Mihon

Mihon source contract có popular/latest/search/detail/page list; extension APK khai metadata class/factory; loader kiểm tra extension library version và chữ ký; store index chứa package, version, signing key, icon/APK URL và source metadata. Lifecycle available–installed–update–untrusted rất đáng tái sử dụng.

Vấn đề là [Mihon tự cảnh báo](https://mihon.app/docs/faq/browse/extensions) extension bên thứ ba có full access vào app. Paper Reader không dùng ChildFirstPathClassLoader/DexClassLoader cho plugin ngoài.

### 8.2 Execution model đề xuất

- Built-in provider dùng cùng contract logic nhưng chạy trong app.
- Community provider là APK riêng, export một bound service với action cố định.
- Android chạy service dưới UID/package của plugin; app bind bằng explicit component.
- Provider SDK dùng AIDL nhỏ và payload Protobuf Lite có schema version.
- Response phân trang và giữ dưới giới hạn Binder; request lớn đi qua ParcelFileDescriptor.
- Host không truyền database, filesystem root hay token tổng quát cho plugin.
- File chỉ được chia sẻ bằng content URI read-only, thời hạn ngắn và theo thao tác người dùng.
- Plugin tự khai INTERNET và tự quản credential của chính nó; host chỉ hiển thị capability/host/privacy trước khi enable.

Contract tối thiểu:

| Lệnh | Ý nghĩa |
| --- | --- |
| getManifest | Provider, version, SDK range, capability, allowed hosts, privacy/license |
| search | Query/filter/cursor → provider records |
| getWork | External ID → metadata chi tiết |
| getUpdates | Cursor/since → records mới hoặc thay đổi |
| resolveFullText | Record → locations kèm access/license/provenance |
| cancel | Hủy request đang chạy |

Không thêm method generic executeScript hoặc rawHttpProxy.

### 8.3 Store/index

Store manifest cần:

- Signed payload: schemaVersion, storeId, displayName, websiteUrl, monotonic sequence, generatedAt.
- Extension: kind, package/service, bounded version range, host API range, signer SHA-256, HTTPS install URL, license, optional privacy URL, and kind-specific provider/theme metadata.
- Ed25519 envelope signs the exact payload bytes; the index URL and raw public key are supplied out of band.
- Package signing certificate phải khớp fingerprint trong index.
- Store được thêm bằng explicit user action; lần đầu hiển thị key fingerprint.
- Sequence rollback và same-sequence content change phải bị từ chối; last-known-good index được giữ lại.
- Key rotation sau này phải được ký bởi key cũ và key mới.
- Trạng thái mục tiêu: available, installed, disabled, update, incompatible, untrusted, orphaned.

Hiện tại host đã có user-managed signed store, install/update qua explicit HTTPS system page,
available/installed/update/incompatible/untrusted state, và fail-closed cache. Publisher-key
rotation vẫn deferred, cùng với disabled/orphaned management, preconfigured official store,
và in-app APK downloader.

### 8.4 GitHub/F-Droid

[F-Droid Inclusion Policy](https://f-droid.org/docs/Inclusion_Policy/) yêu cầu FLOSS, không tự cung cấp API key và chỉ cho tải executable bổ sung khi người dùng opt-in rõ ràng rằng họ đang bỏ qua kiểm tra F-Droid.

Đề xuất hai flavor nhưng chung code:

| Flavor | Cài plugin | Quyền |
| --- | --- | --- |
| oss/F-Droid | Mở custom F-Droid repo hoặc system package page; không có installer trong app | Không REQUEST_INSTALL_PACKAGES |
| full/GitHub | Có thể tải APK từ store sau cảnh báo/consent và verify chữ ký | REQUEST_INSTALL_PACKAGES chỉ ở flavor này |

Tốt nhất plugin cộng đồng cũng được build reproducibly và phát hành qua một custom F-Droid repo. Host chỉ phát hiện package/service đã cài, verify signer rồi bind. Main app vẫn có thể xin vào f-droid.org nếu dependency và hành vi đạt policy; plugin repo độc lập không mặc nhiên được F-Droid bảo chứng.

### 8.5 Threat model

| Nguy cơ | Kiểm soát bắt buộc |
| --- | --- |
| Plugin lấy token/PDF/annotation | UID riêng; capability; không host DB/file access; URI grant tối thiểu |
| Index/plugin bị thay | HTTPS; signed index; cert fingerprint; key rotation |
| Provider trả HTML/script độc | Parse thành DTO; sanitize; không render HTML/JS trực tiếp |
| SSRF/redirect lạ | Allowed host; HTTPS; redirect revalidation; block local/private IP khi host fetch |
| PDF/zip bomb | Size/page/object limits; isolated process; timeout; cancellation |
| Quota abuse/IP ban | Per-host rate policy; Retry-After; cache; user-visible health |
| License infringement | Per-location access/license; provenance; không mirror mặc định |
| Plugin treo hoặc response lớn | Timeout; pagination; Binder size cap; circuit breaker |

## 9. Stack Android đề xuất

Không pin “latest” trong spec. Khi scaffold phải đọc lại stable channel, khóa version catalog, bật Gradle dependency verification và lưu license notices.

| Nhu cầu | Lựa chọn | Lý do/quyết định |
| --- | --- | --- |
| Ngôn ngữ/concurrency | Kotlin, Coroutines, Flow | Trùng hệ Mihon và Android hiện đại |
| UI | Jetpack Compose Material 3, Navigation Compose | Official, không cần framework navigation khác |
| Database | Room + Room FTS4 | Official, migrations/relations/FTS phù hợp Android-only |
| Preferences | DataStore | Official; không lạm dụng database cho settings |
| Network | OkHttp + Retrofit + Kotlin Serialization | Mature, interceptor/cache/cancel/test tốt |
| Pagination | Paging 3 | Kết nối Room và remote result |
| Background | WorkManager | Download/update/backup/OCR có constraint/retry |
| Images | Coil | Cover/thumbnail/author avatar nếu có |
| Extraction | `pdf-inspector` commit-pinned + Rust facade/JNI build bằng cargo-ndk | Local-first; P0 gate CMap/perf/16 KB page |
| Reflow | commonmark-java AST/HTML + AndroidX WebKit/WebViewAssetLoader | Tận dụng engine HTML/CSS; local-only, không chạy source script ở P1 |
| PDF fidelity | AndroidX PDF alpha sau spike; PdfRenderer fallback | Chỉ Original mode, sau interface thay thế được |
| OCR | Tesseract4Android ở P2 | FLOSS/offline; model size là trade-off |
| Plugin IPC | AIDL + Protobuf Lite | ABI rõ, DTO versioned, process isolation |
| Backup | Protobuf + ZIP container, schema version | Tương tự bài học Mihon, restore/migration tốt |
| Citation | Crossref content negotiation; CSL JSON là canonical export | Không kéo formatter nặng vào P1 |
| Citation formatting | [citeproc-java](https://github.com/michel-kraemer/citeproc-java) chỉ sau Android size/runtime spike | Hỗ trợ CSL/BibTeX/RIS nhưng không cần cho vertical MVP |
| License UI | AboutLibraries hoặc generated notices đã bỏ timestamp | Minh bạch và giữ reproducible build |
| Dependency injection | Constructor injection thủ công lúc đầu | Chưa cần Hilt/Koin khi graph còn nhỏ |

Mihon snapshot đang dùng Compose, WorkManager, SQLDelight, OkHttp/Okio, Coroutines, Kotlin Serialization, Coil, Jsoup và nhiều viewer/UI library. Paper Reader chỉ lấy dependency khi có use case; không copy toàn version catalog hoặc các Git-commit dependency của Mihon.

### 9.1 Dự án/thư viện gần domain đã loại hoặc giữ làm ứng viên

| Dự án | Dữ kiện đã xác minh | Quyết định |
| --- | --- | --- |
| [Zotero for Android](https://github.com/zotero/zotero-android) | App paper/reference manager gần nhất; có reader, pdf-worker, citation processor và translator submodule. Toàn app là AGPLv3; build còn có PSPDFKit key hook và các service Google/Firebase tùy cấu hình. | Học workflow và làm Zotero connector; không copy code vào app Apache-2.0. Chỉ đổi quyết định nếu toàn dự án chấp nhận AGPLv3 và audit lại proprietary dependency. |
| [Readium Kotlin Toolkit](https://github.com/readium/kotlin-toolkit) | BSD-3-Clause, Kotlin, có PDF/EPUB/OPDS và locator/progression. PDF pagination/scroll/RTL có; search, highlight và TTS cho PDF đang được đánh dấu chưa hoàn chỉnh. PDF adapter dựa trên PDFium/AndroidPdfViewer và tăng app size. | Ứng viên nếu sau này hỗ trợ EPUB/OPDS; không chọn làm reader khoa học P1 vì thiếu text search/highlight PDF. |
| [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android) | Apache-2.0, port PDFBox cho Android, phù hợp parse/manipulate/export hơn là UI reader; README hiện vẫn dựa trên PDFBox 2.0.27. | Chỉ spike cho metadata, text hoặc export annotation nếu AndroidX PDF thiếu; không dùng làm renderer UI. |
| [citeproc-java](https://github.com/michel-kraemer/citeproc-java) | Apache-2.0; tạo citation/bibliography, hơn 10.000 CSL styles, import BibTeX/EndNote/RIS. | Ứng viên P2 sau benchmark Android runtime, APK size và locale/style packaging. |
| [Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android) | Apache-2.0, wrapper Tesseract/Leptonica hiện đại, offline, traineddata tách riêng. | Ứng viên OCR FLOSS P2; không bundle mọi language pack. |
| [pdf-inspector](https://github.com/firecrawl/pdf-inspector) | MIT/Rust, classifier/Markdown/position/structure extraction; không OCR, formula fidelity hay upstream Android artifact. A local wrapper spike embeds CMaps but is not production-ready. | Chọn làm extraction core nếu wrapper vượt P0 cancellation/isolation/bounds/corpus/license gate; pin commit và giữ PDF fallback. |
| [commonmark-java](https://github.com/commonmark/commonmark-java) | BSD-2-Clause, Java 11+, core nhỏ, AST/source spans, HTML renderer và GFM table/footnote extensions; Android được hỗ trợ best-effort từ API 19. | Ứng viên chính Markdown → HTML; pin version và test desugaring/minSdk 28. |
| [Markwon](https://github.com/noties/Markwon) | Apache-2.0, native TextView/Spannable và plugin table/LaTeX; release mới nhất hiện là 4.6.2 từ 2021. | Chỉ fallback P0 nếu WebView không đạt; không ship song song hai renderer. |

## 10. Cấu trúc code tối thiểu cho vòng triển khai

Theo yêu cầu tách tuyệt đối logic khỏi UI, project hiện có ba Gradle module:

| Module | Trách nhiệm |
| --- | --- |
| app | Chỉ UI/UX, Compose, navigation, presentation state và Android entry points; phụ thuộc một chiều vào `logic` |
| logic | Domain model, Room, repository/Flow, identity resolver, built-in providers, federated search, extraction/pdf-inspector facade, task state và plugin trust/contract |
| extension-api | AIDL và data contract có version, giới hạn kích thước cho source/theme APK bên ngoài; không chứa host storage, network hay UI |

Built-in provider chưa cần mỗi provider một module. `extension-api` được tách vì hai repo mẫu bên ngoài
đã compile và bind thật với host, đồng thời contract phải được publish độc lập. Trust/runtime source
vẫn thuộc `logic`; trust/runtime theme thuộc `app` vì host sở hữu visual rendering. Rust/JNI facade
vẫn ở `logic`; chỉ tách `reader` khi có consumer thứ hai hoặc native build isolation thực sự cần.

Luồng local-first:

UI chỉ gọi `PaperReaderLogic` và đọc Flow từ repository/use case → user action gọi interactor → logic dùng provider/network → kết quả normalize và transaction vào Room → Flow tự cập nhật UI. UI không import DAO, entity, built-in HTTP client hay JNI facade. PDF `DownloadWorker` hiện gọi coordinator/repository này; Room vẫn là queue duy nhất, không có database riêng trong worker. Saved-search refresh thủ công và aggregate background refresh đều đi qua facade; periodic WorkManager chỉ là executor và không sở hữu feed/queue thứ hai. Automatic-backup worker vẫn được hoãn; manual metadata backup cũng đi qua cùng facade.

Named collections hiện đã là vertical slice thật trên Room v2: tên được chuẩn hóa và duy nhất không phân biệt hoa/thường, một Work có thể thuộc nhiều collection, thay toàn bộ membership chạy trong transaction, xóa collection chỉ xóa junction và không xóa paper. Library lọc theo collection và trạng thái annotated từ cùng aggregate Flow; mỗi card hiển thị số highlight nhưng aggregate không tải quote/note text. More tạo/đổi tên/xóa, Detail gán/bỏ gán. Migration `1 -> 2` giữ nguyên dữ liệu v1. Tags, smart collections, reorder và bulk assignment vẫn được hoãn, không được quảng bá là parity đầy đủ.

Page bookmark hiện là vertical slice thật trên Room v3, không phải state UI tạm: bookmark chỉ được tạo khi manifestation thuộc đúng Work và DB có local PDF path với SHA-256 khớp không phân biệt hoa/thường. Danh sách được observe qua Flow, sắp theo trang, tồn tại qua force-stop, và bị xóa khi Work được xóa thành công; nếu việc xóa paper bị chặn bởi local artifact thì bookmark cũng được giữ. Migration `2 -> 3` giữ dữ liệu v2. Highlight/note exact-document đã là vertical slice riêng trong mobile HTML reader và dùng bảng annotation có sẵn của Room v4; PDF geometry, annotation export và re-anchor qua parser revision vẫn chưa triển khai.

Original-PDF reader hiện có chỉ báo `Page X of Y` sau khi tài liệu load và dialog nhảy tới số trang 1-based có kiểm tra biên; nhảy trang đi qua `PdfView.scrollToPage`, cập nhật bookmark/progress và vẫn khôi phục theo đúng manifestation + SHA-256. Chỉ báo bị ẩn trong loading/error; nhập sai không tự clamp. Đây là affordance của Original mode, không phải bằng chứng reflow đã chạy.

Viewport chọn page có diện tích hiển thị lớn nhất thay vì `firstVisiblePage` chỉ lộ một sliver, nên chỉ báo, bookmark và progress nhất quán; khôi phục exact-document vẫn yêu cầu đúng manifestation và SHA-256 như trên.

Saved Search Updates hiện là vertical slice thật trên Room v4. Search được nhận diện idempotent bằng
query đã chuẩn hóa cộng tập provider đã thực sự chạy; mỗi provider giữ riêng `lastChecked`,
`lastSuccess`, rate-limit/unavailable/invalid-response và tối đa 200 snapshot metadata có version.
Lần refresh thành công đầu tạo baseline không-unread; các provider-record mới hoặc fingerprint metadata
thay đổi ở lần sau mới thành unread. Refresh thủ công lấy trang NEWEST 20 bản ghi từ arXiv/Crossref,
cô lập lỗi từng nguồn và giữ stale results. Chỉ exact canonical alias mới được link sang Work; title
giống nhau không bao giờ tự merge. Provider set lấy từ chính snapshot của federated search. Checkpoint
đơn điệu và transaction từ chối completion cũ nên hai refresh chồng nhau không thể ghi lùi metadata,
failure hoặc `lastChecked`; metadata HTTP cũng bị chặn ở 8 MiB trước parser và snapshot từng hit có
giới hạn input/payload riêng. Aggregate background interactor refresh từng saved search tuần tự để
không nhân fan-out; một unique periodic WorkManager job có network constraint được điều khiển bởi
DataStore switch mặc định tắt trong More. Job chạy xấp xỉ mỗi 24 giờ, giữ typed provider failure trong
Room và không retry chúng như lỗi worker. Chỉ tổng `newlyUnread > 0` mới được phép phát notification;
API 33+ kiểm `POST_NOTIFICATIONS`, channel/global block được phản ánh rõ nhưng không chặn refresh,
Trong contract này “newly unread” gồm provider record mới hoặc fingerprint metadata đã đổi mà trước
đó không còn unread; copy notification không được gọi tất cả là paper mới. Tap notification mở
Updates. Migration `3 -> 4` giữ dữ liệu v3; scheduler không đổi schema.
Cursor/ETag delta, revision/citation feed và cadence tùy chỉnh vẫn chưa triển khai.

Local PDF/share intent hiện cũng là vertical slice thật. OpenDocument, `ACTION_VIEW` và
`ACTION_SEND` chỉ nhận `content://` với MIME PDF; logic giới hạn byte, kiểm `%PDF-`, tính SHA-256 và
tạo bản staged app-private trước khi UI cho sửa/xác nhận title. Session staged sống qua process
recreation và không cần mở lại URI/grant tạm. Confirm mới publish bản content-addressed và ghi
Work/manifestation/file trong một transaction. Exact SHA chỉ idempotent trong authority
`local-pdf`; cùng byte với PDF tải từ provider không phải bằng chứng hai Work trí tuệ là một.
Metadata local được backup, còn PDF/path vẫn bị loại và cùng SHA sẽ re-attach sau restore.

Share text DOI/arXiv cũng đã nối vào vertical slice thật. `ACTION_SEND text/plain` chỉ parse một
reference không mơ hồ: DOI canonical hoặc arXiv modern/legacy, kể cả URL nằm trong share text thông
thường. Version arXiv được giữ trong exact provider query; DOI đi qua exact Crossref filter. Request
được đưa vào Discover rồi submit qua federated search hiện hữu, không tạo network path riêng và không
tự lưu paper. Text tùy ý, payload quá lớn hoặc nhiều reference bị từ chối trước khi gọi provider và
nhận feedback tiếng Anh; intent đã xử lý được neutralize để không replay sau Activity recreation.

Navigation giữ Library là start destination mà không khôi phục lại tab vừa bị pop; local-PDF recovery ở trạng thái `Preparing` không tự mở More, chỉ các trạng thái cần người dùng mới điều hướng tới đó.

## 11. Navigation và UX chính

Bottom navigation đề xuất:

- Library: collection, tag, filter/sort, offline/inbox/annotated.
- Discover: provider selector, search, recent/trending, extensions.
- Updates: revision, saved-search result, download/plugin update.
- History: resume, recently read.
- More: a progressive-disclosure hub with Appearance; Collections; Reading & imports;
  Updates & notifications; Data & backup; and Sources/providers.

Paper detail:

- Canonical metadata + provenance.
- Abstract và authors.
- Identifier chips.
- Versions/manifestations timeline.
- Available full text với host, version, OA/license status.
- Add to library, download, cite/export, open external.

Reader:

- Mặc định Reflow; toggle Original luôn thấy được và giữ cùng page/block locator.
- Reflow có font size, line height, margin, theme, heading/TOC, find, select/copy và link; bảng rộng cuộn trong block.
- Page chip, extraction confidence và cảnh báo OCR/fidelity mở thẳng trang PDF gốc.
- Bookmark, note, highlight.
- Source/version/license accessible từ toolbar.
- Progress autosave local; crash không làm hỏng file hoặc annotation.

## 12. Backup, import/export và sync

Vertical slice hiện tại đã có manual metadata backup thật qua Storage Access Framework. Archive là
một ZIP entry duy nhất chứa ProtoBuf có format/schema/database version và giới hạn kích thước. Nội
dung hiện có Work, identifier, author, manifestation/provenance, collection membership, reading
state, history, exact-PDF page bookmark, annotation sidecar, saved-search query, provider checkpoint
và tối đa 200 hit snapshot cho mỗi provider hiện có trong Room. Restore luôn
decode/validate toàn bộ graph trước, hiển thị preview new/merged/skipped/conflict, provider còn thiếu
và anchor dormant, rồi dùng lại đúng plan đó trong một transaction. Chỉ exact canonical alias được
merge; title giống nhau không đủ bằng chứng. PDF/file row, download/task row, cache, plugin APK,
credential và private path không vào archive và không bị restore ghi đè. Pending preview được lưu
tạm trong app-private `noBackupFilesDir` để sống qua process recreation. Automatic backup,
attachment bundle, settings/provider-config backup và sync vẫn chưa được coi là đã triển khai.
Saved-search restore suy ra lại ID ổn định từ query/provider set, remap exact Work link, giữ trạng thái
read/unread cục bộ khi merge và vẫn giữ snapshot khi provider tương ứng chưa được cài.

Backup P1 gồm:

- Work/identifiers/authors/manifestations/provider records.
- Collections/tags, progress, bookmarks và annotations.
- Saved search, store list, provider settings không nhạy cảm.
- App settings và schema version.

Mặc định không gồm:

- Plugin APK.
- PDF/attachments.
- `document.html` và extraction cache có thể tái tạo; `document.md` chỉ kèm khi người dùng chọn export/attachment bundle.
- API key/token.

Người dùng có thể chọn attachment bundle riêng, có dự báo dung lượng. Restore phải có preview, transaction, collision report, missing plugin list và rollback khi lỗi.

Import/export:

- P1: PDF/share intent, DOI, CSL JSON.
- P2: BibTeX, RIS; citation formatting; Zotero-compatible export.
- P3: Zotero/WebDAV connector hoặc folder sync. Không dựng tài khoản/cloud backend chỉ để sync sớm.

## 13. License, privacy và compliance

- License app và provider SDK đã chốt là Apache-2.0 để tương thích việc tái sử dụng Mihon; phải giữ LICENSE/NOTICE và attribution cho phần copy/modify.
- Tên, icon, package ID phải khác Mihon; không ám chỉ Mihon bảo chứng.
- Audit license theo từng dependency và từng file lấy từ extension community.
- Không có telemetry mặc định. Nếu thêm, phải opt-in, tài liệu event schema và có thể tắt hoàn toàn.
- API key do người dùng nhập được mã hóa bằng Android Keystore; không vào backup/log.
- Diagnostics redact query nhạy cảm, URL token, header và đường dẫn file.
- Mọi full-text asset lưu provenance, rights/access và checksum.
- Không hỗ trợ provider có mục đích vượt paywall hoặc phân phối nội dung trái phép.
- F-Droid build chỉ dùng FLOSS dependency, không nhúng proprietary SDK/model hoặc shared API secret.

## 14. Lộ trình và exit criteria

### P0 — technical spikes

- Scaffold minSdk 28 và version catalog stable.
- Validate the pinned pdf-inspector wrapper spike, then complete cancellation, isolated-process
  bounds, CMap/license attribution, target-filtered SBOM, ABI/checksum reporting, and benchmark the
  corpus on Android before considering production integration.
- Prototype Markdown → CommonMark AST → local HTML/WebView; đo long-document memory, selection/search, TalkBack, 200% font scale, table/RTL/CJK và link/network isolation. Chỉ giữ một `ReflowRenderer` sau spike.
- So sánh AndroidX PDF alpha19 với PdfRenderer cho Original fidelity mode.
- External source/theme proof đã có: APK/UID riêng, verify certificate/API/kind/descriptor, bind,
  real OpenAlex search, complete theme icons, timeout và cancellation.
- Contract test arXiv/Crossref với fixture và 429/backoff.
- Room schema + identity resolver + migration test.
- Dependency/license/reproducible-build baseline.

Exit:

- Parser tạo Markdown/source map deterministic cho corpus native-text; CJK/Type0 qua test, process crash không kéo hỏng dữ liệu app và PDF gốc vẫn mở được.
- Reflow ↔ Original round-trip tới đúng page/block; region jump chỉ được quảng bá khi page-box/rotation mapping qua test. HTML không phát sinh request mạng hoặc chạy nội dung PDF như script.
- Demo plugin không đọc được host database/file.
- DOI/arXiv dedupe deterministic và merge có undo.
- Build sạch, license inventory không có dependency cấm F-Droid.

### P1 — vertical MVP

- Import local PDF và share intent — đã triển khai SAF/share, durable prepare/confirm, exact-SHA
  local provenance và metadata-backup re-attachment; watched folders/citation-file import chưa nằm
  trong lát cắt này.
- arXiv search/detail + Crossref DOI enrichment.
- Library/collection/filter, download queue, auto-extraction, offline reflow reader mặc định, Original fallback, progress/history.
- Manifestation/provenance/license display.
- Manual metadata backup đã có; automatic backup vẫn được hoãn.
- Demo community source và theme qua `extension-api` đã có ở hai repo độc lập.

Exit:

- Airplane mode vẫn mở thư viện/reflow/PDF gốc/progress; xóa HTML cache rồi mở lại cho kết quả tương đương.
- App restart/process death không mất queue, progress hoặc metadata.
- Không fan-out vượt rate; 429 được backoff.
- Backup/restore round-trip trên DB mới và DB có dữ liệu.

### P2 — research workflow

- OpenAlex, Europe PMC, Unpaywall.
- Federated search, persisted saved-search inbox và opt-in daily background refresh/notification đã có;
  revision/citation update, delta cursor và cadence tùy chỉnh vẫn còn trong P2.
- Per-page Tesseract OCR cho trang được classifier đánh dấu.
- Exact-document highlight + note đã có cho official arXiv HTML; PDF/source geometry,
  cross-revision re-anchor, annotation export và CSL JSON/BibTeX/RIS vẫn còn trong P2.
- Remaining store work includes publisher signing-material rotation, lifecycle states for disabled
  or orphaned packages, review policy, and an optional preconfigured store. User-managed signed
  stores and system-mediated install/update are already implemented.

Exit:

- Dedupe giữ đúng các manifestation.
- Annotation gắn hash và không tự chuyển sai sang revision.
- Store/cert mismatch bị chặn; oversized/timeout plugin không làm treo host.

### P3 — ecosystem và optional services

- Custom F-Droid provider repo, SDK template, CI/reproducible-build docs.
- Zotero/WebDAV/folder sync.
- DataCite/DOAJ/OpenReview/institutional plugins.
- Advanced annotation export, E-ink và dual-page khi engine hỗ trợ.

## 15. Kế hoạch kiểm thử bắt buộc

- Identity: DOI normalization, PMID/PMCID, arXiv version, fuzzy candidate, undo merge.
- Provider: golden fixtures XML/JSON, pagination, empty/malformed payload, 401/403/429/5xx, Retry-After.
- Database: migration từng version, transaction merge, FTS rebuild.
- Download: redirect, resumed download, checksum mismatch, low storage, process death.
- Extraction: output/page-index contract, deterministic artifact/cache invalidation, CMap CJK/Type0, OCR flags, formula/figure degradation, memory/process death và parser upgrade.
- Reflow: corpus mục 7; source↔render↔PDF anchor round-trip, 100k+ ký tự, search/select/copy, tables/RTL, TalkBack/font scale/theme và HTML/link/script/network injection.
- PDF fidelity: encrypted/malformed/large file, object bomb, memory pressure và parser crash fallback.
- Plugin: wrong signer, incompatible SDK, timeout, crash, huge response, uninstall giữa request.
- Backup: round-trip, selective restore, missing plugin, corrupted archive, no secret leakage.
- Privacy: log redaction, URI permission expiry, API key không vào backup.
- Distribution: offline F-Droid build, license scan, reproducible APK verification.

## 16. Rủi ro và quyết định còn mở

| Vấn đề | Trạng thái |
| --- | --- |
| AndroidX PDF vẫn alpha | Chỉ dùng sau `OriginalPdfRenderer`; PdfRenderer fallback, không khóa domain/UI vào API cụ thể |
| pdf-inspector chưa có upstream Android package | Wrapper JNI chỉ tồn tại trong `build/native-spike`; production vẫn là P0 gate cho cancellation, isolation, bounds, corpus, provenance/checksum, SBOM/license và packaging |
| Native CMaps cần provenance/packaging | Spike đã embed CMaps; attribution Adobe CMap/Korea1/AGL, checksum and target-filtered packaging must pass CJK/Type0 corpus before P1 |
| Markdown mất formula/figure/layout fidelity | Hiển thị confidence/unsupported state, page chip và Original fallback; không suy diễn LaTeX/ảnh |
| Local HTML/WebView mở thêm attack surface | Appassets origin, raw HTML escaped, URL allowlist, file/content/mixed-content off, source script bị loại, TOC command bounded, CSP và network-deny tests |
| Markwon dễ hơn nhưng upstream cũ | Chỉ là P0 fallback; pin/audit nếu chọn và không duy trì hai renderer production |
| OCR FLOSS tăng APK/model size | Tải language pack theo nhu cầu ở P2 |
| OpenAlex chuyển sang freemium/API key | App hỗ trợ user key và provider fallback |
| Europe PMC không công bố rate số cố định ở trang đã kiểm tra | Throttle bảo thủ, cache, đọc header/ToS lại |
| Full-text license khác nhau từng record | Rights/provenance là field bắt buộc, không boolean OA đơn giản |
| Plugin APK vẫn có thể tự exfiltrate dữ liệu nó nhận | Capability tối thiểu, UID riêng, không truyền host secret/file |
| F-Droid main repo có thể yêu cầu thay đổi | Giữ oss flavor sạch; plugin ở custom repo; xin review sớm |
| minSdk 28 bỏ Android 8 | Đã chốt; baseline là Android 9/API 28 |
| Apache-2.0 không cho copy code AGPL Zotero vào app | Đã chốt; chỉ tích hợp qua API/format công khai hoặc viết connector độc lập |

## 17. Source ledger

- [Mihon commit snapshot](https://github.com/mihonapp/mihon/tree/2506b049642af2211c1ef81e7369f752363f655d)
- [Mihon modules](https://github.com/mihonapp/mihon/blob/2506b049642af2211c1ef81e7369f752363f655d/settings.gradle.kts)
- [Mihon dependencies](https://github.com/mihonapp/mihon/blob/2506b049642af2211c1ef81e7369f752363f655d/gradle/libs.versions.toml)
- [Mihon extension loader](https://github.com/mihonapp/mihon/blob/2506b049642af2211c1ef81e7369f752363f655d/app/src/main/java/eu/kanade/tachiyomi/extension/util/ExtensionLoader.kt)
- [Mihon extension trust warning](https://mihon.app/docs/faq/browse/extensions)
- [Mihon backup behavior](https://mihon.app/docs/guides/backups)
- [Firecrawl pdf-inspector — audited commit](https://github.com/firecrawl/pdf-inspector/tree/a67ee032695388f8b7bbfd029783bd255ebbb8a4)
- [pdf-inspector TextItem geometry contract](https://github.com/firecrawl/pdf-inspector/blob/a67ee032695388f8b7bbfd029783bd255ebbb8a4/src/types.rs#L96-L130)
- [pdf-inspector private page-box extraction](https://github.com/firecrawl/pdf-inspector/blob/a67ee032695388f8b7bbfd029783bd255ebbb8a4/src/extractor/mod.rs#L1167-L1205)
- [pdf-inspector native CMap loading](https://github.com/firecrawl/pdf-inspector/blob/a67ee032695388f8b7bbfd029783bd255ebbb8a4/src/tounicode.rs#L1192-L1218)
- [cargo-ndk](https://github.com/bbqsrc/cargo-ndk)
- [Android 16 KB page-size guidance](https://developer.android.com/guide/practices/page-sizes)
- [commonmark-java](https://github.com/commonmark/commonmark-java)
- [Android WebViewAssetLoader](https://developer.android.com/reference/androidx/webkit/WebViewAssetLoader)
- [Android WebView native bridge guidance](https://developer.android.com/develop/ui/views/layout/webapps/native-api-access-jsbridge)
- [Markwon](https://github.com/noties/Markwon)
- [AndroidX PDF releases](https://developer.android.com/jetpack/androidx/releases/pdf)
- [Android PdfRenderer](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer)
- [Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android)
- [arXiv API terms](https://info.arxiv.org/help/api/tou.html)
- [arXiv API manual](https://info.arxiv.org/help/api/user-manual.html)
- [OpenAlex authentication/pricing](https://developers.openalex.org/guides/authentication)
- [Crossref access/rates](https://crossref.org/documentation/retrieve-metadata/rest-api/access-and-authentication/)
- [Crossref exact-match REST filters](https://www.crossref.org/documentation/retrieve-metadata/rest-api/rest-api-filters/)
- [Crossref content negotiation](https://www.crossref.org/documentation/retrieve-metadata/content-negotiation/)
- [Europe PMC REST](https://dev.europepmc.org/RestfulWebService)
- [NCBI E-utilities](https://www.ncbi.nlm.nih.gov/books/NBK25497/)
- [Unpaywall API](https://data.unpaywall.org/products/api)
- [Semantic Scholar API](https://www.semanticscholar.org/product/api)
- [CORE API](https://core.ac.uk/documentation/api)
- [F-Droid inclusion policy](https://f-droid.org/docs/Inclusion_Policy/)
- [F-Droid reproducible builds](https://f-droid.org/en/docs/Reproducible_Builds/)
- [Zotero Android source/license](https://github.com/zotero/zotero-android)
- [Readium Kotlin Toolkit](https://github.com/readium/kotlin-toolkit)
- [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android)
- [citeproc-java](https://github.com/michel-kraemer/citeproc-java)

## 18. Current implementation addendum (2026-08-12)

The readable-content resolver now has an explicit source priority. It prefers provider-supplied
structured full text (official arXiv HTML when available), then a future isolated/constrained
TeX-derived conversion, then the local PDF extraction path, and finally the immutable original PDF.
Provider HTML is never rendered as received: it is fetched under byte/time limits, validated,
sanitized, normalized into a versioned local document model, and cached with provenance and content
hash before a renderer consumes it. TeX remains a deferred input only; it must not be compiled in the
host process because active macros/Turing-complete behavior and archive traversal/decompression bombs
require isolation, bounded resources, and path controls.

For PDF-derived content, Markdown/source blocks remain canonical and HTML remains a regenerable local
render cache. The original PDF is retained for fidelity and fallback. The official versioned arXiv
HTML path is now a runnable mobile-reader vertical slice with a bounded fetcher, jsoup safelist,
same-path raster asset budgets, atomic integrity-checked cache, native TOC/search, local-only WebView,
document-hash progress, and Original fallback. The disposable HTML/figure cache is bounded to 160 MiB,
evicts complete least-recently-used document pairs, and cannot block an already verified document when
cache publication fails. Unsupported SVG `<object>` figures from official arXiv HTML are replaced
before safelist cleaning by an explicit accessible placeholder while preserving the figure caption;
the sanitizer policy version changes whenever this output contract changes, invalidating only the
regenerable cache and never silently moving annotations. This does not claim universal PDF-to-reflow,
TeX, or OCR support.

The More destination is a hub with six secondary branches: Appearance; Collections; Reading & imports;
Updates & notifications; Data & backup; and Sources/providers. Root destinations use a left-aligned
24sp title hierarchy. Appearance persists System/Light/Dark independently from the Doodle, Retro, or
Neobrutalism preset, and Library persists list/grid layout. Bottom navigation keeps equal visible item
geometry while retaining larger hit targets. These are presentation-only decisions and do not change
the `:app` → `:logic` boundary.

The native work is still a spike under `build/native-spike/`. Its narrow FD+flags JNI wrapper emits
bounded deterministic PRX1 bytes, embeds the required CMaps, and has passing arm64-v8a/x86_64 API-28
cargo-ndk builds with 16 KiB alignment. It is not integrated into production or the APK. Release is
NO-GO until cancellation, isolated-process execution, input/memory bounds, corpus coverage,
provenance/checksum reporting, target-filtered SBOM/license gates, Adobe CMap/Korea1/AGL attribution,
and wrapper-only packaging are complete.
