# Event System

ChapterVault uses an in-process domain event bus for decoupled communication between layers.

## Design

- `DomainEvent`: sealed base class for all events
- `EventBus`: publish/subscribe interface
- `EventBus.on<T>()`: inline typed subscription helper

## Usage

Extensions subscribe to events via `ExtensionContext.eventBus`. The `:interfaces` layer uses `EventProjectionService` to fan out events to WebSocket clients.

## Current events

- `TaskEvents`: task lifecycle (enqueued, started, completed, failed, cancelled)
- `NewChaptersDiscovered`: emitted by `TaskExecutorService` after a `FETCH_CHAPTERS` task inserts at least one new chapter. Carries the `Series` and the list of new `Chapter` objects. `NotificationService` subscribes to this event to dispatch push notifications.
