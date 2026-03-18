# ChapterVault Addon Development Guide

A ChapterVault addon is a fat JAR that the server loads at startup from its `addons/` directory.
Each addon declares its contributions (connectors, and in future releases: routes, event listeners)
via an `addon.yml` manifest and an `AddonEntrypoint` class.

---

## Prerequisites

- JDK 21+
- Gradle 8+ (the wrapper in this project handles this automatically)
- A local clone or build of [ChapterVault](https://github.com/DevKoenv/ChapterVault)

---

## Step 1 — Publish core to Maven Local

The `core` module is the only ChapterVault artifact your addon compiles against.
Before building your addon for the first time, publish it to your local Maven cache:

```bash
# From the root of the ChapterVault repository:
./gradlew :core:publishToMavenLocal
```

This installs the artifact at:

```
~/.m2/repository/dev/koenv/chaptervault/core/core/<version>/
```

You only need to repeat this when you update ChapterVault and the core API changes.
The version is defined in the root `build.gradle.kts` of ChapterVault — match it in your addon's
`build.gradle.kts` (`compileOnly("dev.koenv.chaptervault.core:core:<version>")`).

---

## Step 2 — Create your addon

Copy this directory to a new location and rename it:

```
my-awesome-addon/
  build.gradle.kts
  settings.gradle.kts
  GUIDE.md                    ← you can delete this
  src/main/kotlin/
    com/example/myaddon/
      MyAddon.kt              ← entry point, rename and adapt
      AddonConfig.kt          ← env-var driven config, adapt or remove
      MyConnector.kt          ← main connector, rename and adapt
      MyMirrorConnector.kt    ← optional second connector, remove if not needed
  src/main/resources/
    addon.yml
```

### Rename the package

Replace `com.example.myaddon` with your own package throughout all `.kt` files and in `addon.yml`.

### Update addon.yml

```yaml
id: my-awesome-addon          # required: kebab-case, globally unique, must match ^[a-z][a-z0-9-]*$
name: My Awesome Addon
version: 1.0.0
description: Adds a connector for mysite.com
author: Your Name
apiVersion: 1
main: com.yourpackage.YourAddon  # fully-qualified name of your AddonEntrypoint class
# depends: [other-addon-id]       # optional: required dependencies (must load before this addon)
# optionalDepends: [extra-addon]  # optional: load after these if present, ignore if absent
```

`id` must be unique across all installed addons. It is used as the stable identity in the admin API
and for the addon's data directory. `apiVersion` must be `1`.

---

## Step 3 — Implement your addon

### Lifecycle (`YourAddon.kt`)

```kotlin
class YourAddon : AddonEntrypoint {

    private var config = AddonConfig()

    // Called first — read env vars and config here, before any connector is created.
    override fun onLoad() {
        config = AddonConfig(
            baseUrl = System.getenv("MY_ADDON_BASE_URL") ?: config.baseUrl,
            apiKey = System.getenv("MY_ADDON_API_KEY"),
        )
    }

    // Register all contributions here: connectors, and in future releases routes and listeners.
    // A single addon can register multiple connectors (e.g. main site + mirror).
    override fun onEnable(context: AddonContext) {
        logger.info { "Enabling ${context.addonName} v${context.addonVersion}" }
        context.registerConnector(YourConnector(context.executor, config))
        context.registerConnector(YourMirrorConnector(context.executor, config))
    }

    // Release resources, cancel background tasks, close connections.
    // Called in reverse load order during shutdown.
    override fun onDisable() {}
}
```

### Available context fields

| Field | Type | Description |
|-------|------|-------------|
| `context.addonId` | `String` | Stable id from addon.yml |
| `context.addonName` | `String` | Human-readable name |
| `context.addonVersion` | `String` | Version string |
| `context.executor` | `Executor` | Shared HTTP executor — use in all connectors |
| `context.dataDir` | `File` | Persistent data dir: `<addonsDataPath>/<id>/data/` |

For logging, declare a file-level logger the same way as in any Kotlin class:
```kotlin
private val logger = KotlinLogging.logger {}
```

### Addon configuration (`AddonConfig.kt`)

The addon reads environment variables in `onLoad()` and stores them in a config object that gets
passed into each connector. This avoids global state and makes connectors testable in isolation.

```kotlin
data class AddonConfig(
    val baseUrl: String = "https://example.com",
    val apiKey: String? = null,
)
```

### Connector (`YourConnector.kt`)

Implement the `Connector` interface. Receive config via constructor — never read env vars inside a
connector. Use `context.executor` for all network requests, never create your own HTTP client.

```kotlin
class YourConnector(
    override val executor: Executor,
    private val addonConfig: AddonConfig,
) : Connector {

    // Inject auth headers provided by the addon into every request.
    override fun getExecutionContext(): ExecutionContext {
        val base = super.getExecutionContext()
        return if (addonConfig.apiKey != null) {
            base.copy(defaultHeaders = base.defaultHeaders + mapOf("X-API-Key" to addonConfig.apiKey))
        } else base
    }

    // ... implement searchSeries, fetchSeriesMetadata, fetchChapterList, downloadChapter
}
```

See `MyConnector.kt` in this template for a complete example with all four methods implemented.

### Mirror connector (`YourMirrorConnector.kt`)

When a site has mirror domains with the same URL structure, delegate to the main connector
re-configured with the mirror URL rather than duplicating scraping logic:

```kotlin
class YourMirrorConnector(executor: Executor, addonConfig: AddonConfig) : Connector {

    private val delegate = YourConnector(executor, addonConfig.copy(baseUrl = addonConfig.mirrorUrl))

    override val executor get() = delegate.executor
    override val config = ConnectorConfig(id = "your-connector-mirror", ...)
    override val baseUrls = listOf("mirror.example.com")

    override suspend fun searchSeries(query: String) = delegate.searchSeries(query)
    // ... remaining methods delegate the same way
}
```

---

## Step 4 — Build the addon JAR

```bash
./gradlew build
```

The output is a fat JAR at:

```
build/libs/my-addon-1.0.0.jar
```

It contains your code and any `implementation` dependencies. `compileOnly` dependencies
(core, logging) are **not** bundled — the host provides them at runtime.

---

## Step 5 — Install the addon

Drop the JAR into the ChapterVault addons directory (default: `~/ChapterVault/addons/`):

```bash
cp build/libs/my-addon-1.0.0.jar ~/ChapterVault/addons/
```

Restart the server. On startup you should see:

```
Loaded addon 'My Awesome Addon' v1.0.0
Enabled addon 'My Awesome Addon' v1.0.0 — registered 2 connector(s)
```

Your connectors will appear in `GET /api/v1/catalog/connectors`.

---

## Step 6 — Manage via admin API

Once loaded, addons can be inspected and controlled at runtime:

```bash
# List all addons
curl http://localhost:8080/api/v1/admin/addons

# Get addon details and errors
curl http://localhost:8080/api/v1/admin/addons/my-addon

# Disable an addon (unregisters its connectors)
curl -X POST http://localhost:8080/api/v1/admin/addons/my-addon/disable

# Re-enable a disabled addon
curl -X POST http://localhost:8080/api/v1/admin/addons/my-addon/enable

# Reload (disable + re-instantiate from JAR on disk + enable)
curl -X POST http://localhost:8080/api/v1/admin/addons/my-addon/reload

# View lifecycle errors
curl http://localhost:8080/api/v1/admin/addons/my-addon/errors
```

---

## Using JitPack instead of Maven Local

If you prefer not to clone ChapterVault locally, you can resolve addon-api from JitPack.

In `build.gradle.kts`:

1. Uncomment the JitPack repository:
   ```kotlin
   maven("https://jitpack.io")
   ```

2. Replace the `mavenLocal` dependency with:
   ```kotlin
   compileOnly("com.github.koenv.ChapterVault:core:<version>")
   ```
   where `<version>` is a git tag (e.g. `v0.4.1`) or a full commit hash.

3. Comment out or remove the `compileOnly("dev.koenv.chaptervault.core:core:...")` line.

JitPack builds the artifact on first request — the initial resolution may take a minute.

---

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| `addon.yml missing required field: id` | Missing `id` in addon.yml | Add `id: your-addon-id` (kebab-case, e.g. `my-addon`) |
| `id must match ^[a-z][a-z0-9-]*$` | Invalid id format | Use only lowercase letters, digits, and hyphens; start with a letter |
| `No addon.yml found in my-addon.jar` | Missing manifest | Ensure `addon.yml` is in `src/main/resources/` |
| `apiVersion=0, minimum is 1` | Wrong apiVersion in addon.yml | Set `apiVersion: 1` |
| `ClassNotFoundException: YourAddon` | Wrong `main` in addon.yml | Check the fully-qualified class name |
| `NoClassDefFoundError` during enable | core accidentally bundled | Ensure core is declared `compileOnly`, not `implementation` |
| Connector not in API response | Not registered | Call `context.registerConnector(...)` in `onEnable` |
| Addon state is FAILED after reload | Error during load/enable | Check `GET /api/v1/admin/addons/<id>/errors` for details |
