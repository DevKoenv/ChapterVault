# Runtime (Task System)

The runtime manages background tasks: downloads, metadata fetches, chapter list refreshes.

## Components

- `TaskQueue`: enqueues and dequeues tasks
- `TaskExecutorService`: runs tasks on a small worker pool (3 at a time, 2 per connector) with a timeout per task; a running task can be cancelled; a failed task is retried after 30 s, 2 min and 10 min, and the retry time is stored in the database (`tasks.run_after`) so a restart does not lose it. The heartbeat behind `/health` is independent of the workers
- `SeriesRefreshScheduler`: periodically re-enqueues `FETCH_SERIES_METADATA` for every series in the library
- `TaskEvents`: sealed hierarchy of task lifecycle events published to the EventBus

## Task types

- `DOWNLOAD_CHAPTER`
- `FETCH_SERIES_METADATA`
- `FETCH_CHAPTERS`

`TaskType` is a **closed set** in 1.x: extensions cannot add task types, and the values appear in REST responses and the
event stream, so adding one is an API change. Downloading a whole series enqueues one `DOWNLOAD_CHAPTER` task per chapter.
