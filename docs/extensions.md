# Writing an extension

An extension is a JAR that adds a **connector** (a source of series and chapters), a **metadata enricher** or a **notification
channel**. The example project `chaptervault-example-extension` (kept outside this repository) is a working template; this page
explains the pieces it is made of.

Extensions are trusted code: they run inside the server with the server's privileges and are not signed or sandboxed. Read
[security](security.md#extensions) before installing one you did not write.

## The pieces

| File in the JAR | Purpose |
|---|---|
| `extension.yaml` | Manifest, read before any code is loaded |
| a class implementing `Extension` | The entry point, named in `entryPoint`. It needs a public constructor without parameters |
| `config.yml` (optional) | Default settings. Copied to `extensions/<id>/config.yml` on the first start and never overwritten |

`extension.yaml`:

```yaml
id: "my-connector"             # lowercase letters, digits and dashes; this is the id the server uses
name: "My Connector"
version: "1.0.0"
minServerVersion: "1.0.0"  # the extension is skipped on an older server (a -rc suffix counts as the release)
description: "..."
author: "..."
priority: 100              # optional
capabilities: [connector]  # at least one
entryPoint: "com.example.myconnector.MyExtension"
```

## The code

Depend on the SDK (`:sdk`, which re-exports the kernel and shared classes) as **compile-only**: the server provides it at run time.
Do not pack the kernel, Kotlin or ktor into your JAR.

**Compile for Java 21.** The server image runs Java 21, and a JAR built for a newer Java is skipped (the log says
`UnsupportedClassVersionError`). Set `jvmTarget` to 21 and `jvmToolchain(21)` in your build.

```kotlin
class MyExtension : Extension {
    override val id = "my-connector"   // must match the manifest
    override val name = "My Connector"
    override val version = "1.0.0"
    override fun capabilities() = setOf(Capability.CanFetchSeries)

    override fun onEnable(context: ExtensionContext) {
        context.connectorRegistry.register(MyConnector())
    }
    override fun onDisable() = Unit
}
```

`ExtensionContext` gives you the shared `httpClient`, read access to the library and progress (`library`, `progress`), a
`config` backed by your `config.yml`, a data folder (`dataDir`), a rate limiter factory and a logger. Everything you register
through `connectorRegistry`, `enricherRegistry` and `notificationRegistry` is removed again when the extension is disabled,
unloaded or fails to enable.

A connector implements `Connector` (`search`, `fetchSeries`, `fetchChapters`, `download`; `fetchPage` and `supportedLanguages`
are optional). A notification channel implements `NotificationChannel` (`typeId`, `send`). An enricher implements
`MetadataEnricher` (`id`, `enrich`).

## Build and install

Build the JAR with your own Gradle build (`./gradlew build`; the example names it `<project>-<version>.jar`). Copy the JAR into `<data dir>/extensions/` and restart the server. Manage it afterwards with `GET /api/v1/extensions` and
`POST /api/v1/extensions/{id}/enable|disable|reload|unload` (permission `MANAGE_EXTENSIONS`). `reload` re-reads the JAR from disk.

## Rules the server enforces

These are covered by `ExternalExtensionLoaderTest` and `ExtensionLoaderServiceTest`.

- A file that is not a working extension is skipped with a log line and never stops the server or the other extensions:
  not a ZIP, no or invalid `extension.yaml`, `minServerVersion` newer than the server, a missing entry point class, a class
  that needs something the JAR does not contain, a class file built for a newer Java than the server runs on.
- Two JARs with the same id: the one that comes first by file name wins, the other is skipped.
- An extension whose `onEnable` throws is shown as `FAILED` with the message, and everything it had already registered is removed.
- An extension cannot replace something that is already registered. Registering a connector with the id of a built-in one
  (`mangadex`) makes the enable fail.

## What is not enforced

- Nothing limits what your code does: it can read the database file, open sockets or never return from `onEnable`
  (which blocks the server's start). Write extensions as if the server's stability depended on them, because it does.
- An extension can call `unregister` on a registry for an id it did not register itself.
