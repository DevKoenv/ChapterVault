# ChapterVault API — Usage Flow & Data Storage

## Overview

The API is split into four domains:

| Domain      | Base path         | Purpose                                            |
|-------------|-------------------|----------------------------------------------------|
| **Catalog** | `/api/v1/catalog` | Discover and browse content from source connectors |
| **Library** | `/api/v1/library` | Manage owned/tracked series and trigger downloads  |
| **Tasks**   | `/api/v1/tasks`   | Inspect and manage background download jobs        |
| **Admin**   | `/api/v1/admin`   | System maintenance (cache, rate limits)            |

---

## Typical Usage Flow

### 1. Discover a series

**By keyword search** — searches one or all registered connectors:

```
GET /api/v1/catalog/search?q=one+piece&connector=asura-scans
```

- Connector is optional; omitting it searches all connectors.
- Results are upserted into the DB as lightweight cache entries (`inLibrary=false`).
- Returns `CatalogSearchResponse { series: List<SeriesDto>, connector: String? }`.

**By direct URL** — connector is auto-detected from the URL:

```
GET /api/v1/catalog/search?url=https://asurascans.com/manga/one-piece
```

- Fetches full series metadata **and** the chapter list from the connector.
- Both series and chapters are upserted into the DB.
- Returns `CatalogSearchResponse` with a single `SeriesDto`.

### 2. Inspect a series

```
GET /api/v1/catalog/series/{seriesId}
```

- Fetches fresh metadata + chapter list from the connector on every call.
- Upserts the result back into the DB (merge semantics: non-null values win).
- Returns `SeriesDetailResponse` (includes inline chapter list for convenience).

```
GET /api/v1/catalog/series/{seriesId}/chapters
```

- Returns all chapters from the local DB — no connector call.
- Use when you already have up-to-date data and just need the chapter list.

### 3. Add a series to the library

```
POST /api/v1/library/series
Body: { "seriesId": "<uuid>", "autoDownload": false }
```

- Series must already exist in the DB (discovered in step 1/2).
- Sets `inLibrary=true` and records `addedToLibraryAt`.
- If `autoDownload=true`, a `DOWNLOAD_SERIES` task is created and launched immediately; the response includes `taskId`.

### 4. Keep a library series up to date

```
POST /api/v1/library/series/{seriesId}/refresh
```

- Series must be `inLibrary=true`; 404 otherwise.
- Fetches fresh metadata + chapter list from the connector.
- Upserts everything; returns the updated `SeriesDetailResponse`.

### 5. Download content

**Entire series:**

```
POST /api/v1/library/series/{seriesId}/download
```

**Single chapter:**

```
POST /api/v1/library/series/{seriesId}/chapters/{chapterId}/download
```

Both endpoints:

1. Create a persisted `DownloadTask` record (status `PENDING`).
2. Hand the task to the `Orchestrator`, which launches it as a background job.
3. Return `TaskCreatedResponse { taskId, status, message }` immediately (HTTP 202).

### 6. Monitor progress

```
GET /api/v1/tasks                  — list all tasks (filter: ?status=RUNNING)
GET /api/v1/tasks/{taskId}         — poll a specific task
POST /api/v1/tasks/{taskId}/cancel — cancel a running/pending task
DELETE /api/v1/tasks/{taskId}      — remove a task from history
```

---

## Data Storage: What, When, and How

### Series table

| Event                                 | What is stored                                                         | Merge behaviour                                                                                         |
|---------------------------------------|------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------|
| Keyword search result                 | `sourceUrl`, `title`, `coverUrl`, `status`, `tags`, connector metadata | `upsertAllFromSearch()` — non-null fields win; existing non-null fields are never overwritten with null |
| URL lookup / catalog detail / refresh | All of the above + `description`, `author`, `language`                 | `upsert()` — same merge semantics                                                                       |
| Add to library                        | `inLibrary=true`, `addedToLibraryAt`                                   | Set unconditionally                                                                                     |
| Remove from library                   | `inLibrary=false`, `addedToLibraryAt=null`                             | Set unconditionally                                                                                     |

Series discovered via search that are **never added to the library** are cache entries. They are eligible for cleanup by `POST /api/v1/admin/cache/cleanup`.

### Chapters table

| Event                                                 | What is stored                                                                                      |
|-------------------------------------------------------|-----------------------------------------------------------------------------------------------------|
| URL lookup (`GET /catalog/search?url=`)               | Full chapter list for the series: `sourceUrl`, `title`, `chapterNumber`, `publishDate`, `pageCount` |
| Catalog detail (`GET /catalog/series/{id}`)           | Same — always refreshed from connector                                                              |
| Library refresh (`POST /library/series/{id}/refresh`) | Same                                                                                                |
| Chapter downloaded                                    | `downloadStatus=DOWNLOADED`, `downloadedAt`, `filePath`, `fileSize`, `storageFormat`                |

Chapters are always upserted (`saveAll`): new chapters are inserted, existing ones are updated if connector data changed.

`downloadStatus` values: `NOT_DOWNLOADED`, `DOWNLOADING`, `DOWNLOADED`, `FAILED`, `PARTIAL`.

### Tasks table (`download_tasks`)

A task record is created whenever a download is triggered:

- `POST /api/v1/library/series/{id}/download`
- `POST /api/v1/library/series/{id}/chapters/{chapterId}/download`
- `POST /api/v1/library/series` with `autoDownload=true`

Task lifecycle:

```
PENDING → RUNNING → COMPLETED
                 ↘ FAILED
       ↘ CANCELLED  (from PENDING or RUNNING)
```

Progress (`currentProgress` / `totalProgress` / `percentage`) is updated by the Orchestrator as pages are downloaded. Tasks are never automatically deleted; use `DELETE /api/v1/tasks/{taskId}` to clean up history.

---

## Complete Route Reference

```
GET  /health
GET  /

# Catalog — discovery, no ownership
GET  /api/v1/catalog/connectors
GET  /api/v1/catalog/search?q=&url=&connector=
GET  /api/v1/catalog/series/{seriesId}
GET  /api/v1/catalog/series/{seriesId}/chapters

# Library — ownership and downloads
GET    /api/v1/library/series
POST   /api/v1/library/series                                    { seriesId, autoDownload }
GET    /api/v1/library/series/{seriesId}
DELETE /api/v1/library/series/{seriesId}
GET    /api/v1/library/series/{seriesId}/chapters
POST   /api/v1/library/series/{seriesId}/refresh
POST   /api/v1/library/series/{seriesId}/download
POST   /api/v1/library/series/{seriesId}/chapters/{chapterId}/download

# Tasks — background job management
GET    /api/v1/tasks?status=
GET    /api/v1/tasks/{taskId}
POST   /api/v1/tasks/{taskId}/cancel
DELETE /api/v1/tasks/{taskId}

# Admin — system maintenance
GET    /api/v1/admin/cache
POST   /api/v1/admin/cache/cleanup
GET    /api/v1/admin/ratelimits

GET  /opds  (OPDS v1.2 catalog, unchanged)
```
