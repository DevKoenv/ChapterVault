# Runtime (Task System)

The runtime manages background tasks: downloads, metadata fetches, chapter list refreshes.

## Components

- `TaskQueue`: enqueues and dequeues tasks
- `TaskExecutorService`: dispatches and executes tasks by type; handles retries with exponential backoff
- `SeriesRefreshScheduler`: periodically re-enqueues `FETCH_SERIES_METADATA` for every series in the library
- `TaskEvents`: sealed hierarchy of task lifecycle events published to the EventBus

## Task types

- `DOWNLOAD_CHAPTER`
- `DOWNLOAD_SERIES`
- `FETCH_SERIES_METADATA`
- `FETCH_CHAPTERS`
