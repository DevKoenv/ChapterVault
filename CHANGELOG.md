# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.4.0] - TBD

### Changed

- **Unified repository models**: `CachedSeries` renamed to `Series`, `CachedChapter` renamed to `Chapter`; nullable fields naturally encode "not yet fetched" — no explicit partial/complete distinction
- **Unified API DTOs**: `CatalogSeriesDto`, `LibrarySeriesDto`, `CatalogChapterDto`, `LibraryChapterDto` replaced by shared `SeriesDto`, `ChapterDto`, `SeriesDetailResponse` used across all endpoints
- **Always-fresh detail endpoints**: `GET /api/v1/catalog/series/{id}`, `POST /api/v1/catalog/lookup` (URL), and `POST /api/v1/catalog/series/{id}/refresh` always fetch metadata from the source connector — no conditional based on `metadataFetchedAt`
- **Merge semantics on upsert**: `upsert()` and `upsertAllFromSearch()` never downgrade known fields to null — a subsequent search result cannot clear an `author` populated by a full metadata fetch
- **Library series detail shows all chapters**: `GET /api/v1/library/series/{id}` now returns all chapters with `downloadStatus` field instead of only downloaded chapters
- **`ChapterDto` includes download fields**: `downloadStatus`, `downloadedAt`, `filePath`, `fileSize` exposed on every chapter response

### Removed

- **`metadataFetchedAt` field** from `Series` model and repository — field nullability encodes data quality, not an explicit timestamp
- **`CachedSeries` / `CachedChapter` types** — replaced by `Series` / `Chapter`
- **`save()` / `saveFromSearch()` / `saveAllFromSearch()` methods** on `SeriesRepositoryPort` — replaced by `upsert()` / `upsertFromSearch()` / `upsertAllFromSearch()` with merge semantics
- **`CatalogSeriesDto` / `CatalogSeriesDetailResponse` / `CatalogChapterDto`** — replaced by `SeriesDto` / `SeriesDetailResponse` / `ChapterDto`
- **`LibrarySeriesDto` / `LibrarySeriesDetailResponse` / `LibraryChapterDto`** — replaced by shared catalog types
- **`domain/Series.kt` / `domain/Chapter.kt`** lightweight url-only domain types — unused, removed to eliminate ambiguity

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

| Version | Date       | Highlights                                                    |
|---------|------------|---------------------------------------------------------------|
| 0.4.0   | TBD | Entity unification, unified DTOs, merge semantics             |
| 0.3.0   | 2026-02-18 | Domain-aware rate limiting, adaptive backoff, YAML overrides  |
| 0.2.0   | 2026-02-05 | Library management, caching, stable IDs                       |
| 0.1.0   | 2026-01-31 | Initial public release                                        |

[Unreleased]: https://github.com/DevKoenv/ChapterVault/compare/v0.4.0...HEAD
[0.4.0]: https://github.com/DevKoenv/ChapterVault/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/DevKoenv/ChapterVault/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/DevKoenv/ChapterVault/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/DevKoenv/ChapterVault/releases/tag/v0.1.0
