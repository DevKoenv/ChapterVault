# ChapterVault

[![Build](https://github.com/DevKoenv/ChapterVault/actions/workflows/build.yml/badge.svg)](https://github.com/DevKoenv/ChapterVault/actions/workflows/build.yml)

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
- **SQLite:** embedded, no external database required

---

## Quick start

**Requirements:** Docker and Docker Compose.

```bash
git clone https://github.com/DevKoenv/ChapterVault.git
cd ChapterVault
docker compose up -d
```

The server starts on port `8080`. On first boot a default admin account is created and a `data/config.yaml` file is auto-generated with commented defaults.

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
| `CHAPTERVAULT_DATA_DIR` | `data` | Root directory for the database, library files, and config |
| `JAVA_OPTS` | _(empty)_ | Extra JVM flags, e.g. `-Xmx512m` |

### Configuration

On first boot, `data/config.yaml` is auto-generated with all available options and their defaults. The docker-compose volume `./data:/app/data` persists it across container restarts. Edit the file and restart the container to apply changes.

Key settings:

```yaml
storage:
  defaultFormat: CBZ   # CBZ (default) or FOLDER

refresh:
  intervalHours: 24    # 0 to disable automatic library refresh

auth:
  rateLimiting:
    enabled: true
    # trustedProxies:
    #   - "172.18.0.1"  # set this if running behind a reverse proxy
```

---

## API overview

All endpoints except `/health`, `/auth/login`, and `/auth/register` require a Bearer token:

```
Authorization: Bearer <token>
```

### Auth

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/health` | Server health check |
| `POST` | `/auth/register` | Create a user account |
| `POST` | `/auth/login` | Authenticate, receive session token |
| `POST` | `/auth/logout` | Invalidate session token |

### Library

| Method | Path | Access | Description |
|--------|------|--------|-------------|
| `GET` | `/library/series` | Any user | List all series (paginated) |
| `GET` | `/library/series/search` | Any user | Search series by title |
| `GET` | `/library/series/{id}` | Any user | Get a series by ID |
| `GET` | `/library/series/{id}/cover` | Any user | Get series cover image |
| `GET` | `/library/series/{id}/chapters` | Any user | List chapters for a series |
| `POST` | `/library/series` | ADMIN | Add a series to the library |
| `PATCH` | `/library/series/{id}` | ADMIN | Update series settings |
| `DELETE` | `/library/series/{id}` | ADMIN | Remove a series |
| `POST` | `/library/series/{id}/download` | ADMIN | Enqueue download for all chapters |
| `POST` | `/library/series/{id}/refresh` | ADMIN | Refresh chapter list from source |
| `POST` | `/library/chapters/{id}/download` | ADMIN | Enqueue download for one chapter |
| `POST` | `/library/chapters/{id}/redownload` | ADMIN | Re-download an existing chapter |
| `DELETE` | `/library/chapters/{id}` | ADMIN | Delete a downloaded chapter |
| `GET` | `/library/chapters/{id}/pages/{index}` | Any user | Stream a chapter page |

### Progress and reading status

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/library/series/{id}/progress` | Get read progress for a series |
| `POST` | `/library/chapters/{id}/read` | Mark a chapter as read |
| `DELETE` | `/library/chapters/{id}/read` | Mark a chapter as unread |
| `PUT` | `/library/series/{id}/status` | Set reading status for a series |
| `DELETE` | `/library/series/{id}/status` | Clear reading status |

### Bookmarks

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/library/series/{id}/bookmarks` | List bookmarks for a series |
| `POST` | `/library/chapters/{id}/bookmarks` | Create a bookmark |
| `DELETE` | `/library/bookmarks/{id}` | Delete a bookmark |

### Connectors

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/connectors` | List registered connectors |
| `GET` | `/connectors/{id}/search` | Search for series on a connector |
| `GET` | `/connectors/{id}/series/{externalId}` | Get series metadata from a connector |
| `GET` | `/connectors/{id}/series/{externalId}/chapters` | List chapters from a connector |

### Tasks

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/tasks` | List background tasks |
| `GET` | `/tasks/{id}` | Get a task by ID |
| `POST` | `/tasks/{id}/cancel` | Cancel a task |

### Events

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/events` | Server-sent events stream (task updates, new chapters) |

### Notifications

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/notifications` | List notification targets |
| `POST` | `/notifications` | Add a notification target |
| `PATCH` | `/notifications/{id}` | Update a notification target |
| `DELETE` | `/notifications/{id}` | Remove a notification target |
| `POST` | `/notifications/{id}/test` | Send a test notification |

Supported notification types: **ntfy**, **Gotify**, **Discord** (webhook), **generic webhook**.

---

## OPDS

The OPDS v1.2 feed is available at `/opds/v1` and uses HTTP Basic Auth (same credentials as the REST API). Add it to any OPDS-compatible reading app using:

```
http://<your-server>:8080/opds/v1
```

Tested with: Panels (iOS).

---

## Adding a series

1. Find the series on a connector: `GET /connectors/mangadex/search?q=<title>`
2. Note the `externalId` from the results
3. Add it to your library:

```bash
curl -X POST http://localhost:8080/library/series \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"connectorId":"mangadex","externalId":"<id>","language":"en","autoDownload":true}'
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

`:extensions` has no path to `:infrastructure` — connectors cannot touch the database directly.

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

Tests use a temporary SQLite file — no external dependencies needed.

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for the full development guide, code style, and pull request process.

## License

[MIT](LICENSE)
