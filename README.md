# ChapterVault

Self-hosted manga library server. Tracks series, automatically downloads new chapters, and serves your collection over a JSON API and OPDS feed.

> **Status:** Active development - v1 not yet released.

---

## Features

- **Multi-user:** each user has their own read progress and bookmarks; the library is shared
- **Automatic downloads:** a background worker polls connected sources for new chapters and downloads them
- **MangaDex connector:** search and download from MangaDex out of the box
- **CBZ and folder output:** download chapters as `.cbz` archives or plain image folders
- **OPDS v1.2 feed:** connect any OPDS-capable reading app (Panels, Chunky, Moon+ Reader, etc.)
- **REST JSON API:** headless; bring your own frontend
- **SQLite by default:** swap to PostgreSQL via a one-line config change

---

## Quick start

**Requirements:** Docker and Docker Compose.

```bash
git clone https://github.com/DevKoenv/ChapterVault.git
cd ChapterVault
docker compose up -d
```

The server starts on port `8080`. On first boot a default admin account is created using the environment variables below.

```bash
curl http://localhost:8080/health
# {"status":"ok"}

curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"changeme"}'
# {"token":"...","username":"admin","roles":["ADMIN"]}
```

### Environment variables

| Variable | Default | Description |
|----------|---------|-------------|
| `CHAPTERVAULT_ADMIN_USER` | `admin` | Username for the bootstrap admin account |
| `CHAPTERVAULT_ADMIN_PASS` | `changeme` | Password for the bootstrap admin account |
| `JAVA_OPTS` | _(empty)_ | Extra JVM flags, e.g. `-Xmx512m` |

### Configuration

Mount a `config/application.yaml` file to customise the server:

```yaml
server:
  port: 8080
  host: "0.0.0.0"

database:
  driver: "org.sqlite.JDBC"
  url: "jdbc:sqlite:data/chaptervault.db"

storage:
  basePath: "downloads"
  defaultFormat: "CBZ"   # CBZ or FOLDER

log:
  level: "INFO"
```

To use PostgreSQL, swap `driver` and `url`:

```yaml
database:
  driver: "org.postgresql.Driver"
  url: "jdbc:postgresql://localhost:5432/chaptervault"
```

---

## API overview

All endpoints (except `/health`, `/auth/login`, `/auth/register`) require a Bearer token:

```
Authorization: Bearer <token>
```

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/health` | Server health check |
| `POST` | `/auth/register` | Create a user account |
| `POST` | `/auth/login` | Authenticate, receive session token |
| `POST` | `/auth/logout` | Invalidate session token |
| `GET` | `/library/series` | List all series (paginated) |
| `POST` | `/library/series` | Add a series to the library _(ADMIN)_ |
| `GET` | `/library/series/{id}` | Get a series by ID |
| `DELETE` | `/library/series/{id}` | Remove a series _(ADMIN)_ |
| `PATCH` | `/library/series/{id}` | Update series settings _(ADMIN)_ |
| `GET` | `/library/series/{id}/chapters` | List chapters for a series |
| `POST` | `/library/series/{id}/chapters/{chapterId}/read` | Mark chapter read |
| `POST` | `/library/series/{id}/chapters/{chapterId}/unread` | Mark chapter unread |
| `GET` | `/library/series/{id}/progress` | Get read progress for a series |
| `GET` | `/library/series/{id}/bookmarks` | List bookmarks for a series |
| `POST` | `/library/series/{id}/chapters/{chapterId}/bookmarks` | Create a bookmark |
| `DELETE` | `/library/series/{id}/bookmarks/{bookmarkId}` | Delete a bookmark |
| `GET` | `/tasks` | List background tasks |
| `POST` | `/tasks/{id}/cancel` | Cancel a task |
| `GET` | `/opds` | OPDS navigation feed _(Basic Auth)_ |

---

## OPDS

The OPDS feed is available at `/opds` and uses HTTP Basic Auth with the same credentials as the REST API. Add it to any OPDS-compatible reading app using:

```
http://<your-server>:8080/opds
```

---

## Architecture

Six Gradle modules with a strictly enforced one-way dependency graph:

```
:apps:server    ->  :kernel, :extensions, :infrastructure, :interfaces
:interfaces     ->  :kernel, :shared
:infrastructure ->  :kernel, :shared
:extensions     ->  :kernel, :shared
:kernel         ->  :shared
:shared         ->  (nothing)
```

`:extensions` has no path to `:infrastructure` - connectors cannot touch the database directly.

See [`CONTRIBUTING.md`](CONTRIBUTING.md) for a full breakdown of each module's responsibilities.

---

## Development

**Requirements:** JDK 21+, Git.

```bash
git clone https://github.com/DevKoenv/ChapterVault.git
cd ChapterVault

# Build and run all tests
./gradlew build

# Run the server
./gradlew :apps:server:run

# Verify extension isolation (must produce no output)
./gradlew :extensions:dependencies --configuration runtimeClasspath | grep infrastructure
```

Tests use an in-process SQLite database - no external dependencies needed.

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for the full development guide, code style, and pull request process.

## License

[MIT](LICENSE)
