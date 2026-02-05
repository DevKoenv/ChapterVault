# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

| Version | Date       | Highlights                          |
|---------|------------|-------------------------------------|
| 0.2.0   | 2026-02-05 | Library management, caching, stable IDs |
| 0.1.0   | 2026-01-31 | Initial public release              |

[Unreleased]: https://github.com/DevKoenv/ChapterVault/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/DevKoenv/ChapterVault/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/DevKoenv/ChapterVault/releases/tag/v0.1.0
