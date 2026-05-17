# Contributing to ChapterVault

Thanks for your interest in contributing. This document covers how to set up the project, the conventions we follow, and how to submit changes.

## Prerequisites

- JDK 21 or later
- Git

No Docker required for development - tests use an embedded SQLite database.

## Getting started

```bash
git clone https://github.com/DevKoenv/ChapterVault.git
cd ChapterVault
./gradlew build
```

A successful build runs all tests and produces `apps/server/build/libs/server-fat.jar`.

## Project structure

Six Gradle modules with a strictly enforced dependency graph:

| Module | Purpose |
|--------|---------|
| `:shared` | Cross-cutting types: `Result`, `Id`, `ChapterFormat`, pagination |
| `:kernel` | Domain models, API interfaces, extension contracts |
| `:extensions` | Source connectors, OPDS extension, metadata providers |
| `:infrastructure` | Database repositories, file storage, HTTP client |
| `:interfaces` | Ktor routes, DTOs, mappers |
| `:apps:server` | Composition root - Koin wiring, server bootstrap |

The key constraint: **`:extensions` must never depend on `:infrastructure`**. Verify with:

```bash
./gradlew :extensions:dependencies --configuration runtimeClasspath | grep infrastructure
# must produce no output
```

## Running tests

```bash
# All tests
./gradlew test

# Single module
./gradlew :infrastructure:test

# Single test class
./gradlew :infrastructure:test --tests "*.SeriesRepositoryTest"
```

Tests follow strict TDD - every behaviour is covered by a test that was written before the implementation. When fixing a bug, write a failing test first.

### SQLite test pitfalls

- Always use a **temp file** database, not `jdbc:sqlite::memory:`. In-memory SQLite is per-connection and breaks with `Dispatchers.IO`.
- Use `@TestInstance(Lifecycle.PER_CLASS)` with `@BeforeAll` to create the DB once per suite, and `@AfterEach` to drop and recreate tables between tests.
- Avoid expression-body test methods that call `assertIs<T>()`. `assertIs` returns `T`, and JUnit 5 silently skips non-`void` test methods. Always use a block body.

## Code style

- Kotlin official style (enforced by `.editorconfig`)
- No comments explaining *what* code does - names should do that. Comments only for non-obvious *why*.
- No unnecessary abstractions. Three similar lines are better than a premature helper.
- Error handling only at system boundaries (user input, external APIs). Trust internal code.

## Commit messages

Follow the [Conventional Commits](https://www.conventionalcommits.org/) format:

```
<type>(<scope>): <short description>

[optional body]
```

Common types: `feat`, `fix`, `refactor`, `test`, `docs`, `chore`, `build`, `ci`.

Examples:
```
feat(auth): implement UserRepository with bcrypt sessions
fix(library): return 404 when series not found instead of 500
refactor(kernel): rename ChapterStatus to DownloadStatus
```

## Submitting a pull request

1. Fork the repository and create a branch from `master`.
2. Make your changes with tests.
3. Run `./gradlew build` and confirm it passes.
4. Open a pull request against `master`. Fill in the PR template.

For significant changes (new features, architecture changes), open an issue first to discuss the approach before writing code.

## Architecture decisions

Major decisions are recorded in the commit history and in pull request descriptions. If your change affects the module graph, API surface, or data model, document the reasoning in the PR.
