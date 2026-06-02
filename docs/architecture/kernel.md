# Kernel

The kernel is the system core. It owns all domain state and exposes stable APIs.

## Responsibilities

- User authentication and RBAC
- Library state (series, chapters)
- Task system (downloads, metadata fetching)
- Extension lifecycle management
- Domain event bus
- Per-user reading status tracking
- Push notification target management and dispatch

## Key contracts

- `kernel.api`: the sole public surface; all external callers use only these interfaces
  - `LibraryReadApi`, `LibraryCommandApi`: series and chapter access
  - `AuthApi`: authentication and session management
  - `ProgressApi`, `BookmarkApi`: per-user reading data
  - `ReadingStatusApi`: per-user series reading status (PLAN_TO_READ, READING, COMPLETED, DROPPED, ON_HOLD)
  - `NotificationApi`: CRUD for notification targets
  - `NotificationDispatchApi`: send notifications and test dispatch
  - `ChapterPageSource`: read individual pages from downloaded chapters (implemented by `FileStorage`)
  - `ExtensionConfigApi`: read and write per-extension key/value configuration
  - `SystemApi`: task and extension introspection
- `kernel.extension`: extension context and lifecycle contracts
- `kernel.event`: domain event bus

## Dependency rule

The kernel depends only on `:shared`. Nothing in `:kernel` may import from `:extensions`, `:infrastructure`, or `:interfaces`.
