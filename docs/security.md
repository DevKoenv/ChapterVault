# Security and trust model

ChapterVault is meant for one person or one household. This page says what it protects, what it deliberately does not,
and what you have to do yourself. To report a vulnerability, see [SECURITY.md](../SECURITY.md).

## Who can reach the server

| Setup | Assessment |
|---|---|
| Trusted home network only | Fine with the defaults. Anyone on the network can read the unauthenticated parts below |
| Internet, behind a TLS reverse proxy | Supported. Follow the [proxy setup](operations.md#reverse-proxy-and-tls) including `trustedProxies` |
| Internet, plain HTTP | Not safe: passwords, tokens and OPDS Basic-auth credentials are sent in clear text |

## What needs a login, and what does not

Public on purpose (no login):

- `GET /health`: shows only `ok`/`degraded` for the database and the task executor
- `GET /swagger` and the OpenAPI document: the schema is not sensitive
- `GET /api/v1/library/series/{id}/cover`: cover thumbnails, so image tags in a UI and OPDS readers can load them
  without a token. Series ids are random UUIDs, but they are not secrets
- `POST /api/v1/auth/login` and, when `registrationEnabled` is on, `POST /api/v1/auth/register`: both rate limited

Everything else needs a bearer token (REST) or Basic credentials (OPDS).

## Accounts and permissions

- The first administrator is created only on an empty install. Its password is random and shown once, or taken from
  `CHAPTERVAULT_ADMIN_PASS`, which must be at least 8 characters.
- Registration is **off** by default. Turn it on only if strangers should be able to create accounts; otherwise an
  administrator creates them (`POST /users`).
- An administrator can set another user's password (`POST /users/{id}/password`), which signs that user out. A lost
  administrator password is recovered with a `reset-password` file in the data directory
  ([how](operations.md#accounts-and-lost-passwords)); write access to the data directory already means full control, so
  this adds no new way in.
- Passwords are stored as BCrypt hashes. New passwords must be 8 to 72 bytes (BCrypt ignores anything after 72).
- Bearer tokens are random and only a SHA-256 hash is stored, so a copy of the database or a backup does not contain
  working tokens. Sessions last 30 days from the last use, a user keeps at most 20, and they are all revoked when the
  password changes, the account is deleted or the role changes.
- Usernames are compared ignoring case and may not contain control characters. A login for an unknown name takes as
  long as one with a wrong password.
- The last administrator cannot be deleted or demoted.
- Routes check **permissions**; a role is a bundle of them. The table is in the [README](../README.md#api-overview) and
  in the OpenAPI description. A `GUEST` can read the library and OPDS but cannot change anything, including its own
  progress. Only `ADMIN` can add or remove series, trigger tasks, see tasks and their error texts, manage users,
  extensions and notification targets.

### Brute force

- Login and register: 10 and 5 attempts per window per client address (configurable). Failed OPDS (Basic) attempts count
  against the login budget per client address and, at five times the budget, per username, so spreading guesses over many
  addresses does not help. Blocked requests are refused with `429` before the password is checked.
- Trusted networks (loopback and private ranges by default) are **exempt** from all of this. If you run behind a proxy,
  narrow `trustedNetworks` and set `trustedProxies`.

## Notification targets

An administrator can make the server send HTTP requests to a URL. To keep that from being used against the machine
itself, targets must be `http(s)` and may not resolve to this machine, a link-local address (which includes cloud
metadata services) or the unspecified address. Addresses on your LAN are allowed, since that is where Gotify and ntfy
usually run. `notifications.allowLocalTargets: true` also allows localhost. The check is repeated every time a
notification is sent. Tokens you store are not returned by the API (`hasToken` only) but are stored unencrypted in the
database, as are Discord webhook URLs (which contain their own secret); protect the data directory accordingly.

## Extensions

Extension JARs in `extensions/` are loaded into the server process **with the same privileges as the server itself**. An
extension can read and write everything the server can, including the database and every other user's data, and make any
network request. They are **not signed, not verified and not sandboxed**. Only install extensions whose code you trust,
and keep the `extensions/` folder writable only by you. An administrator can enable, disable, reload and unload them
through the API, but cannot install one through the API (JARs are placed on disk).

## Content sources

The server ships one content source, MangaDex (`mangadex`), which talks to MangaDex's public API. The built-in `mock`
and `custom` connectors are test helpers and are only registered when `debug.mockConnectorEnabled` is set. Any other
source is an extension (see above).

- The operator decides what is searched, added and downloaded, and is responsible for following the terms of each
  source: for MangaDex its [API documentation and acceptable usage policy](https://api.mangadex.org/docs/) and its
  [terms of service](https://mangadex.org/terms). The software does not check whether you are allowed to download a
  given work.
- The MangaDex connector sends a `ChapterVault/1.0` User-Agent, limits itself to 3 API requests per second and 40
  at-home lookups per minute, and reports each image it loads from a MangaDex@Home node, as MangaDex asks.
- A MangaDex search only returns works with the content ratings in `connectors.mangadex.contentRatings` (default
  `safe` and `suggestive`; `erotica` and `pornographic` must be switched on by the operator). The setting filters
  search results only; a series added by id is not checked against it.
- Downloaded files are stored unmodified on your disk and served only to your own users. Do not expose the server to
  people you do not intend to give access to, and do not put ads or paid access on top of MangaDex content: MangaDex's
  policy forbids that for API users.
- Extensions are third-party code with the same privileges as the server (see above); their sources and behaviour are
  not reviewed by this project.

## Input and resource limits

- Request bodies are limited to 10 MB.
- Downloaded pages are limited to 25 MB each, covers to 5 MB, and a chapter to 512 MB in total; images with absurd
  dimensions are not decoded. A source that sends more is refused instead of filling the memory.
- Every task has a timeout, at most three run at once and two per source.
- Covers are only stored when they are an image the server knows; everything is served with
  `X-Content-Type-Options: nosniff`.
- Browsers may only call the API from other sites if you list their origin in `server.corsOrigins`.
- Page images are sent with `Cache-Control: private` so a shared cache does not keep one user's pages for another.

## Data at rest

The SQLite database, the library and `config.yaml` are plain files protected only by file permissions. The image runs
as uid 10001; on a plain install run the server as its own user and keep the data directory `0700`.
There is no encryption at rest.

## Known limits

- No two-factor authentication, no per-user API tokens, no audit log of administrator actions.
- Sessions cannot be listed or revoked individually (log out, change the password, or delete the account).
- The rate limiter keeps its counters in memory; a restart resets them.
- Dependencies are updated through Dependabot pull requests; there is no automated vulnerability scan yet.

## Hardening checklist

1. TLS reverse proxy in front; port bound to `127.0.0.1`.
2. `trustedProxies` set, private ranges removed from `trustedNetworks` unless you really want them exempt.
3. Strong administrator password; registration off unless needed.
4. Data directory only readable by the server user; backups stored with the same care.
5. Only extensions you trust.
6. Pin the image version and read the changelog before upgrading.
