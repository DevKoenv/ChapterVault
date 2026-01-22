# ChapterVault - Media Ingestion and Download System

A modular Kotlin-based system for downloading and managing comics, manga, and ebooks with OPDS catalog support.

## Architecture Overview

ChapterVault follows a clean, modular architecture with strict separation of concerns:

### Core Principles

1. **Connectors fetch data directly** - Each connector handles HTTP, browser automation, tokens, and binary data fetching
2. **No downloader module** - Downloading happens inside connectors, no separate fetch-plan abstractions
3. **Orchestration schedules work** - Orchestrator manages tasks, retries, and rate limiting but never fetches data
4. **Clear separation: Browsing vs Downloading** - Browsing fetches metadata only; downloading fetches pages and binary data
5. **Storage receives bytes** - Storage gets already-fetched bytes and writes files (CBZ, PDF, EPUB)

## Module Structure

```
ChapterVault/
├── core/              # Domain models and interfaces
├── connectors/        # Site connectors (handles all fetching logic)
├── orchestration/     # Task scheduling and rate limiting
├── storage/           # File writing and format generation
├── api/               # REST API for browsing and triggering downloads
├── opds/              # OPDS v1.2 catalog for reading comics
└── app/               # Application bootstrap
```

### Module Details

#### Core Module
- **Domain Models**: Series, Chapter, Page, SeriesMetadata, ChapterMetadata
- **Interfaces**: Connector, StorageSink, ConnectorRegistry
- **Rate Limiting**: RateLimitConfig primitives

#### Connectors Module
- Implements Connector interface
- Handles HTTP requests and browser automation (Playwright)
- Manages CSRF/VRF tokens and cookies
- **Methods**:
  - `canHandle(url)` - Check if connector supports a URL
  - `searchSeries(query)` - Search for series (optional)
  - `fetchSeriesMetadata(url)` - Get series details
  - `fetchChapterList(url)` - List chapters (metadata only)
  - `downloadChapter(url, storage)` - Download chapter pages

#### Storage Module
- Implements StorageSink interface
- Receives bytes from connectors
- Writes files to disk
- Builds CBZ archives
- **Methods**:
  - `beginSeries(metadata)` - Start series
  - `beginChapter(metadata)` - Start chapter
  - `writePage(index, bytes, mimeType)` - Write page
  - `endChapter()` - Finalize chapter (creates CBZ)
  - `endSeries()` - Finalize series

#### Orchestration Module
- Task scheduling and execution
- Rate limit enforcement per connector
- Retry logic
- Progress tracking
- **Operations**:
  - `searchSeries(query)` - Search across connectors
  - `fetchSeriesMetadata(url)` - Get series details
  - `fetchChapterList(url)` - Get chapter list
  - `downloadChapter(url)` - Download single chapter
  - `downloadSeries(url)` - Download all chapters
  - `getProgress(taskId)` - Check task status

#### API Module
- REST API built with Ktor
- **Endpoints**:
  - `POST /series/search` - Search for series
  - `GET /series/metadata?url=...` - Get series metadata
  - `GET /series/chapters?url=...` - Get chapter list
  - `POST /download/chapter` - Trigger chapter download
  - `POST /download/series` - Trigger series download
  - `GET /download/progress/{taskId}` - Check download progress
  - `GET /download/progress` - Get all tasks

#### OPDS Module
- OPDS v1.2 catalog implementation
- Serves downloaded content for comic readers
- **Endpoints**:
  - `GET /opds` - Root catalog
  - `GET /opds/series/{id}` - Series chapters
  - `GET /opds/download/{seriesId}/{chapterId}` - Download CBZ

#### App Module
- Application entry point
- Dependency wiring
- Server initialization

## Quick Start

### Build

```bash
./gradlew build
```

### Run

```bash
./gradlew :app:run
```

The server starts on port 8080 (or use `PORT=8081 ./gradlew :app:run`).

### API Examples

**Search for series:**
```bash
curl -X POST http://localhost:8080/series/search \
  -H "Content-Type: application/json" \
  -d '{"query":"adventure"}'
```

**Get series metadata:**
```bash
curl "http://localhost:8080/series/metadata?url=https://mock-comics.example.com/series/test-comic-1"
```

**List chapters:**
```bash
curl "http://localhost:8080/series/chapters?url=https://mock-comics.example.com/series/test-comic-1"
```

**Download a chapter:**
```bash
curl -X POST http://localhost:8080/download/chapter \
  -H "Content-Type: application/json" \
  -d '{"url":"https://mock-comics.example.com/series/test-comic-1/chapter-1"}'
```

**Check download progress:**
```bash
curl "http://localhost:8080/download/progress/{taskId}"
```

### OPDS Catalog

Access the OPDS catalog at:
```
http://localhost:8080/opds
```

Compatible with any OPDS-supporting comic reader (Chunky, Panels, etc.).

## Mock Connector

The system includes a MockConnector for testing that:
- Responds to URLs starting with `https://mock-comics.example.com/`
- Generates mock series with 5 chapters each
- Creates simple PNG images for each page
- Demonstrates the full architecture

## Storage

Downloaded content is stored in:
```
~/ChapterVault/downloads/
  SeriesName/
    Chapter 1 - Title.cbz
    Chapter 2 - Title.cbz
    ...
```

CBZ files are ZIP archives containing numbered images that can be opened with any comic reader.

## Technology Stack

- **Kotlin** - Primary language
- **Coroutines** - Async/await support
- **Ktor** - HTTP server and client
- **Playwright** - Browser automation (for future real connectors)
- **Gradle** - Build system

## Future Enhancements

### Real Connectors
Add connectors for actual comic/manga sites with:
- CSRF/VRF token handling
- Browser automation for JS-heavy sites
- Cookie management
- Custom rate limiting per site

### Storage Formats
- PDF generation
- EPUB for text-based content
- Custom folder structures

### OPDS Extensions
- Page streaming (v1)
- Search within OPDS
- Thumbnail generation

## Design Philosophy

1. **Single Responsibility** - Each module has one clear purpose
2. **Interface Segregation** - Clean contracts between modules
3. **Dependency Inversion** - Depend on abstractions, not implementations
4. **Local-First** - Designed for single-node deployment
5. **Extensible** - Easy to add new connectors and storage formats
6. **Maintainable** - Clear separation makes reasoning about code easy

## License

MIT License - See LICENSE file for details
