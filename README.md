# ChapterVault

[![Build](https://github.com/DevKoenv/ChapterVault/actions/workflows/build.yml/badge.svg)](https://github.com/DevKoenv/ChapterVault/actions/workflows/build.yml)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.0-blue.svg)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/license-Apache%20License%202.0-green)](LICENSE)

A modular, self-hosted manga/comic library server with OPDS support. Download, organize, and serve your comic collection to any OPDS-compatible reader.

## Features

- **Multi-Source Support** - Extensible connector system for different content sources
- **OPDS v1.2 Catalog** - Compatible with Panels, Chunky, Kavita, and other readers
- **REST API** - Full API for browsing, searching, and managing downloads
- **CBZ Output** - Industry-standard comic archive format with ComicInfo.xml metadata
- **Rate Limiting** - Per-connector configurable rate limits to respect source servers
- **Declarative Extraction** - Clean DSL for defining what data to extract from sources
- **Browser Automation** - Playwright integration for JavaScript-heavy sites
- **Multiple Databases** - SQLite (default), H2, or PostgreSQL

## Quick Start

### Prerequisites

- Java 21 or higher
- Gradle 8.x (wrapper included)

### Build & Run

```bash
# Clone the repository
git clone https://github.com/DevKoenv/ChapterVault.git
cd ChapterVault

# Build the project
./gradlew build

# Run the server
./gradlew :app:run
```

The server starts at `http://localhost:8080`.

### Docker

Build and run with Docker:

```bash
# Clone the repository
git clone https://github.com/DevKoenv/ChapterVault.git
cd ChapterVault

# Build and run with docker-compose
docker-compose up -d
```

Or build manually:

```bash
docker build -t chaptervault .
docker run -d \
  -p 8080:8080 \
  -v /path/to/downloads:/downloads \
  -v /path/to/data:/data \
  chaptervault
```

## Configuration

Configuration via environment variables:

| Variable                    | Default                    | Description                                    |
|-----------------------------|----------------------------|------------------------------------------------|
| `PORT`                      | `8080`                     | Server port                                    |
| `HOST`                      | `0.0.0.0`                  | Server bind address                            |
| `CHAPTERVAULT_DATA_PATH`    | `~/ChapterVault/data`      | Database location                              |
| `CHAPTERVAULT_STORAGE_PATH` | `~/ChapterVault/downloads` | Downloaded files                               |
| `CHAPTERVAULT_DB_TYPE`      | `sqlite`                   | Database type: `sqlite`, `h2`, `postgresql`    |
| `CHAPTERVAULT_ENV`          | `production`               | Set to `development` to enable mock connectors |

See [docs/configuration.md](docs/configuration.md) for full configuration options.

> **Note:** In production mode, no connectors are registered by default. Set `CHAPTERVAULT_ENV=development` to enable mock connectors for testing, or implement your own connectors. See [docs/LEGAL_SOURCES.md](docs/LEGAL_SOURCES.md) for information on legal content sources.

## API Usage

### Lookup by URL

```bash
# Fetch series metadata from a direct URL
curl -X POST "http://localhost:8080/api/v1/catalog/lookup" \
  -H "Content-Type: application/json" \
  -d '{"url": "https://example.com/series/my-series"}'
```

### Search for Series

```bash
# Search a specific connector (source required for searches)
curl -X POST "http://localhost:8080/api/v1/catalog/lookup" \
  -H "Content-Type: application/json" \
  -d '{"query": "adventure", "source": "my-connector"}'
```

### Get Series Details

```bash
curl "http://localhost:8080/api/v1/catalog/series/{seriesId}"
```

### List Available Connectors

```bash
curl "http://localhost:8080/api/v1/catalog/connectors"
```

### Download a Series

```bash
# Download by source URL (for series not yet in library)
curl -X POST "http://localhost:8080/api/v1/downloads" \
  -H "Content-Type: application/json" \
  -d '{"sourceUrl": "https://example.com/series/123"}'

# Or download by series ID (for series already in library)
curl -X POST "http://localhost:8080/api/v1/downloads" \
  -H "Content-Type: application/json" \
  -d '{"seriesId": "uuid-here"}'
```

> **Note:** All lookup results are cached and assigned a stable ID. Series not yet in your library will have `inLibrary: false`. Downloading a series automatically adds it to your library.

### Check Download Progress

```bash
curl "http://localhost:8080/api/v1/downloads/{downloadId}"
```

## OPDS Catalog

Access your library with any OPDS-compatible reader:

```
http://localhost:8080/opds
```

**Compatible Readers:**

- [Panels](https://panels.app/) (iOS/macOS)
- [Chunky](http://chunkyreader.com/) (iOS)
- [Librera](https://librera.mobi/) (Android)
- [Moon+ Reader](https://www.moondownload.com/) (Android)
- [Kavita](https://www.kavitareader.com/) (Web)

## Architecture

```
ChapterVault/
├── modules/
│   ├── core/           # Domain models, ports, execution primitives
│   ├── connectors/     # Connector implementations
│   ├── orchestration/  # Task scheduling, rate limiting, execution
│   ├── storage/        # File storage (CBZ writer)
│   ├── database/       # Repository implementations
│   ├── api/            # REST API (Ktor)
│   ├── opds/           # OPDS catalog (Ktor)
│   └── app/            # Application entry point
├── config/             # Configuration files
└── docs/               # Documentation
```

### Key Design Decisions

1. **Declarative Extraction** - Connectors declare WHAT to extract, not HOW:
   ```kotlin
   extractData(url = searchUrl) {
       nestedList("results", ".manga-card") {
           href("url", "a")
           text("title", ".title")
       }
   }
   ```

2. **Port/Adapter Pattern** - Clean interfaces with pluggable implementations

3. **Execution Plans** - Instructions describe operations; executor handles implementation

See [docs/ROADMAP.md](docs/ROADMAP.md) for the full roadmap.

## Creating Connectors

Connectors handle fetching data from sources. Here's a minimal example:

```kotlin
class MyConnector(override val executor: Executor) : Connector {
    override val baseUrls = listOf("manga.example.com")

    override val config = ConnectorConfig(
        name = "MyConnector",
        version = "1.0.0",
        // ... rate limits, features
    )

    override suspend fun searchSeries(query: String): List<SeriesSearchResult> {
        val plan = executionPlan {
            extractData(url = "https://manga.example.com/search?q=$query") {
                nestedList("results", ".manga-item") {
                    href("url", "a")
                    text("title", ".title")
                    src("cover", "img")
                }
            }
        }

        val results = executor.executeAll(plan.instructions, getExecutionContext())
        // Convert extracted data to SeriesSearchResult...
    }

    // Implement other methods...
}
```

See [docs/connectors.md](docs/connectors.md) for the full connector development guide.

## Storage Format

Downloaded chapters are stored as CBZ files:

```
~/ChapterVault/downloads/
└── Series Name/
    ├── Chapter 001 - Title.cbz
    ├── Chapter 002 - Title.cbz
    └── ...
```

Each CBZ contains:

```
chapter.cbz/
├── ComicInfo.xml      # Metadata (ComicRack format)
├── 001.jpg            # Pages
├── 002.jpg
└── ...
```

## Technology Stack

| Component          | Technology                         |
|--------------------|------------------------------------|
| Language           | Kotlin 2.3                         |
| Async              | Kotlin Coroutines                  |
| HTTP Server        | Ktor 3.4                           |
| HTTP Client        | Ktor Client                        |
| Browser Automation | Playwright                         |
| Database           | Exposed ORM (H2/SQLite/PostgreSQL) |
| HTML Parsing       | Jsoup                              |
| Build              | Gradle (Kotlin DSL)                |

## Contributing

We welcome contributions! Please see [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

### Development Setup

```bash
# Clone and build
git clone https://github.com/DevKoenv/ChapterVault.git
cd ChapterVault
./gradlew build

# Run tests
./gradlew test

# Run with hot reload (development)
./gradlew :app:run --continuous
```

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## Acknowledgments

- [Ktor](https://ktor.io/) - Kotlin async web framework
- [Exposed](https://github.com/JetBrains/Exposed) - Kotlin SQL framework
- [Playwright](https://playwright.dev/) - Browser automation
- [Jsoup](https://jsoup.org/) - HTML parsing

## Disclaimer

This software is provided for personal use only. Users are responsible for ensuring they have the right to download and store any content. The developers do not endorse piracy or copyright infringement.
