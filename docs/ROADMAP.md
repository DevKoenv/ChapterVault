# ChapterVault Roadmap

This document outlines features, improvements, and items that need attention for v1.0 and future releases.

---

## Table of Contents

- [Current State Summary](#current-state-summary)
- [v1.0 - Core Functionality](#v10---core-functionality)
- [v1.1 - Enhanced User Experience](#v11---enhanced-user-experience)
- [v2.0 - Advanced Features](#v20---advanced-features)
- [Technical Debt](#technical-debt)
- [Architecture Notes](#architecture-notes)

---

## Current State Summary

### What's Working

| Feature                  | Status   | Notes                                           |
|--------------------------|----------|-------------------------------------------------|
| Multi-connector search   | Complete | Priority-based with rate limiting               |
| Series metadata fetching | Complete | Title, author, description, cover, tags, status |
| Chapter list retrieval   | Complete | Parsed from source pages                        |
| Chapter download to CBZ  | Complete | ComicInfo.xml metadata included                 |
| REST API                 | Complete | Catalog, library, downloads endpoints           |
| OPDS v1.2 catalog        | Complete | Navigation, search, chapter download            |
| Database persistence     | Complete | H2, SQLite, PostgreSQL support                  |
| Download task tracking   | Complete | Progress, status, error handling                |
| Rate limiting            | Complete | Per-connector configurable                      |
| Declarative extraction   | Complete | `extractData`, `bulkDownload` DSL               |

### What's Incomplete

| Feature             | Status          | Notes                              |
|---------------------|-----------------|------------------------------------|
| OPDS page streaming | Stubbed         | Returns 501 Not Implemented        |
| Authentication      | Framework only  | Config structures exist, not wired |
| Proxy support       | Not integrated  | Configuration defined only         |
| Real connectors     | Examples only   | Mock/Sample/Example connectors     |
| Resume downloads    | Not implemented | No continuation on failure         |
| Web UI              | Not implemented | API only                           |

---

## v1.0 - Core Functionality

**Goal:** Fully functional self-hosted manga/comic library server with at least one real connector.

### P0 - Critical (Must Have)

#### 1. Real Connector Implementation

- [ ] Implement at least one connector for a popular source
- [ ] Test with real-world HTML structures
- [ ] Handle edge cases (pagination, lazy loading, cloudflare)
- [ ] Document connector creation process

#### 2. OPDS Page Streaming

- [ ] Implement `/opds/stream/{chapterId}/{pageNumber}` endpoint
- [ ] Extract pages from CBZ files on-demand
- [ ] Support for page image serving with correct MIME types
- [ ] Cache extracted pages for performance

#### 3. Download Reliability

- [ ] Resume interrupted downloads
- [ ] Retry individual failed pages without re-downloading entire chapter
- [ ] Better error reporting per-page
- [ ] Handle rate limit errors gracefully (429 responses)

#### 4. Configuration Validation

- [ ] Validate YAML configuration on startup
- [ ] Clear error messages for misconfiguration
- [ ] Environment variable documentation
- [ ] Example configuration files

### P1 - Important (Should Have)

#### 5. Authentication Support

- [ ] Wire `AuthConfig` to connectors
- [ ] Support username/password login
- [ ] Support API key authentication
- [ ] Cookie-based authentication (browser session)
- [ ] Secure credential storage

#### 6. Error Handling & Logging

- [ ] Structured logging with correlation IDs
- [ ] Download failure notifications
- [ ] API error responses with actionable messages
- [ ] Health check endpoint

#### 7. Storage Improvements

- [ ] Configurable output directory structure
- [ ] Series folder naming templates
- [ ] Chapter file naming templates
- [ ] Cleanup of incomplete downloads

#### 8. Basic Monitoring

- [ ] `/health` endpoint
- [ ] `/metrics` endpoint (download counts, errors)
- [ ] Connector status endpoint

### P2 - Nice to Have

#### 9. Docker Deployment

- [x] Dockerfile with multi-stage build
- [x] docker-compose.yml with volume mounts
- [x] Environment variable configuration
- [ ] ARM64 support
- [ ] Published Docker image (ghcr.io)

#### 10. API Improvements

- [ ] Series cover image proxy endpoint
- [ ] Batch operations (download multiple series)
- [ ] WebSocket for real-time download progress
- [ ] API rate limiting

---

## v1.1 - Enhanced User Experience

**Goal:** Better usability and content management.

### Library Management

#### 11. Metadata Editing

- [ ] Edit series metadata (title, author, tags)
- [ ] Custom series covers
- [ ] Series notes/comments
- [ ] Reading status tracking

#### 12. Organization Features

- [ ] Collections/shelves
- [ ] Tag management
- [ ] Series grouping (by author, status, etc.)
- [ ] Custom sorting options

#### 13. Duplicate Detection

- [ ] Detect same series from different sources
- [ ] Merge series metadata
- [ ] Link chapters across sources
- [ ] Preferred source selection

### Download Management

#### 14. Scheduling

- [ ] Scheduled series refresh
- [ ] Auto-download new chapters
- [ ] Download queue priorities
- [ ] Bandwidth throttling

#### 15. Selective Downloads

- [ ] Download specific chapter ranges
- [ ] Skip already-read chapters
- [ ] Quality preferences (if source provides options)
- [ ] Page limit validation

### Content Discovery

#### 16. Browse Features

- [ ] Browse by genre/tag
- [ ] New releases feed
- [ ] Popular series (per connector)
- [ ] Similar series recommendations

---

## v2.0 - Advanced Features

**Goal:** Full-featured comic library platform.

### Multi-Format Support

#### 17. Output Formats

- [ ] EPUB output (for e-readers)
- [ ] PDF output
- [ ] Raw folder output (no archive)
- [ ] Format conversion between types

#### 18. Input Sources

- [ ] Import existing CBZ/CBR files
- [ ] Import from local folders
- [ ] Metadata from ComicVine/AniList
- [ ] ISBN/barcode scanning

### Web Interface

#### 19. Admin Dashboard

- [ ] Download queue management
- [ ] Connector configuration
- [ ] Storage statistics
- [ ] User management (if multi-user)

#### 20. Reader Interface

- [ ] Web-based comic reader
- [ ] Reading progress sync
- [ ] Bookmarks and annotations
- [ ] Keyboard navigation

### Integration

#### 21. External Services

- [ ] Webhooks for events (download complete, new chapter)
- [ ] Notification services (Discord, Telegram, email)
- [ ] AniList/MyAnimeList tracking sync
- [ ] Komga/Kavita metadata compatibility

#### 22. Backup & Sync

- [ ] Database backup/restore
- [ ] Cloud storage support (S3, GCS)
- [ ] Multi-instance sync
- [ ] Export/import library

### Performance

#### 23. Scaling

- [ ] Redis caching layer
- [ ] Background job queue (separate workers)
- [ ] Horizontal scaling support
- [ ] CDN integration for file serving

---

## Technical Debt

### Code Quality

- [ ] **Unit tests** - Add tests for core modules
- [ ] **Integration tests** - Test connector + orchestrator flow
- [ ] **E2E tests** - Full API test suite
- [ ] **Code coverage** - Target 80%+ coverage

### Refactoring

- [ ] **Gradle plugin warning** - Fix multiple Kotlin plugin loading
- [ ] **Consistent error handling** - Unified exception hierarchy
- [ ] **Logging standardization** - Consistent log levels and messages
- [ ] **Configuration consolidation** - Single source of truth for config

### Documentation

- [ ] **API documentation** - OpenAPI/Swagger spec
- [ ] **Connector development guide** - How to create connectors
- [ ] **Deployment guide** - Production setup instructions
- [ ] **Architecture documentation** - System design overview

### Security

- [ ] **Dependency audit** - Check for vulnerabilities
- [ ] **Input validation** - Sanitize all user inputs
- [ ] **Rate limiting** - Protect API from abuse
- [ ] **Authentication** - Optional API authentication

---

## Architecture Notes

### Current Module Structure

```
modules/
├── core/           # Domain models, ports, execution primitives
├── connectors/     # Connector implementations
├── orchestration/  # Orchestrator, executor, rate limiter
├── storage/        # File storage (CBZ writer)
├── database/       # Repository implementations
├── api/            # REST API routes
├── opds/           # OPDS catalog routes
└── app/            # Main entry point, bootstrapping
```

### Key Design Decisions

1. **Declarative Extraction** - Connectors declare WHAT to extract, not HOW
    - `extractData` with CSS selectors instead of Jsoup parsing
    - `bulkDownload` for concurrent downloads with retry
    - Easier to test, maintain, and reason about

2. **Port/Adapter Pattern** - Interfaces in `core`, implementations in modules
    - `Connector` interface with multiple implementations
    - `Repository` interfaces with database implementations
    - Easy to swap implementations

3. **Execution Plans** - Instructions describe operations, executor runs them
    - Enables local or remote execution
    - Facilitates testing and debugging
    - Rate limiting at executor level

4. **OPDS v1.2** - Standard protocol for comic readers
    - Supported by many apps (Panels, Chunky, etc.)
    - Allows browsing and downloading without web UI
    - Extensible with streaming support

### Connector Development

```kotlin
// Minimal connector implementation
class MyConnector(override val executor: Executor) : Connector {
    override val baseUrls = listOf("manga.example.com")

    override suspend fun searchSeries(query: String) = executionPlan {
        extractData(url = "https://manga.example.com/search?q=$query") {
            nestedList("results", ".manga-item") {
                href("url", "a")
                text("title", ".title")
            }
        }
    }.let { plan ->
        val results = executor.executeAll(plan.instructions, getExecutionContext())
        // Convert to SeriesSearchResult...
    }
}
```

---

## Version History

| Version | Status  | Release Target |
|---------|---------|----------------|
| v0.1.0  | Current | -              |
| v1.0.0  | Planned | TBD            |
| v1.1.0  | Planned | TBD            |
| v2.0.0  | Future  | TBD            |

---

## Contributing

See [CONTRIBUTING.md](./CONTRIBUTING.md) for guidelines on:

- Setting up development environment
- Code style and conventions
- Pull request process
- Connector submission guidelines
