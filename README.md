# ChapterVault

[![Build](https://github.com/DevKoenv/ChapterVault/actions/workflows/build.yml/badge.svg)](https://github.com/DevKoenv/ChapterVault/actions/workflows/build.yml)

Self-hosted manga library server. Tracks series, automatically downloads new chapters, and serves your collection over a JSON API and OPDS feed.

ChapterVault is built for one person or one household running it on their own hardware. It is safe on a trusted
network by default and safe on the internet **behind a TLS reverse proxy** ([how](docs/operations.md#reverse-proxy-and-tls)).
It is not a multi-tenant or hosted service.

---

## Features

- **Multi-user:** each user has their own read progress and reading status; the library is shared
- **Automatic downloads:** a background worker polls connected sources for new chapters and downloads them
- **MangaDex connector:** search and download from MangaDex out of the box
- **CBZ and folder output:** download chapters as `.cbz` archives or plain image folders
- **OPDS v1.2 feed:** connect any OPDS-capable reading app (Panels, Chunky, Moon+ Reader, etc.)
- **REST JSON API:** headless; bring your own frontend (see [`/swagger`](#swagger-ui))
- **SQLite:** embedded, no external database required; upgrades migrate the database automatically and keep a copy first
- **Editable metadata:** change a series' title, cover, description, author, artist, year, status or genres; your edits survive refreshes from the source until you hand a field back
- **Reading progress backup:** export and import your reading progress and statuses as JSON
- **Update check:** the server logs when a newer release is available (can be switched off)
- **Reading status tracking:** per-user per-series status (`PLAN_TO_READ`, `READING`, `COMPLETED`, `DROPPED`, `ON_HOLD`)
- **Push notifications:** ntfy, Gotify, Discord webhook, and generic webhook targets for new chapter alerts
- **Extension system:** load third-party connectors and metadata enrichers at runtime from the extensions directory (extensions are trusted code, see [security](docs/security.md#extensions))
- **Permission based access:** routes check permissions; roles (`ADMIN`, `USER`, `GUEST`) are bundles of them
- **OPDS facets:** genre, language, and status navigation feeds for compatible OPDS clients
- **OPDS search:** readers that support OpenSearch can search the library by title
- **Sorting:** by title or by date added, in the API and as OPDS sort facets

---

## Quick start

**Requirements:** Docker and Docker Compose.

```bash
curl -o docker-compose.yml https://raw.githubusercontent.com/DevKoenv/ChapterVault/master/docker-compose.yml
docker compose up -d
docker compose logs chaptervault
```

Or clone the repo and use the dev compose file to build locally:

```bash
git clone https://github.com/DevKoenv/ChapterVault.git
cd ChapterVault
docker compose -f docker-compose.dev.yml up -d
```

The server listens on `127.0.0.1:8080` only (see [reverse proxy and TLS](docs/operations.md#reverse-proxy-and-tls) to
publish it). **On the first start it creates the administrator account and prints a generated password once in the
log**, between two lines of `=` characters:

```
 ChapterVault created the first administrator account.
 username: admin
 password: <generated>
```

Log in with it and change it. To choose the password yourself, set `CHAPTERVAULT_ADMIN_PASS` before the first start
(at least 8 characters). Nothing is created again on later starts, even if you delete the account.

```bash
curl http://localhost:8080/health
# {"status":"ok","checks":{"database":"ok","executor":"ok"}}

curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"<the generated password>"}'
# {"token":"...","username":"admin","roles":["ADMIN"]}
```

Running without Docker: `./gradlew :apps:server:shadowJar`, then `java -jar apps/server/build/libs/server-all.jar`
(JDK 21 or newer).

### Environment variables

| Variable | Default | Description |
|----------|---------|-------------|
| `CHAPTERVAULT_ADMIN_USER` | `admin` | Username of the first administrator (only used on an empty install) |
| `CHAPTERVAULT_ADMIN_PASS` | _generated and printed once_ | Password of the first administrator; at least 8 characters |
| `CHAPTERVAULT_DATA_DIR` | `data` | Root directory for the database, library files, thumbnails, extensions and config |
| `CHAPTERVAULT_PORT` / `CHAPTERVAULT_HOST` | `8080` / `0.0.0.0` | Where the server listens |
| `CHAPTERVAULT_AUTH_REGISTRATION_ENABLED` | `false` | Allow anyone to create a USER account via `POST /api/v1/auth/register` |
| `CHAPTERVAULT_CORS_ORIGINS` | _(none)_ | Comma separated browser origins that may call the API from another site |
| `CHAPTERVAULT_PUBLIC_URL` | _(taken from each request)_ | Scheme and host readers use to reach the server, e.g. `https://manga.example.com`; set it behind a reverse proxy (see [OPDS](#opds)) |
| `CHAPTERVAULT_LIBRARY_PATH` / `CHAPTERVAULT_THUMBNAILS_PATH` / `CHAPTERVAULT_DATABASE_URL` | under the data dir | Override where individual parts live |
| `CHAPTERVAULT_REFRESH_HOURS` | `24` | Hours between automatic library refreshes, `0` disables |
| `CHAPTERVAULT_TASK_RETENTION_DAYS` | `30` | Days to keep finished task records, `0` keeps them forever |
| `CHAPTERVAULT_MIN_FREE_MB` | `1024` | Downloads stop while the library disk has less free space than this (MB), `0` turns the check off |
| `CHAPTERVAULT_UPDATE_CHECK` | `true` | `false` stops the server from contacting GitHub |
| `CHAPTERVAULT_NOTIFICATIONS_ALLOW_LOCAL` | `false` | Allow notification targets on this machine (localhost) |
| `CHAPTERVAULT_MANGADEX_CONTENT_RATINGS` | `safe,suggestive` | Comma separated MangaDex content ratings a search returns: `safe`, `suggestive`, `erotica`, `pornographic` |
| `JAVA_OPTS` | `-XX:MaxRAMPercentage=75` (image) | Extra JVM flags, e.g. `-Xmx512m` |

### Configuration

On first boot, `<data dir>/config.yaml` is generated with every option and its default (inside the container:
`/app/data/config.yaml`, persisted by the data volume). **This is the only config file the server reads.** Edit it and
restart. Environment variables override it. A misspelled key or a value of the wrong type is reported in the log at
startup instead of being ignored silently.

```yaml
server:
  # publicUrl: "https://manga.example.com"   # see OPDS; unset = taken from each request

storage:
  defaultFormat: CBZ   # CBZ (default) or FOLDER
  minFreeMb: 1024      # downloads stop below this much free disk space; 0 turns the check off

refresh:
  intervalHours: 24    # 0 to disable automatic library refresh

tasks:
  retentionDays: 30    # finished task records older than this are deleted; 0 to keep them

updates:
  checkEnabled: true   # false: never contact GitHub

connectors:
  mangadex:
    contentRatings: [safe, suggestive]   # also: erotica, pornographic; at least one is required

auth:
  registrationEnabled: false
  rateLimiting:
    enabled: true
    # trustedProxies:
    #   - "172.18.0.1"  # set this if running behind a reverse proxy
```

#### Rate limiting behind a reverse proxy

Login, register and failed Basic-auth (OPDS) attempts are rate limited per client IP. Two settings decide which IP that is:

- `trustedProxies`: addresses of your reverse proxy. Only when the direct connection comes from one of these is the
  `X-Forwarded-For` header used, and the client is the rightmost address in it that is not itself a trusted proxy.
  When the connection comes from anywhere else the header is ignored.
- `trustedNetworks`: clients in these ranges are exempt from rate limiting. The defaults cover loopback and all
  private networks.

With the defaults and no `trustedProxies`, a reverse proxy on the same host or Docker network makes every request look
like it comes from a private address, so rate limiting is effectively off. When running behind a proxy, set
`trustedProxies` to the proxy's address and remove the private ranges from `trustedNetworks` (keep only what should
really be exempt). The server logs a warning at startup when it detects the default combination.

```yaml
auth:
  rateLimiting:
    trustedProxies:
      - "172.18.0.1"
    trustedNetworks:
      - "127.0.0.0/8"
```

After 10 failed attempts in 15 minutes a client gets `429` with `Retry-After`; for OPDS the same applies per username
(five times as many attempts), so one account cannot be guessed from many addresses.

---

## API overview

The REST API lives under **`/api/v1`**. Everything except `/health`, `POST /api/v1/auth/login`,
`POST /api/v1/auth/register` and series covers needs a Bearer token:

```
Authorization: Bearer <token>
```

Errors always look like `{"error": "NOT_FOUND", "message": "..."}`. Missing or invalid token: `401`. Valid token but
not allowed: `403`.

**Permissions.** Each route needs one permission; a role grants a set of them.

| Permission | ADMIN | USER | GUEST | Covers |
|---|:-:|:-:|:-:|---|
| `READ_LIBRARY` | yes | yes | yes | browse, read pages, own progress, own export, OPDS |
| `WRITE_PROGRESS` | yes | yes | | own progress and reading status, backup import |
| `WRITE_LIBRARY` | yes | | | add/edit/remove series and chapters, browse sources |
| `TRIGGER_TASKS` | yes | | | queue downloads and refreshes, cancel tasks |
| `VIEW_TASKS` | yes | | | task list and errors, task events |
| `MANAGE_USERS` | yes | | | users and roles |
| `MANAGE_EXTENSIONS` | yes | | | extensions |
| `MANAGE_NOTIFICATIONS` | yes | | | notification targets |
| `MANAGE_SERVER` | yes | | | update information |

Paths below are relative to `/api/v1` unless they start with `/health` or `/opds`.

### Auth

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/health` | Server health check (no prefix, no auth) |
| `POST` | `/auth/register` | Create a user account (`403` when `registrationEnabled` is false) |
| `POST` | `/auth/login` | Authenticate, receive session token |
| `DELETE` | `/auth/logout` | Invalidate the current session token |
| `GET` | `/auth/me` | Return the current user's id, username, and roles |
| `POST` | `/auth/change-password` | Change your password; all your sessions are revoked, log in again |

Passwords: at least 8 characters, at most 72 bytes. Usernames are compared ignoring case.

### User management (`MANAGE_USERS`)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/users` | List all user accounts |
| `POST` | `/users` | Create an account (also with registration off); without a password one is generated and returned once |
| `POST` | `/users/{id}/password` | Set another user's password, or `{}` to generate one; signs that user out everywhere |
| `DELETE` | `/users/{id}` | Delete a user account (not yourself, not the last administrator) |
| `PATCH` | `/users/{id}/role` | Change a user's role (the last administrator cannot be demoted) |

### Library

| Method | Path | Permission | Description |
|--------|------|-----------|-------------|
| `GET` | `/library/series` | `READ_LIBRARY` | List all series (paginated); `sort=title\|added`, `order=asc\|desc`, filters `readingStatus`, `genre`, `language`, `upstreamStatus` |
| `GET` | `/library/series/search` | `READ_LIBRARY` | Search series by title (`q`); takes the same sort and filters |
| `GET` | `/library/series/{id}` | `READ_LIBRARY` | Get a series by ID |
| `GET` | `/library/series/{id}/cover` | none | Series cover image (public by design) |
| `GET` | `/library/series/{id}/chapters` | `READ_LIBRARY` | List chapters for a series |
| `GET` | `/library/chapters/{id}/pages/{index}` | `READ_LIBRARY` | Stream a chapter page |
| `POST` | `/library/series` | `WRITE_LIBRARY` | Add a series to the library |
| `PATCH` | `/library/series/{id}` | `WRITE_LIBRARY` | Update series settings and metadata |
| `DELETE` | `/library/series/{id}` | `WRITE_LIBRARY` | Remove a series |
| `DELETE` | `/library/chapters/{id}` | `WRITE_LIBRARY` | Delete a downloaded chapter's files |
| `POST` | `/library/series/{id}/download` | `TRIGGER_TASKS` | Enqueue download for all chapters |
| `POST` | `/library/series/{id}/refresh` | `TRIGGER_TASKS` | Refresh chapter list from source |
| `POST` | `/library/chapters/{id}/download` | `TRIGGER_TASKS` | Enqueue download for one chapter |
| `POST` | `/library/chapters/{id}/redownload` | `TRIGGER_TASKS` | Re-download an existing chapter |

`page` is zero-based (a negative value is treated as 0); `size` is clamped to 1..100 (1..200 for chapters).

### Progress and reading status

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/library/series/{id}/progress` | Get read progress for a series |
| `POST` | `/library/chapters/{id}/read` | Mark a chapter as read |
| `DELETE` | `/library/chapters/{id}/read` | Mark a chapter as unread |
| `PATCH` | `/library/series/{id}/status` | Set reading status for a series |
| `DELETE` | `/library/series/{id}/status` | Clear reading status |

### Connectors (`WRITE_LIBRARY`)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/connectors` | List registered connectors |
| `GET` | `/connectors/{id}/search` | Search for series on a connector |
| `GET` | `/connectors/{id}/series/{externalId}` | Get series metadata from a connector |
| `GET` | `/connectors/{id}/series/{externalId}/chapters` | List chapters from a connector |

### Tasks

| Method | Path | Permission | Description |
|--------|------|-----------|-------------|
| `GET` | `/tasks` | `VIEW_TASKS` | List background tasks, newest first; filters `status`, `type`, `targetId` |
| `GET` | `/tasks/{id}` | `VIEW_TASKS` | Get a task by ID |
| `POST` | `/tasks/{id}/cancel` | `TRIGGER_TASKS` | Cancel a task (a running task is stopped) |

Tasks run up to three at a time, at most two per source, with a timeout per task. A failed task is retried up to three
times (after 30 s, 2 min, 10 min); the wait survives a restart. To find what still failed after that, use
`GET /api/v1/tasks?status=FAILED`; to try again, start the work again with `POST /library/chapters/{id}/download`,
`POST /library/series/{id}/download` (every available or failed chapter) or `POST /library/series/{id}/refresh`.

### Events

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/events` | Server-sent events: download status (`READ_LIBRARY`) and task updates (`VIEW_TASKS`) |

### Notifications (`MANAGE_NOTIFICATIONS`)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/notifications` | List notification targets (tokens are never returned, only `hasToken`) |
| `POST` | `/notifications` | Add a notification target |
| `PATCH` | `/notifications/{id}` | Update a notification target |
| `DELETE` | `/notifications/{id}` | Remove a notification target |
| `POST` | `/notifications/{id}/test` | Send a test notification |

Supported notification types: **ntfy**, **Gotify**, **Discord** (webhook), **generic webhook**. Targets must be
`http(s)` URLs; targets on this machine are refused unless `notifications.allowLocalTargets` is on (your LAN is fine).

### Backup

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/backup/export` | Export your reading progress and statuses as JSON |
| `POST` | `/backup/import` | Import a previously exported backup |

This is a per-user export of progress, not a server backup. To back up the server itself, see
[operations](docs/operations.md#backup-and-restore).

### Extensions (`MANAGE_EXTENSIONS`)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/extensions` | List loaded extensions |
| `GET` | `/extensions/{id}` | Get extension details |
| `POST` | `/extensions/{id}/enable` | Enable an extension |
| `POST` | `/extensions/{id}/disable` | Disable an extension |
| `POST` | `/extensions/{id}/reload` | Reload an extension from disk |
| `POST` | `/extensions/{id}/unload` | Unload an extension |

### System (`MANAGE_SERVER`)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/system/updates` | Version, latest release on GitHub, whether an update exists |
| `GET` | `/system/storage` | Library and thumbnail size, free and total disk space, the download minimum |

---

## Swagger UI

Interactive API documentation is available at `/swagger` without authentication. This is intentional for an open-source self-hosted tool — the API itself is protected by bearer tokens; the schema is not sensitive.

---

## OPDS

The OPDS v1.2 feed is available at `/opds/v1` and uses HTTP Basic Auth with the same account as the REST API. Add it
to any OPDS-compatible reading app using:

```
http://<your-server>:8080/opds/v1
```

**Basic auth sends the password with every request.** Use `https://` through a reverse proxy whenever the server is
reachable by anyone but you.

**Search.** Every feed links to an OpenSearch description (`/opds/v1/search.xml`, as OPDS 1.2 section 3 describes);
readers that support it show a search field. Results come from `/opds/v1/search?q=<terms>` and match series titles,
like the REST search. OpenSearch needs an absolute address in that description. Without `server.publicUrl` the server
uses the scheme and host of the request, so **behind a reverse proxy that serves HTTPS, set `server.publicUrl`**
(or `CHAPTERVAULT_PUBLIC_URL`) to the address readers use, e.g. `https://manga.example.com`. Otherwise the search
address says `http://`.

Tested with: Panels (iOS).

---

## Adding a series

1. Find the series on a connector: `GET /api/v1/connectors/mangadex/search?q=<title>`
2. Note the `externalId` from the results
3. Add it to your library:

```bash
curl -X POST http://localhost:8080/api/v1/library/series \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"connectorId":"mangadex","externalId":"<id>","language":"en","autoDownload":true}'
```

---

## Content sources

ChapterVault ships one source, [MangaDex](https://mangadex.org), through its public API. Other sources come only from
extensions you install yourself. ChapterVault does not host or provide any content: you choose what the server fetches,
and **you are responsible for what you download and for following the terms of every source you use** (for MangaDex:
its [API documentation and acceptable usage policy](https://api.mangadex.org/docs/) and
[terms of service](https://mangadex.org/terms)). Chapters are uploaded by scanlation groups; credit goes to them and to
MangaDex. Extensions are third-party code that runs with full server privileges, see
[Security and trust model](docs/security.md#content-sources).

---

## More documentation

- [Operations guide](docs/operations.md): install, reverse proxy and TLS, data layout, upgrades, backup and restore, troubleshooting, uninstall
- [Security and trust model](docs/security.md): who can do what, what is public by design, extensions, hardening checklist
- [Writing an extension](docs/extensions.md): manifest, entry point, the sample extension, what the server enforces
- [Architecture notes](docs/architecture/kernel.md)

---

## Architecture

Six Gradle modules with a one-way dependency graph that the build checks (`./gradlew verifyModuleGraph`, part of `check`):

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

**Requirements:** JDK 21 must be installed (a newer JDK alone is not enough), Git. CI and the Docker image use JDK 21, and the build compiles against the JDK 21
API even on a newer JDK, so a green local build cannot depend on a later API.

```bash
git clone https://github.com/DevKoenv/ChapterVault.git
cd ChapterVault

# Build and run all tests (also ktlint, detekt, the module graph check and the coverage gate)
./gradlew build

# Run the server
./gradlew :apps:server:run
```

Tests use temporary SQLite files — no external dependencies needed.

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for the full development guide, code style, and pull request process.

## License

[MIT](LICENSE)
