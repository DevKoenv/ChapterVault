# Planned API Spec (POC)

> **IMPORTANT**
> This is a **planned API spec**, not an implementation contract.
> Everything here is **subject to change at developer discretion**.
> The goal is clarity for API consumers and client builders, not strict guarantees.

---

## Design Goals

* Simple, obvious consumer flow
* Single mental model for search & library
* No dependency on remote IDs
* Optimized for automatic downloading
* OPDS treated as a read-only consumer of downloaded content

---

## Core Concepts

### Content Model (Conceptual)

```
Series
 └── Chapters
      └── Pages (materialized only after download)
```

* **Series** and **Chapters** may exist as metadata-only
* **Pages** only exist after downloading
* Downloading is the only way content becomes readable

---

## API Surface Overview

```
/api/v1
  catalog/    # search & browse all known content
  downloads/  # background download jobs
  library/    # convenience view of downloaded content

/opds/*       # proof-of-concept reader interface
```

---

## Catalog API

The catalog represents **all known content**, regardless of download state.
This is the primary entry point for dashboards, UIs, and API consumers.

---

### List / Search Series

```
GET /api/v1/catalog/series
```

Purpose:

* Search across all sources
* Show downloaded and non-downloaded content
* Drive user decisions

Suggested query parameters (non-exhaustive):

* `q` – search query
* `downloaded=true|false`
* `hasUpdates=true`
* `source`

Returns (loosely):

* Series metadata
* Download state summary
* Chapter availability counts

---

### Get Series Details

```
GET /api/v1/catalog/series/{seriesId}
```

Purpose:

* Inspect a single series
* Decide what to download

Returns (loosely):

* Series metadata
* Chapter list
* Per-chapter download state
* **No pages**

---

## Downloads API

Downloads represent **state transitions** from metadata-only to materialized content.

---

### Create Download Job

```
POST /api/v1/downloads
```

Purpose:

* Request content to be downloaded

Intended to support:

* Entire series
* Missing chapters only
* Individual chapters

Exact request shape intentionally undefined.

---

### List Downloads

```
GET /api/v1/downloads
```

Purpose:

* Observe active and completed jobs
* Power dashboards and automation

---

### Get Download Status

```
GET /api/v1/downloads/{downloadId}
```

Purpose:

* Track progress
* Debug failures

---

## Library API

The library is a **filtered view** of the catalog showing only downloaded content.
It exists for ergonomics and convenience.

---

### List Library Series

```
GET /api/v1/library/series
```

Equivalent to:

```
GET /api/v1/catalog/series?downloaded=true
```

---

### Get Library Series

```
GET /api/v1/library/series/{seriesId}
```

Returns:

* Downloaded chapters only
* Page counts
* Read-related metadata (future)

---

## OPDS

```
/opds/*
```

* Proof-of-concept reader
* Read-only
* Consumes only downloaded content
* Not part of this API spec

---

## Intended Consumer Flow (Informational)

> This section documents *how the API is meant to be used*,
> but is not itself part of the API contract.

1. Search via `catalog`
2. Inspect series & chapters
3. Trigger downloads
4. Monitor progress
5. Read via OPDS

---

## Notes & Non-Goals

* No remote IDs exposed to consumers
* No connector-specific behavior exposed
* No guarantee of stable response shapes
* No promise of backward compatibility (POC)

---

## Rationale Summary

* One main entry point (`catalog`)
* Downloads as explicit state changes
* Library as a convenience filter
* OPDS cleanly separated as reader-only

This structure favors simplicity, flexibility, and future iteration.
