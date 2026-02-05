# Contributing to ChapterVault

Thank you for your interest in contributing to ChapterVault! This document provides guidelines and information for contributors.

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [Getting Started](#getting-started)
- [Development Setup](#development-setup)
- [Making Changes](#making-changes)
- [Submitting Changes](#submitting-changes)
- [Coding Guidelines](#coding-guidelines)
- [Connector Development](#connector-development)
- [Testing](#testing)
- [Documentation](#documentation)

## Code of Conduct

By participating in this project, you agree to maintain a respectful and inclusive environment. Be kind, be constructive, and be patient with others.

## Getting Started

### Prerequisites

- **Java 21** or higher (recommend using [SDKMAN](https://sdkman.io/) or [asdf](https://asdf-vm.com/))
- **Git** for version control
- An IDE with Kotlin support (IntelliJ IDEA recommended)

### Development Setup

1. **Fork the repository** on GitHub

2. **Clone your fork:**
   ```bash
   git clone https://github.com/DevKoenv/ChapterVault.git
   cd ChapterVault
   ```

3. **Build the project:**
   ```bash
   ./gradlew build
   ```

4. **Run tests:**
   ```bash
   ./gradlew test
   ```

5. **Run the application:**
   ```bash
   ./gradlew :app:run
   ```

### IDE Setup

**IntelliJ IDEA:**

1. Open the project folder
2. Wait for Gradle sync to complete
3. Mark `modules/*/src/main/kotlin` as Sources Root
4. Mark `modules/*/src/test/kotlin` as Test Sources Root

## Making Changes

### Branch Naming

Use descriptive branch names:

- `feature/add-xyz-connector` - New features
- `fix/download-retry-logic` - Bug fixes
- `docs/connector-guide` - Documentation
- `refactor/execution-plan` - Code refactoring

### Commit Messages

Follow conventional commits format:

```
type(scope): description

[optional body]

[optional footer]
```

**Types:**

- `feat` - New feature
- `fix` - Bug fix
- `docs` - Documentation only
- `refactor` - Code refactoring
- `test` - Adding tests
- `chore` - Maintenance tasks

**Examples:**

```
feat(connector): add Webtoon connector

fix(download): handle 429 rate limit responses

docs(readme): update installation instructions
```

## Submitting Changes

### Pull Request Process

1. **Create a feature branch** from `master`:
   ```bash
   git checkout -b feature/your-feature
   ```

2. **Make your changes** with clear, focused commits

3. **Ensure tests pass:**
   ```bash
   ./gradlew test
   ```

4. **Ensure the build succeeds:**
   ```bash
   ./gradlew build
   ```

5. **Push to your fork:**
   ```bash
   git push origin feature/your-feature
   ```

6. **Open a Pull Request** against the `master` branch

### Pull Request Guidelines

- Fill out the PR template completely
- Link related issues using `Fixes #123` or `Closes #123`
- Keep PRs focused - one feature or fix per PR
- Add tests for new functionality
- Update documentation if needed
- Ensure CI passes before requesting review

## Coding Guidelines

### Kotlin Style

Follow the [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html):

- Use 4 spaces for indentation
- Maximum line length: 120 characters
- Use trailing commas in multi-line declarations
- Prefer `val` over `var`
- Use explicit types for public APIs

### Project Conventions

**Packages:**

```
dev.koenv.chaptervault.<module>.<subpackage>
```

**File naming:**

- Classes: `PascalCase.kt`
- Interfaces: `PascalCase.kt` (no `I` prefix)
- Extensions: `TypeExtensions.kt`

**Coroutines:**

- Use `suspend` functions for async operations
- Avoid `runBlocking` except in main/tests
- Use `coroutineScope` for parallel operations

### Error Handling

- Use `Result<T>` for operations that can fail
- Throw exceptions for programming errors only
- Log errors with context (connector name, URL, etc.)
- Provide actionable error messages

## Connector Development

### Creating a New Connector

1. Create a new file in `modules/connectors/src/main/kotlin/dev/koenv/chaptervault/connectors/impl/`

2. Implement the `Connector` interface:

```kotlin
class MyConnector(
    override val executor: Executor,
    private val connectorConfig: ConnectorSpecificConfig? = null
) : Connector {

    override val config = ConnectorConfig(
        id = "my-connector",
        name = "My Connector",
        version = "1.0.0",
        rateLimitConfig = RateLimitConfig(
            minDelay = 500.milliseconds,
            maxConcurrent = 2,
            maxRequestsPerWindow = 60,
            windowDuration = 60.seconds
        ),
        features = ConnectorFeatures(
            supportsSearch = true,
            requiresAuth = false,
            supportsBatchDownload = true,
            supportsPageCount = true,
            maxConcurrentDownloads = 3
        ),
        priority = 10
    )

    override val baseUrls = listOf("example.com", "www.example.com")

    override suspend fun searchSeries(query: String): List<SeriesSearchResult> {
        // Use extractData for declarative extraction
        val plan = executionPlan {
            extractData(url = "https://example.com/search?q=$query") {
                nestedList("results", ".search-item") {
                    href("url", "a")
                    text("title", ".title")
                    src("cover", "img")
                }
            }
        }
        // ... execute and parse results
    }

    // Implement other methods...
}
```

3. Register in `ConnectorRegistry` (done automatically via ServiceLoader or manual registration)

### Connector Guidelines

- **Use declarative extraction** (`extractData`) over manual Jsoup parsing
- **Use `bulkDownload`** for downloading multiple pages
- **Respect rate limits** - configure appropriate delays
- **Handle errors gracefully** - don't crash on missing elements
- **Test with real data** - verify selectors work with actual pages

### Testing Connectors

Create tests in `modules/connectors/src/test/kotlin/`:

```kotlin
class MyConnectorTest {
    @Test
    fun `searchSeries returns results for valid query`() {
        // Test with mock executor
    }
}
```

## Testing

### Running Tests

```bash
# All tests
./gradlew test

# Specific module
./gradlew :core:test

# With coverage
./gradlew test jacocoTestReport
```

### Writing Tests

- Use descriptive test names with backticks
- Follow Arrange-Act-Assert pattern
- Mock external dependencies
- Test edge cases and error conditions

```kotlin
class DownloadServiceTest {
    @Test
    fun `downloadChapter retries on transient failure`() {
        // Arrange
        val mockExecutor = mockk<Executor>()
        // ...

        // Act
        val result = service.downloadChapter(url)

        // Assert
        assertEquals(DownloadStatus.COMPLETED, result.status)
    }
}
```

## Documentation

### What to Document

- Public APIs (KDoc comments)
- Complex algorithms
- Configuration options
- New features (in docs/ folder)

### KDoc Style

```kotlin
/**
 * Downloads a chapter and stores it in the specified storage sink.
 *
 * This method fetches all pages for the chapter and writes them
 * to storage. Progress is tracked via the [ProgressListener].
 *
 * @param chapterUrl The URL of the chapter to download
 * @param storage The storage sink to write pages to
 * @throws DownloadException if the download fails after all retries
 */
suspend fun downloadChapter(chapterUrl: String, storage: StorageSink)
```

## Questions?

- Open a [Discussion](https://github.com/DevKoenv/ChapterVault/discussions) for questions
- Open an [Issue](https://github.com/DevKoenv/ChapterVault/issues) for bugs or feature requests
- Check existing issues before creating new ones

Thank you for contributing!
