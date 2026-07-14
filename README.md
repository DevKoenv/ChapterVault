# ChapterVault

[![Build](https://github.com/DevKoenv/ChapterVault/actions/workflows/build.yml/badge.svg)](https://github.com/DevKoenv/ChapterVault/actions/workflows/build.yml)

Self-hosted manga library server. Tracks series, automatically downloads new chapters, and serves your collection over a JSON API and OPDS feed.

---

## Features

- **Multi-user:** each user has their own read progress and reading status; the library is shared
- **Automatic downloads:** a background worker polls connected sources for new chapters and downloads them
- **MangaDex connector:** search and download from MangaDex out of the box
- **CBZ and folder output:** download chapters as `.cbz` archives or plain image folders
- **OPDS v1.2 feed:** connect any OPDS-capable reading app (Panels, Chunky, Moon+ Reader, etc.)
- **REST JSON API:** headless; bring your own frontend
- **SQLite:** embedded, no external database required
- **Metadata overrides:** override any series field (title, cover, description, status) without losing upstream data
- **Backup / export:** export and import all reading progress, statuses, and overrides as JSON
- **Update checker:** server logs a warning when a newer release is available on GitHub
- **Unread count:** per-series unread chapter count available via reading progress API
- **Reading status tracking:** per-user per-series status (`PLAN_TO_READ`, `READING`, `COMPLETED`, `DROPPED`, `ON_HOLD`)
- **Push notifications:** ntfy, Gotify, Discord webhook, and generic webhook targets for new chapter alerts
- **Extension system:** load third-party connectors and metadata enrichers at runtime from the extensions directory
- **OPDS facets:** genre, language, and status navigation feeds for compatible OPDS clients

---

## Quick start

**Requirements:** Docker and Docker Compose.

```bash
curl -o docker-compose.yml https://raw.githubusercontent.com/DevKoenv/ChapterVault/master/docker-compose.yml
docker compose up -d
```

Or clone the repo and use the dev compose file to build locally:

```bash
git clone https://github.com/DevKoenv/ChapterVault.git
cd ChapterVault
docker compose -f docker-compose.dev.yml up -d
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

### Metadata overrides

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/library/series/{id}/overrides` | List metadata overrides for a series |
| `PUT` | `/library/series/{id}/overrides/{field}` | Set a metadata override |
| `DELETE` | `/library/series/{id}/overrides/{field}` | Remove a metadata override |

Overridable fields: `title`, `coverUrl`, `description`, `status`, `author`, `artist`, `upstreamStatus`.

### Backup

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/backup/export` | Export reading progress, statuses, and overrides as JSON |
| `POST` | `/backup/import` | Import a previously exported backup |

### Extensions

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/extensions` | List loaded extensions |
| `GET` | `/extensions/{id}` | Get extension details |
| `POST` | `/extensions/{id}/enable` | Enable an extension |
| `POST` | `/extensions/{id}/disable` | Disable an extension |
| `POST` | `/extensions/{id}/reload` | Reload an extension from disk |
| `POST` | `/extensions/{id}/unload` | Unload an extension |

### System

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/system/updates` | Check for a newer server version on GitHub |

---

## Swagger UI

Interactive API documentation is available at `/swagger` without authentication. This is intentional for an open-source self-hosted tool — the API itself is protected by bearer tokens; the schema is not sensitive.

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
:apps:server    ->  :kernel, :infrastructure, :interfaces
:interfaces     ->  :kernel, :shared
:infrastructure ->  :kernel, :shared
:sdk            ->  :kernel, :shared
:kernel         ->  :shared
:shared         ->  (nothing)
```

`:sdk` is a thin re-export layer exposing `:kernel` and `:shared` types for extension authors.

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
```

Tests use a temporary SQLite file — no external dependencies needed.

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for the full development guide, code style, and pull request process.

## License

[MIT](LICENSE)
