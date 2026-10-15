# Operations guide

How to install, run, upgrade, back up and remove a ChapterVault server. For what the server protects against and what it
does not, read [security.md](security.md) as well.

## Install

### Docker (recommended)

```bash
curl -o docker-compose.yml https://raw.githubusercontent.com/DevKoenv/ChapterVault/master/docker-compose.yml
docker compose up -d
docker compose logs chaptervault      # shows the generated administrator password on the first start
```

The compose file:

- pins an image version (`ghcr.io/devkoenv/chaptervault:<version>`); upgrade by changing the tag
- publishes the port on `127.0.0.1` only; see [reverse proxy and TLS](#reverse-proxy-and-tls) to reach it from elsewhere
- stores everything in the named volume `chaptervault-data` mounted at `/app/data`
- runs as an unprivileged user (uid 10001) with a read-only root file system, no extra capabilities and a 30 second
  stop grace period
- has a health check on `/health`; `docker compose ps` shows `healthy` once the server is up

To keep the data in a folder next to the compose file instead of a named volume, use `./data:/app/data` and make it
writable for the container user first (`mkdir data && chown 10001:10001 data` on Linux).

### Plain jar

JDK 21 or newer is required.

```bash
./gradlew :apps:server:shadowJar
CHAPTERVAULT_DATA_DIR=/srv/chaptervault java -jar apps/server/build/libs/server-all.jar
```

Each GitHub release has the same jar attached as `server-all.jar`. Windows and macOS are best effort;
Linux in Docker is what is tested.

## First start

On an empty install the server creates one administrator. If `CHAPTERVAULT_ADMIN_PASS` is not set it generates a
password and prints it **once** in the log. It is not stored anywhere in readable form; if you lose it, use the
`reset-password` file (see [Accounts and lost passwords](#accounts-and-lost-passwords)).

The administrator is only created when there are no users at all. Deleting the account later does not bring it back.
On an empty install, a `CHAPTERVAULT_ADMIN_PASS` shorter than 8 characters stops the server from
starting, with a message saying why. Once users exist the variable is not used any more.

## Accounts and lost passwords

Registration is off by default; an administrator creates accounts with `POST /api/v1/users` (without a password the
server generates one and returns it once). A user who forgot their password gets a new one from an administrator with
`POST /api/v1/users/{id}/password`.

If the administrator password itself is lost and nobody else can log in as an administrator, put the username in a
file called `reset-password` in the data directory and restart:

```bash
docker compose exec chaptervault sh -c 'echo admin > /app/data/reset-password'
docker compose restart chaptervault
docker compose logs chaptervault      # the new password, shown once
```

Without Docker: `echo admin > <data dir>/reset-password` and restart the service. The server removes the file before it
changes anything, sets a generated password, prints it once in the log and signs that user out everywhere. If the file
names an unknown user, or cannot be removed, nothing is reset and the log says why. Anyone who can write to the data
directory can do this, which is no more than they could already do with the database file.

## Data layout

Everything lives under `CHAPTERVAULT_DATA_DIR` (`/app/data` in the image, `./data` otherwise):

| Path | Content |
|---|---|
| `config.yaml` | The only configuration file the server reads (written with defaults on first start) |
| `db/chaptervault.db` (+ `-wal`, `-shm`) | The SQLite database: series, chapters, users, progress, tasks |
| `db/chaptervault.db.pre-V<N>` | A copy made before the database was upgraded to schema version N |
| `library/<series id>/` | Downloaded chapters, `<chapter id>.cbz` or a folder per chapter |
| `library/.trash/` | Series folders that had no database entry at startup (see below) |
| `thumbnails/<series id>.jpg` | Cached covers |
| `extensions/*.jar` | Extension JARs, loaded at startup |

**Library folders with no database entry** (for example after restoring an older database) are moved to
`library/.trash/` at startup, never deleted. Only folders named like a series id are considered; any other file or folder
in the library path is left alone. Delete `.trash` yourself when you are sure. Pointing `CHAPTERVAULT_LIBRARY_PATH` at a
folder that also holds other files is safe for this reason, but not recommended.

Interrupted downloads leave `*.part` files, which are removed at the next start. A chapter is only visible once it is
complete.

## Reverse proxy and TLS

ChapterVault speaks plain HTTP. Anything that is not a private network you trust should reach it through a proxy that
terminates TLS, because bearer tokens, OPDS Basic-auth passwords and login passwords would otherwise cross the network in
clear text.

Caddy (certificates are automatic):

```
manga.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

nginx:

```nginx
server {
    listen 443 ssl;
    server_name manga.example.com;
    # ssl_certificate / ssl_certificate_key ...

    client_max_body_size 10m;            # the server rejects larger request bodies anyway

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location = /api/v1/events {          # server-sent events must not be buffered
        proxy_pass http://127.0.0.1:8080;
        proxy_buffering off;
        proxy_read_timeout 1h;
    }
}
```

Then tell the server who the proxy is, otherwise every client looks like the proxy and the rate limiter is blind:

```yaml
# config.yaml
auth:
  rateLimiting:
    trustedProxies: ["127.0.0.1/32"]     # the proxy's address as the server sees it
    trustedNetworks: []                   # or only what should really be exempt
```

If the proxy runs in another container, use that container's network address (for the default compose network, the
gateway such as `172.18.0.1/32` or the proxy's service address). Check the startup log: the warning about
`trustedProxies` should be gone.

Also set the address readers use, so the OPDS search description points at `https://` and not at the plain HTTP the
proxy forwards:

```yaml
# config.yaml
server:
  publicUrl: "https://manga.example.com"   # scheme and host only, no path
```

If a separately hosted web UI on another origin calls the API from a browser, list its origin in
`server.corsOrigins`. With nothing listed, browsers are not allowed to call the API from other sites (apps, OPDS readers
and a UI served from the same origin are unaffected).

## Upgrading

- Any 1.x to a later 1.x is supported. Stop the server, start the new version; the database is migrated automatically.
- Before changing an existing database the server writes `db/chaptervault.db.pre-V<N>`. If an upgrade goes wrong, stop the
  server, restore that file as `chaptervault.db` (delete the `-wal` and `-shm` files next to it) and start the previous
  version. Migrations run in one transaction, so a failed migration leaves the database as it was.
- **Downgrades are not supported.** Going back means restoring the `.pre-V<N>` copy.
- Migrations are applied with Flyway; its bookkeeping lives in the table `flyway_schema_history`. An edited, already applied
  migration stops the server at startup with a Flyway validation error.
- Pre-release databases (from before 1.0.0 shipped) are not upgraded: the schema was reset for 1.0.0. A database from the
  latest pre-release build (only `V1` in its old `schema_version` table) is adopted as it is; an older one stops the server with
  a message pointing here. In that case export your reading progress with `GET /api/v1/backup/export`, start with an empty
  data directory, re-add your series and import the progress.
- The server asks GitHub once a day for the latest release and logs a message when yours is older (see
  `GET /api/v1/system/updates`). Set `updates.checkEnabled: false` (or `CHAPTERVAULT_UPDATE_CHECK=false`) to stop that.

## Backup and restore

What each thing covers:

| | Includes | Does not include |
|---|---|---|
| `GET /api/v1/backup/export` | Your reading progress and reading statuses, keyed by source and series id | Library files, series metadata, other users, settings |
| A copy of the data directory | Everything | |

For a full backup, stop the server and copy the data directory (or, while it runs, copy the library and
thumbnails, and take the database with `sqlite3 db/chaptervault.db ".backup 'backup.db'"`; copying the `.db` file of a
running server can produce a torn copy because of the write-ahead log).

To restore: stop the server, put the directory back, start. To move to a new machine, copy the data directory and keep the
same version or newer.

Restoring only reading progress onto a fresh install: re-add the series, then `POST /api/v1/backup/import` with the
exported JSON. Entries for series that are not in the library, or that carry an unknown status or a negative page, are
skipped and counted in the answer.

## Stopping

`docker compose stop` (or SIGTERM) shuts the server down; tasks that were running are put back in the queue at the next
start. Do not `kill -9` it while it writes if you can avoid it, although the database and downloads are crash-safe
(downloads are written to a temporary file first).

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `Address already in use` at startup | Another program uses the port. Change `server.port` / `CHAPTERVAULT_PORT` |
| Container exits with permission errors on `/app/data` | The data folder is not writable for uid 10001. Use the named volume or `chown 10001:10001` |
| `Server startup aborted due to ... configuration error(s)` | The log lines above it say which path or value is wrong |
| Log line `Unknown setting` / `must be a number` | A typo or wrong type in `config.yaml`; the default is used for that setting |
| `This database was made by a pre-release build` / a Flyway validation error | The database is from an older pre-1.0.0 build or a migration file was edited, see Upgrading |
| `/health` returns 503 | `database` or `executor` is `error` in the body. A busy download does not cause this; check the log |
| Every OPDS request returns 401 | The reader sends no Basic credentials, or the account is not allowed to read. Use the same username and password as the API |
| Login or OPDS suddenly returns 429 | Too many failed attempts from your address; wait for `Retry-After` seconds. Behind a proxy, check `trustedProxies` |
| A notification target is rejected | Its URL must be http(s) and not on the server itself. Allow localhost targets with `notifications.allowLocalTargets` |
| A series folder disappeared from `library/` | It had no database entry; look in `library/.trash/` |
| Nobody can log in as administrator any more | See [Accounts and lost passwords](#accounts-and-lost-passwords) |
| Downloads fail with `MB free on the library disk` | Less free space than `storage.minFreeMb` (default 1024). These are not retried. Free space (`library/.trash/` is never emptied automatically) and run `POST /api/v1/library/series/{id}/download` again; `GET /api/v1/system/storage` shows the numbers |

## Uninstall

`docker compose down -v` removes the container and the named volume (and with it all data). If you used a folder,
delete it. There is nothing outside the data directory and the image.
