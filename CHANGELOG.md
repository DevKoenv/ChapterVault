# Changelog

All notable changes to this project will be documented in this file.

The format is based on \[Keep a Changelog\]\(https://keepachangelog.com/en/1.1.0/),
and this project adheres to \[Semantic Versioning\]\(https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Architectural rewrite (targeting 0.5.0)

Complete rebuild from scratch with strict layered architecture and a kernel-based extension model. Nothing from 0.4.x was carried forward at the code level; the two lines of history will be joined via a merge commit when 0.5.0 is released.

#### Added

- **Six-module Gradle project** with enforced one-way dependency graph: `:shared` → `:kernel` → `:extensions` / `:infrastructure` / `:interfaces` → `:apps:server`
- **`:shared`**: `Result<T>` / `AppError` sealed hierarchy, `Pagination<T>` / `PageRequest`, `Id` (UUID value class), `Time`, `ChapterFormat` sealed class (`Cbz`, `Folder`)
- **`:kernel` contracts**: all domain models (`Series`, `Chapter`, `SeriesStatus`, `ChapterStatus`), runtime types (`Task`, `TaskType`, `TaskStatus`, `TaskQueue`, `TaskExecutor`, `TaskScheduler`, `TaskEvents`), auth types (`UserPrincipal`, `Role`, `Permission`), event system (`DomainEvent`, `EventBus`), extension contracts (`Extension`, `ExtensionLifecycle`, `ExtensionContext`, `ExtensionRegistry`, `Capability`)
- **`kernel.api` as sole public surface**: `LibraryReadApi`, `LibraryCommandApi`, `ProgressApi`, `BookmarkApi`, `SystemApi`, `AuthApi`; no duplication with internal service interfaces
- **`:extensions` stubs**: `ExtensionBase`, `CapabilityDsl`, `Connector` interface, `ConnectorRegistry`, `MockConnector`, `MangaDexConnector` stub, `OpdsExtension` stub, `MetadataProvider`, `AniListProvider` stub, `AdminExtension` stub
- **`:infrastructure` stubs**: `AppConfig` / `ConfigLoader`, `DatabaseFactory` with `SchemaUtils.create` on boot, Exposed table definitions (`SeriesTable`, `ChapterTable`, `UserTable`, `TaskTable`), `SeriesRepository` (stub), `ChapterArchiveWriter` interface, `CbzWriter` / `FolderWriter` / `ArchiveWriterSelector`, `HttpClientFactory`, `RateLimiter`, `BrowserPool` stub
- **`:interfaces` stubs**: DTOs v1 (`SeriesDto`, `ChapterDto`, `TaskDto`, `UserDto`), mappers v1, REST routes returning 501, `EventProjectionService` + `/events` WebSocket endpoint, OPDS routes
- **`:apps:server`**: Koin module definitions, `DependencyInjection`, `ServerBootstrap`, `Main`; `fatJar` Gradle task produces `server-fat.jar` for Docker
- **`Dockerfile`** + **`docker-compose.yml`**: JRE 21 Alpine image; volume mounts for `data/`, `downloads/`, `config/`
- **CI workflow**: GitHub Actions build on push/PR
- **Package root** `dev.koenv.chaptervault.*`

#### Technical details

- Kotlin 2.2.0, Gradle 9.5.1, JVM target 21 (JDK 26 runtime)
- Ktor 3.0.3 (server + client), Koin 4.0.0, Exposed 0.57.0 + SQLite JDBC 3.47.0.0
- kotlinx-coroutines 1.9.0, kotlinx-serialization 1.7.3, Logback 1.5.12

### Planned: kernel internals

- `InMemoryEventBus`: coroutine-based fan-out, typed handler subscriptions
- `DefaultExtensionRegistry`: map-backed registry with capability index
- Bind both in `kernelModule`; server will boot for the first time after this phase

### Planned: system and auth stubs

- `SystemApiImpl`: delegates task queries to `TaskQueue`; reports extension list via registry
- `AuthApiImpl`: in-memory credential store for development; JWT session tokens
- `InMemoryTaskQueue`: coroutine channel-backed queue with `enqueue` / `dequeue` / `cancel`

### Planned: infrastructure repositories

- `SeriesRepository`: full Exposed implementation: `getSeries`, `listSeries`, `searchLibrary`, `addToLibrary`, `removeSeries`, `updateSeries`; `java.time.Instant` / `kotlinx.datetime.Instant` conversion at boundary
- `ChapterRepository`: `getChapter`, `listChapters`; extracted from `SeriesRepository`
- `TaskRepository`: persists task lifecycle; implements `TaskQueue` over the `tasks` table
- `UserRepository`: user lookup and password verification

### Planned: config and connector wiring

- `ConfigLoader`: parse `config/application.yaml` via Ktor's built-in YAML config support (`ktor-server-config-yaml` already a dependency); env-var override layer
- `ConnectorContext`: pass `HttpClient` and `RateLimiter` through to connector implementations
- `MangaDexConnector`: first real connector: search, series metadata, chapter list, page download

### Planned — first runnable release (0.5.0)

- Server boots to `/health` with all Koin bindings satisfied
- Library CRUD via `GET/POST /library/series` and `GET /library/series/{id}/chapters`
- Task creation on `addToLibrary` with `autoDownload = true`
- MangaDex series discovery and chapter download
- OPDS feed from library via `OpdsExtension`

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

- **Unified repository models**: `CachedSeries` renamed to `Series`, `CachedChapter` renamed to `Chapter`; nullable fields naturally encode "not yet fetched" — no explicit partial/complete distinction
- **Unified API DTOs**: `CatalogSeriesDto`, `LibrarySeriesDto`, `CatalogChapterDto`, `LibraryChapterDto` replaced by shared `SeriesDto`, `ChapterDto`, `SeriesDetailResponse` used across all endpoints
- **Always-fresh detail endpoints**: `GET /api/v1/catalog/series/{id}` and `POST /api/v1/library/series/{id}/refresh` always fetch metadata from the source connector
- **Merge semantics on upsert**: `upsert()` and `upsertAllFromSearch()` never downgrade known fields to null — a subsequent search result cannot clear an `author` populated by a full metadata fetch
- **Library series detail shows all chapters**: `GET /api/v1/library/series/{id}` now returns all chapters with `downloadStatus` field instead of only downloaded chapters
- **`ChapterDto` includes download fields**: `downloadStatus`, `downloadedAt`, `filePath`, `fileSize` exposed on every chapter response
- **Task identity decoupled from domain FKs**: `tasks` table stores `target_type VARCHAR(32)` and `target_id VARCHAR(36)` (UUID as string, no foreign key) instead of FK columns pointing at series/chapter rows
- **Connector identity replaces `source_url` unique constraint**: Series and chapters are now keyed on `(connector, external_id)` — `source_url` retains its column but no longer carries a unique index
- **`series_tag` table renamed to `tags`**: Kotlin types renamed to `TagTable` / `TagEntity`
- **`publish_date` column widened**: Changed from `VARCHAR(32)` to `TEXT`
- **Catalog search endpoint restructured**: `POST /api/v1/catalog/lookup` replaced by `GET /api/v1/catalog/search?q=&url=&connector=`; both keyword and URL lookup share a single endpoint with response type `CatalogSearchResponse`
- **Pagination removed from list responses**: `ChapterListResponse`, `LibrarySeriesListResponse`, and `TaskListResponse` no longer include pagination metadata
- **`bulkDownload` concurrency unified**: `maxConcurrency` parameter removed from `bulkDownload`; concurrency is now controlled entirely by rate-limit bucket configuration

### Removed

- **`metadataFetchedAt` field** from `Series` model and repository — field nullability encodes data quality, not an explicit timestamp
- **`CachedSeries` / `CachedChapter` types** — replaced by `Series` / `Chapter`
- **`save()` / `saveFromSearch()` / `saveAllFromSearch()` methods** on `SeriesRepositoryPort` — replaced by `upsert()` / `upsertFromSearch()` / `upsertAllFromSearch()` with merge semantics
- **`CatalogSeriesDto` / `CatalogSeriesDetailResponse` / `CatalogChapterDto`** — replaced by `SeriesDto` / `SeriesDetailResponse` / `ChapterDto`
- **`LibrarySeriesDto` / `LibrarySeriesDetailResponse` / `LibraryChapterDto`** — replaced by shared catalog types
- **`domain/Series.kt` / `domain/Chapter.kt`** lightweight url-only domain types — unused, removed to eliminate ambiguity
- **`DownloadTaskRepositoryPort` / `DownloadTaskRepository` / `DownloadTaskTable` / `DownloadTaskEntity`** — replaced by `TaskRepositoryPort` / `TaskRepository` / `TaskTable` / `TaskEntity` in the renamed `tasks` table
- **`SeriesTagTable` / `SeriesTagEntity`** — replaced by `TagTable` / `TagEntity`
- **`seriesId` field on `TaskStatusResponse`** — replaced by `targetType` + `targetId`

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

\[Unreleased\]: https://github.com/DevKoenv/ChapterVault/compare/v0.4.1...HEAD
\[0.4.1\]: https://github.com/DevKoenv/ChapterVault/compare/v0.3.0...v0.4.1
\[0.3.0\]: https://github.com/DevKoenv/ChapterVault/compare/v0.2.0...v0.3.0
\[0.2.0\]: https://github.com/DevKoenv/ChapterVault/compare/v0.1.0...v0.2.0
\[0.1.0\]: https://github.com/DevKoenv/ChapterVault/releases/tag/v0.1.0
