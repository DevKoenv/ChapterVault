# Kernel

The kernel is the system core. It owns all domain state and exposes stable APIs.

## Responsibilities

- User authentication and RBAC
- Library state (series, chapters)
- Task system (downloads, metadata fetching)
- Extension lifecycle management
- Domain event bus

## Key contracts

- `kernel.api` — the sole public surface; all external callers use only these interfaces
- `kernel.extension` — extension context and lifecycle contracts
- `kernel.event` — domain event bus

## Dependency rule

The kernel depends only on `:shared`. Nothing in `:kernel` may import from `:extensions`, `:infrastructure`, or `:interfaces`.
