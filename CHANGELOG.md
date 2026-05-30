# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **Reading status tracking**: Users can set a per-series reading status (`PLAN_TO_READ`, `READING`, `COMPLETED`, `DROPPED`, `ON_HOLD`) via `PUT /library/series/{id}/status` and clear it via `DELETE /library/series/{id}/status`. The current user's status is returned as `readingStatus` on all `SeriesDto` responses. `GET /library/series` accepts a `readingStatus` query parameter to filter the list.
- **Push notifications**: New `/notifications` endpoints (ADMIN) manage notification targets. Supported types: `NTFY`, `Gotify`, `Discord` (webhook), and generic `WEBHOOK`. When new chapters are discovered after a series refresh, a notification is dispatched to all enabled targets. Test dispatch available via `POST /notifications/{id}/test`.
- **Auth rate limiting**: `POST /auth/login` and `POST /auth/register` are rate-limited per IP address. Defaults: 10 login attempts per 15 minutes, 5 register attempts per 60 minutes. Trusted networks (RFC 1918 + loopback) are exempt. Configurable via the `auth.rateLimiting` YAML section. Blocked requests receive `429 Too Many Requests` with a `Retry-After` header.
- **OPDS Page Streaming Extension (PSE)**: The OPDS series feed now includes a PSE `<link>` element for each downloaded chapter, allowing OPDS clients to stream individual pages without downloading the full CBZ. New endpoint: `GET /opds/v1/chapters/{id}/pages/{pageNumber}` (Basic Auth) serves page images with `ETag` and `Cache-Control: immutable` headers and supports `304 Not Modified` conditional requests.
- **Series language**: Series now carry a `language` field (BCP 47 tag, default `en`). `POST /library/series` accepts `language` in the request body. The language is validated against the connector's supported languages before the series is added.
- **MangaDex connector language support**: `MangaDexConnector` exposes 30 supported languages and uses the series language when fetching chapters.

### Changed

- **Breaking:** Unified data root layout — all persistent data now lives under a single configurable directory (`CHAPTERVAULT_DATA_DIR`, default `./data`). Sub-directories: `db/` (database), `library/` (chapter files), `thumbnails/` (cover images). Docker deployments now need a single volume mount (`./data:/app/data`) instead of separate `data` and `downloads` mounts.
- **Breaking:** `storage.basePath` YAML key renamed to `storage.libraryPath`. Update `config/application.yaml` if set explicitly.
- **Breaking:** `CHAPTERVAULT_STORAGE_PATH` env var renamed to `CHAPTERVAULT_LIBRARY_PATH`.
- Cover images are now stored as canonical JPEG (`thumbnails/{seriesId}.jpg`), transcoded from source format on write. Previously stored as extension-less files inside the chapter directory.
- Added `CHAPTERVAULT_THUMBNAILS_PATH` env var to override the thumbnails directory independently of `CHAPTERVAULT_DATA_DIR`.

### Architectural rewrite

Complete rebuild from scratch with strict layered architecture and a kernel-based extension model. Nothing from 0.4.x was carried forward at the code level; the two lines of history will be joined via a merge commit on first release.

#### Added

- **Task recovery on boot**: RUNNING tasks are reset to PENDING and all PENDING tasks are re-enqueued on startup, so downloads interrupted by a server restart resume automatically
- **Six-module Gradle project** with enforced one-way dependency graph: `:shared` -> `:kernel` -> `:extensions` / `:infrastructure` / `:interfaces` -> `:apps:server`; `:interfaces` and `:infrastructure` additionally depend on `:extensions` for connector types
- **`:shared`**: `Result<T>` / `AppError` sealed hierarchy, `Pagination<T>` / `PageRequest`, `Id` (UUID value class), `Time`, `ChapterFormat` sealed class (`Cbz`, `Folder`), `RateLimiter` (sliding-window with Mutex and burst support)
- **`:kernel` contracts**: domain models, runtime types, auth types, event bus, extension lifecycle
- **`kernel.api` as sole public surface**: `LibraryReadApi`, `LibraryCommandApi`, `ProgressApi`, `BookmarkApi`, `SystemApi`, `AuthApi`; no duplication with internal service interfaces
- **`:extensions` connector infrastructure**: `Connector` interface, `ConnectorRegistry`, `DefaultConnectorContext` (rate-limit buckets, retry, content negotiation)
- **`MockConnector`**: deterministic fake connector for tests; no HTTP calls
- **`MangaDexConnector`**: full HTTP implementation (MangaDex API v5): search, metadata, paginated chapters, at-home download
- **`CustomConnector`**: template connector for self-hosted sources
- **`:infrastructure` repositories**: `SeriesRepository`, `ChapterRepository`, `UserRepository` (bcrypt, 30-day session TTL), `TaskRepository` (implements `TaskReadStore`), `ProgressRepository`, `BookmarkRepository`
- **`:infrastructure` storage**: `CbzWriter`, `FolderWriter`, `ArchiveWriterSelector`, `FileStorage` (cover transcoding, page streaming, orphan cleanup)
- **`TaskExecutorService`**: coroutine dequeue loop with supervisor scope; dispatches FETCH_SERIES_METADATA, FETCH_CHAPTERS, DOWNLOAD_CHAPTER, DOWNLOAD_SERIES
- **Task retry**: up to 3 attempts with exponential backoff (30s, 120s, 600s)
- **`HttpClientFactory`**: upgraded with ContentNegotiation (JSON ignoreUnknownKeys), DefaultRequest (User-Agent: ChapterVault/1.0), HttpRequestRetry (3 retries, exponential delay, 429+5xx)
- **`:interfaces` REST routes** (42 endpoints): full library management (series + chapters), connector browsing, task management, per-user progress and bookmarks, page serving with ETag + immutable cache headers, health check, SSE event stream, Swagger UI
- **OPDS 1.0 feed**: navigation feed, paginated catalog feed, per-series chapter feed, CBZ streaming download endpoint; Basic Auth
- **`:interfaces` DTOs**: `ConnectorDto`, `SeriesSearchResultDto`, `SeriesMetadataDto`, `ChapterMetadataDto`, `TaskDto` (with payload map), `ReadProgressDto`, `BookmarkDto`, `ErrorResponse`
- **Auth**: BCrypt password hashing, 48-byte random session tokens (30-day TTL), bearer token auth for REST, basic auth for OPDS, ADMIN/USER role enforcement on all write routes
- **Real-time events**: `EventProjectionService` subscribes to `EventBus`; emits task state changes and chapter download status changes via SSE at `/events`
- **Health check** at `/health`: reports `ok` or `degraded` with separate `database` and `executor` sub-checks; returns 503 when degraded
- **`:apps:server` wiring**: Koin DI across all modules; `ConfigValidator` validates all paths and port on startup; `ServerBootstrap` launches executor and mounts all routes
- **`AppConfig` / `ConfigLoader`**: SnakeYAML + env-var override layer; all paths derive from `CHAPTERVAULT_DATA_DIR`; explicit vars override per-field
- **`Dockerfile`** + **`docker-compose.yml`**: JRE 21 Alpine image; single `./data` volume mount
- **CI workflow**: GitHub Actions build on push/PR

#### Technical details

- Kotlin 2.2.0, Gradle 9.5.1, JVM target 21 (JDK 26 runtime)
- Ktor 3.0.3 (server + client), Koin 4.0.0, Exposed 0.57.0 + SQLite JDBC 3.47.0.0
- kotlinx-coroutines 1.9.0, kotlinx-serialization 1.7.3, Logback 1.5.12
- 27 tests passing

---

## [0.4.1] - 2026-03-08

> Release note: Identical to `0.4.0`. An existing immutable `v0.4.0` tag prevented creating a release for that tag, so this release is published as `0.4.1`.

## [0.4.0] - 2026-03-07

### Added

- **Stable connector identity on series and chapters**: `connector` + `external_id` composite unique index on both `series` and `chapters` tables; all connectors now provide `externalId` on `SeriesSearchResult`, `SeriesMetadata`, and `ChapterMetadata`
- **`chapter_index` column**: Connectors supply a numeric ordering value (typically `chapterNumber * 1000`); `findBySeriesId()` returns chapters sorted by `chapterIndex` ascending (nulls last)
- **`auto_download` flag on series**: Persisted on the `series` table; `addToLibrary()` accepts `autoDownload: Boolean` and triggers a series download task when true
- **`chapters_fetched_at` timestamp on series**: Stamped after every successful chapter list fetch via `stampChaptersFetchedAt(seriesId)`
- **`connector` and `externalId` fields in API responses**: `SeriesDto` and `SeriesDetailResponse` now include `connector` and `externalId`; `ChapterDto` includes `chapterIndex`
- **`targetType` / `targetId` fields on task responses**: `TaskStatusResponse` exposes `targetType` (e.g. `SERIES`, `CHAPTER`) and `targetId` (UUID string) instead of the previous `seriesId` field
- **`idx_chapters_series_id` index**: Performance index on `chapters.series_id`
- **ExamplePlanConnector and ExampleBrowserPlanConnector**: Registered in development mode (`CHAPTERVAULT_ENV=development`)

### Changed

- **Unified repository models**: `CachedSeries` renamed to `Series`, `CachedChapter` renamed to `Chapter`; nullable fields naturally encode "not yet fetched" - no explicit partial/complete distinction
- **Unified API DTOs**: `CatalogSeriesDto`, `LibrarySeriesDto`, `CatalogChapterDto`, `LibraryChapterDto` replaced by shared `SeriesDto`, `ChapterDto`, `SeriesDetailResponse` used across all endpoints
- **Always-fresh detail endpoints**: `GET /api/v1/catalog/series/{id}` and `POST /api/v1/catalog/series/{id}/refresh` always fetch metadata from the source connector
- **Merge semantics on upsert**: `upsert()` and `upsertAllFromSearch()` never downgrade known fields to null - a subsequent search result cannot clear an `author` populated by a full metadata fetch
- **Library series detail shows all chapters**: `GET /api/v1/library/series/{id}` now returns all chapters with `downloadStatus` field instead of only downloaded chapters
- **`ChapterDto` includes download fields**: `downloadStatus`, `downloadedAt`, `filePath`, `fileSize` exposed on every chapter response
- **Task identity decoupled from domain FKs**: `tasks` table stores `target_type VARCHAR(32)` and `target_id VARCHAR(36)` (UUID as string, no foreign key) instead of FK columns pointing at series/chapter rows
- **Connector identity replaces `source_url` unique constraint**: Series and chapters are now keyed on `(connector, external_id)` - `source_url` retains its column but no longer carries a unique index
- **`series_tag` table renamed to `tags`**: Kotlin types renamed to `TagTable` / `TagEntity`
- **`publish_date` column widened**: Changed from `VARCHAR(32)` to `TEXT`
- **Catalog search endpoint restructured**: `POST /api/v1/catalog/lookup` replaced by `GET /api/v1/catalog/search?q=&url=&connector=`; both keyword and URL lookup share a single endpoint with response type `CatalogSearchResponse`
- **Pagination removed from list responses**: `ChapterListResponse`, `LibrarySeriesListResponse`, and `TaskListResponse` no longer include pagination metadata
- **`bulkDownload` concurrency unified**: `maxConcurrency` parameter removed from `bulkDownload`; concurrency is now controlled entirely by rate-limit bucket configuration

### Removed

- **`metadataFetchedAt` field** from `Series` model and repository - field nullability encodes data quality, not an explicit timestamp
- **`CachedSeries` / `CachedChapter` types** - replaced by `Series` / `Chapter`
- **`save()` / `saveFromSearch()` / `saveAllFromSearch()` methods** on `SeriesRepositoryPort` - replaced by `upsert()` / `upsertFromSearch()` / `upsertAllFromSearch()` with merge semantics
- **`CatalogSeriesDto` / `CatalogSeriesDetailResponse` / `CatalogChapterDto`** - replaced by `SeriesDto` / `SeriesDetailResponse` / `ChapterDto`
- **`LibrarySeriesDto` / `LibrarySeriesDetailResponse` / `LibraryChapterDto`** - replaced by shared catalog types
- **`domain/Series.kt` / `domain/Chapter.kt`** lightweight url-only domain types - unused, removed to eliminate ambiguity
- **`DownloadTaskRepositoryPort` / `DownloadTaskRepository` / `DownloadTaskTable` / `DownloadTaskEntity`** - replaced by `TaskRepositoryPort` / `TaskRepository` / `TaskTable` / `TaskEntity` in the renamed `tasks` table
- **`SeriesTagTable` / `SeriesTagEntity`** - replaced by `TagTable` / `TagEntity`
- **`seriesId` field on `TaskStatusResponse`** - replaced by `targetType` + `targetId`

## [0.3.0] - 2026-02-18

### Added

- **Domain-aware site rate limiting**: New `SiteRateLimiter` with per-host auto-bucketing and named bucket support, throttling individual outgoing HTTP requests independently of the orchestrator-level rate limiter
- **Named rate limit buckets**: Connectors can declare named buckets (e.g., `cdn`, `api`) with independent limits or unlimited throughput via `SiteRateLimits`
- **`RateLimitScope` enum**: Instructions and download items declare which rate limiting layers they participate in (`CONNECTOR`, `SITE`, `CONNECTOR_AND_SITE`, `NONE`)
- **Adaptive backoff**: `SiteRateLimiter` supports AIMD-based backoff on 429 responses with `Retry-After` header support
- **YAML `siteRateLimits` override**: Per-connector domain-aware rate limit configuration via `connectors.<name>.siteRateLimits` in config file, with support for default limits and named bucket overrides
- **Rate limit status endpoint**: `GET /api/v1/admin/ratelimits` exposes live rate limiter state including site buckets, backoff status, and per-connector orchestrator limits
- **`window_duration_millis` YAML support**: Both `rate_limit` and `site_rate_limits` overrides now accept `window_duration_millis` to configure the rate limit time window per connector

### Changed

- **`max_requests_per_minute` renamed to `max_requests_per_window`** in YAML configuration (`rate_limit`, `site_rate_limits.defaults`, and bucket overrides); `max_requests_per_minute` is still accepted as a legacy alias

### Fixed

- **YAML rate limit overrides not applied**: `connectors.<name>.rate_limit` and `connectors.<name>.site_rate_limits` were parsed correctly but silently discarded; overrides are now applied at startup via `applyTo` extension functions before rate limiters accept any requests
- **`RateLimitBuilder` DSL defaults**: `defaults { }` and `bucket { }` blocks no longer silently activate a 1-second min-delay and 60-requests-per-window limit when only one field is set; defaults now match `RateLimitConfig` (zero delay, no window limit)

### Removed

- **`SiteRateLimitConfig`**: Replaced by `SiteRateLimits` with domain-aware bucketing
- **`SiteRateLimitOverride`**: Replaced by `SiteRateLimitsOverride` with bucket support
- **Legacy `acquire()` methods**: Removed from both `RateLimiter` and `SiteRateLimiter` in favor of `withRateLimit()` which correctly holds concurrency permits for the duration of the request
- **YAML `siteRateLimit` key**: Replaced by `siteRateLimits` with richer configuration

## [0.2.0] - 2026-02-05

### Added

- **Series Caching**: Lookup results are now cached in the database, giving all series a stable ID
- **Library Separation**: New `inLibrary` flag distinguishes user's collection from cached metadata
- **Unified Lookup Endpoint**: `POST /api/v1/catalog/lookup` handles both URL lookups and searches
    - URL lookup: `{"url": "https://..."}` - auto-detects connector
    - Search: `{"query": "term", "source": "connector-id"}` - requires source to prevent concurrent load
- **Library Management API**: Add/remove series from library via `POST/DELETE /api/v1/library/series/{id}`
- **Metadata Refresh**: Force refresh metadata from source via `POST /api/v1/catalog/series/{id}/refresh`
- **Auto-fetch on Detail**: `GET /api/v1/catalog/series/{id}` auto-fetches full metadata if only search data exists
- **Cache Cleanup**: Configurable TTL-based cleanup of non-library cached series
- **Admin Endpoints**: Manual cache cleanup via `POST /api/v1/admin/cache/cleanup`
- **Cache Configuration**: New `cache.cleanup` config section with `enabled`, `ttl_days`, `run_interval_hours`

### Changed

- **Connector IDs**: Connectors now have explicit `id` field in `ConnectorConfig` for stable API lookups
- **Flattened API Responses**: Removed nested `download` object - `totalChapters`, `downloadedChapters` are now top-level fields
- `CatalogSeriesDto.id` is now always populated (non-nullable) since lookup results are cached
- Series are automatically added to library when downloading
- Database schema uses `createMissingTablesAndColumns` for automatic migrations
- Search operations require a `source` parameter (connector ID) to search one connector at a time
- `ConnectorRegistry.findById()` is now the preferred lookup method (replaces `findByName`)

### Removed

- `GET /api/v1/catalog/series` endpoint - use `POST /api/v1/catalog/lookup` for discovery and `GET /api/v1/library/series` for library browsing

### Database

- New columns on `series` table: `in_library`, `added_to_library_at`, `metadata_fetched_at`
- Automatic migration marks existing series with downloads as library items

## [0.1.0] - 2026-01-31

### Added

- Declarative extraction system with `extractData` DSL
- Bulk download primitive with `bulkDownload` for concurrent downloads with retry
- Unified DOM abstraction (`Document`, `Element` interfaces) and Jsoup implementations (`JsoupDocument`, `JsoupElement`)
- `FetchDocument` instruction returning parsed DOM
- `ExtractedDataResult` for structured data extraction
- `BulkDownloadResult` with per-item success/failure tracking
- Initial project structure with modular architecture
- Core domain models: Series, Chapter, Page, metadata types
- Connector interface with execution plan support
- Mock connector for testing
- Sample connectors with static data, demonstrating HTTP and browser automation
- Orchestrator for task scheduling and rate limiting
- Storage module with CBZ output support
- Database module with H2, SQLite, PostgreSQL support
- REST API for catalog browsing and download management
- OPDS v1.2 catalog implementation
- Rate limiting per connector
- Download task tracking with progress
- Execution plan DSL with browser instructions
- Comprehensive roadmap documentation

### Changed

- Connector `canHandle()` now requires exact domain match (no subdomain auto-matching)
- Example connectors migrated to use new declarative primitives
- LocalExecutor extended with new instruction handlers

### Fixed

- Added missing Jsoup dependency to orchestration module

### Technical Details

- Kotlin 2.3.0
- Ktor 3.4.0 (server and client)
- Exposed ORM for database access
- Playwright for browser automation
- Jsoup for HTML parsing
- Coroutines for async operations

---

## Version History Summary

| Version | Date       | Highlights                                                   |
|---------|------------|--------------------------------------------------------------|
| 0.4.1   | 2026-03-08 | Schema hardening, stable connector identity, task decoupling |
| 0.3.0   | 2026-02-18 | Domain-aware rate limiting, adaptive backoff, YAML overrides |
| 0.2.0   | 2026-02-05 | Library management, caching, stable IDs                      |
| 0.1.0   | 2026-01-31 | Initial public release                                       |

[Unreleased]: https://github.com/DevKoenv/ChapterVault/compare/v0.4.1...HEAD
[0.4.1]: https://github.com/DevKoenv/ChapterVault/compare/v0.3.0...v0.4.1
[0.3.0]: https://github.com/DevKoenv/ChapterVault/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/DevKoenv/ChapterVault/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/DevKoenv/ChapterVault/releases/tag/v0.1.0
