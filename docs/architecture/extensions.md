# Extensions

Extensions are untrusted plugins that implement kernel interfaces.

## Rules

- Extensions access the kernel exclusively through `ExtensionContext`
- No direct database access
- No routing: the `:interfaces` layer owns all HTTP mounting
- Must be replaceable and independently loadable

## Capabilities

Each extension declares one or more `Capability` sealed class members:
- `CanFetchSeries`: source connector
- `CanDownloadChapters`: chapter downloader
- `CanServeOpds`: OPDS feed provider
- `CanEnrichMetadata`: metadata enrichment
- `CanServeAdmin`: admin UI data provider
