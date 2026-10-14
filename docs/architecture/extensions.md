# Extensions

Extensions are third-party plugins that implement kernel interfaces.

**They are architecture-constrained, not sandboxed.** The rules below describe how an extension is supposed to be
written. Nothing enforces them at run time: an extension JAR is loaded into the server JVM with the server's own
privileges and can do anything the server can, including opening the database file or making any network request. JARs
are not signed or verified. Only install extensions you trust; see [security](../security.md#extensions).

## Rules

- Extensions access the kernel exclusively through `ExtensionContext`
- No direct database access
- No routing: the `:interfaces` layer owns all HTTP mounting
- Must be replaceable and independently loadable

## Capabilities

Each extension declares one or more `Capability` sealed class members:
- `CanFetchSeries`: source connector
- `CanDownloadChapters`: chapter downloader
- `CanEnrichMetadata`: metadata enrichment (author, genre, year, etc.)
- `CanSendNotifications`: custom notification channel
