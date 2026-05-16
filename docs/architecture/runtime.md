# Runtime (Task System)

The runtime manages background tasks: downloads, metadata fetches, chapter list refreshes.

## Components

- `TaskQueue` — enqueues and dequeues tasks
- `TaskExecutor` — executes a single task by type
- `TaskScheduler` — schedules tasks with a delay or on a recurring interval
- `TaskEvents` — sealed hierarchy of task lifecycle events published to the EventBus

## Task types

- `DOWNLOAD_CHAPTER`
- `DOWNLOAD_SERIES`
- `FETCH_SERIES_METADATA`
- `FETCH_CHAPTERS`
