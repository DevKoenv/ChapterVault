# ChapterVault Architecture

## Module Dependency Graph

```
┌─────────────────────────────────────────────────────────────────┐
│                              APP                                 │
│                     (Bootstrap & Wiring)                         │
└──────────────┬────────────────────────┬────────────────┬────────┘
               │                        │                │
               ▼                        ▼                ▼
        ┌──────────┐            ┌──────────┐    ┌──────────┐
        │   API    │            │   OPDS   │    │  STORAGE │
        │ (REST)   │            │ (v1.2)   │    │  (Files) │
        └────┬─────┘            └────┬─────┘    └────┬─────┘
             │                       │               │
             │                       │               │
             ▼                       ▼               │
        ┌────────────────────────────────┐          │
        │      ORCHESTRATION             │          │
        │   (Task Scheduling & Rate      │◄─────────┘
        │        Limiting)               │
        └────────────┬───────────────────┘
                     │
                     ▼
             ┌──────────────┐
             │  CONNECTORS  │
             │  (Mock, ...)  │
             └───────┬───────┘
                     │
                     ▼
              ┌──────────┐
              │   CORE   │
              │(Interfaces│
              │& Models)  │
              └──────────┘
```

## Data Flow

### Browsing Flow (Metadata Only)
```
User Request
    │
    ▼
┌────────┐ search/       ┌──────────────┐ searchSeries()  ┌──────────┐
│  API   │ metadata  ──► │ Orchestrator │ ──────────────► │Connector │
└────────┘               └──────────────┘                 └──────────┘
    │                            │                              │
    │                            │ Rate Limit                   │
    │                            │ Enforcement                  │
    │                            │                              │
    │◄───────────────────────────┴──────────────────────────────┘
    │                       Metadata
    ▼
Response to User
```

### Download Flow (Binary Data)
```
User Request
    │
    ▼
┌────────┐ download  ┌──────────────┐              ┌──────────┐
│  API   │ chapter ─►│ Orchestrator │─┐            │Connector │
└────────┘           └──────────────┘ │            └────┬─────┘
    │                                  │                 │
    │                                  │ Rate Limit      │ Fetch Pages
    │                                  │ + Retry         │ & Binary Data
    │                                  │                 │
    │                                  ▼                 ▼
    │                          ┌──────────────┐  writePage() ┌─────────┐
    │                          │   Storage    │◄─────────────│Connector│
    │                          │   (CBZ)      │              └─────────┘
    │                          └──────────────┘
    │                                  │
    │ Check Progress                   │
    │◄─────────────────────────────────┘
    │                            CBZ File Created
    ▼
Task Status
```

### OPDS Reading Flow
```
Comic Reader App
    │
    ▼
┌────────┐  Browse    ┌──────────────┐  List Files  ┌─────────┐
│  OPDS  │  Catalog ─►│OPDS Generator│ ───────────► │ Storage │
│ Server │            └──────────────┘              │  (Disk) │
└───┬────┘                   │                      └─────────┘
    │                        │                           │
    │◄───────────────────────┴───────────────────────────┘
    │                   OPDS XML Feed
    │
    ▼
┌────────────┐  Download    ┌─────────┐
│Comic Reader│  CBZ File ──►│ Storage │
└────────────┘              └─────────┘
```

## Key Design Decisions

### 1. Connectors Fetch Everything
- ✅ Handle HTTP/Browser automation
- ✅ Manage tokens and cookies
- ✅ Fetch binary data (images, files)
- ✅ Pass bytes to storage
- ❌ Do NOT write files

### 2. Storage Only Writes
- ✅ Receive bytes from connectors
- ✅ Write files to disk
- ✅ Build formats (CBZ, PDF, EPUB)
- ❌ Do NOT fetch data
- ❌ Do NOT know about URLs

### 3. Orchestrator Coordinates
- ✅ Schedule tasks
- ✅ Enforce rate limits
- ✅ Manage retries
- ✅ Track progress
- ❌ Do NOT fetch data
- ❌ Do NOT parse content

### 4. API Exposes Operations
- ✅ Browse metadata
- ✅ Trigger downloads
- ✅ Monitor progress
- ❌ Do NOT stream downloads
- ❌ Do NOT pause/resume (v0)

### 5. OPDS Serves Content
- ✅ Generate OPDS v1.2 feeds
- ✅ Serve downloaded files
- ✅ Compatible with readers
- ❌ No page streaming (v0)
- Future: Add streaming (v1)

## Module Responsibilities Matrix

| Module        | Fetch Data | Parse Content | Write Files | HTTP API | Schedule Tasks |
|---------------|------------|---------------|-------------|----------|----------------|
| Core          | ❌         | ❌            | ❌          | ❌       | ❌             |
| Connectors    | ✅         | ✅            | ❌          | ❌       | ❌             |
| Storage       | ❌         | ❌            | ✅          | ❌       | ❌             |
| Orchestration | ❌         | ❌            | ❌          | ❌       | ✅             |
| API           | ❌         | ❌            | ❌          | ✅       | ❌             |
| OPDS          | ❌         | ❌            | ❌          | ✅       | ❌             |
| App           | ❌         | ❌            | ❌          | ❌       | ❌             |

Each module has ONE clear responsibility!
