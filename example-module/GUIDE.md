# ChapterVault Module Development Guide

A ChapterVault module is a fat JAR that the server loads at startup from its `modules/` directory.
Each module declares its contributions (connectors, and in future releases: routes, event listeners)
via a `module.yml` manifest and a `ModuleEntrypoint` class.

---

## Prerequisites

- JDK 21+
- Gradle 8+ (the wrapper in this project handles this automatically)
- A local clone or build of [ChapterVault](https://github.com/DevKoenv/ChapterVault)

---

## Step 1 — Publish core to Maven Local

The `core` module is the only ChapterVault artifact your module compiles against.
Before building your module for the first time, publish it to your local Maven cache:

```bash
# From the root of the ChapterVault repository:
./gradlew :core:publishToMavenLocal
```

This installs the artifact at:

```
~/.m2/repository/dev/koenv/chaptervault/core/core/<version>/
```

You only need to repeat this when you update ChapterVault and the core API changes.
The version is defined in the root `build.gradle.kts` of ChapterVault — match it in your module's
`build.gradle.kts` (`compileOnly("dev.koenv.chaptervault.core:core:<version>")`).

---

## Step 2 — Create your module

Copy this directory to a new location and rename it:

```
my-awesome-module/
  build.gradle.kts
  settings.gradle.kts
  GUIDE.md                    ← you can delete this
  src/main/kotlin/
    com/example/mymodule/
      MyModule.kt             ← entry point, rename and adapt
      ModuleConfig.kt         ← env-var driven config, adapt or remove
      MyConnector.kt          ← main connector, rename and adapt
      MyMirrorConnector.kt    ← optional second connector, remove if not needed
  src/main/resources/
    module.yml
```

### Rename the package

Replace `com.example.mymodule` with your own package throughout all `.kt` files and in `module.yml`.

### Update module.yml

```yaml
name: My Awesome Module
version: 1.0.0
description: Adds a connector for mysite.com
author: Your Name
apiVersion: 1
main: com.yourpackage.YourModule  # fully-qualified name of your ModuleEntrypoint class
```

`apiVersion` must be `1`. This is checked against the server's minimum supported version at load time.

---

## Step 3 — Implement your module

### Lifecycle (`YourModule.kt`)

```kotlin
class YourModule : ModuleEntrypoint {

    private var config = ModuleConfig()

    // Called first — read env vars and config here, before any connector is created.
    override fun onLoad() {
        config = ModuleConfig(
            baseUrl = System.getenv("MY_MODULE_BASE_URL") ?: config.baseUrl,
            apiKey = System.getenv("MY_MODULE_API_KEY"),
        )
    }

    // Register all contributions here: connectors, and in future releases routes and listeners.
    // A single module can register multiple connectors (e.g. main site + mirror).
    override fun onEnable(context: ModuleContext) {
        context.registerConnector(YourConnector(context.executor, config))
        context.registerConnector(YourMirrorConnector(context.executor, config))
    }

    // Release resources, cancel background tasks, close connections.
    // Called in reverse load order during shutdown.
    override fun onDisable() {}
}
```

### Module configuration (`ModuleConfig.kt`)

The module reads environment variables in `onLoad()` and stores them in a config object that gets
passed into each connector. This avoids global state and makes connectors testable in isolation.

```kotlin
data class ModuleConfig(
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
    private val moduleConfig: ModuleConfig,
) : Connector {

    // Inject auth headers provided by the module into every request.
    override fun getExecutionContext(): ExecutionContext {
        val base = super.getExecutionContext()
        return if (moduleConfig.apiKey != null) {
            base.copy(defaultHeaders = base.defaultHeaders + mapOf("X-API-Key" to moduleConfig.apiKey))
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
class YourMirrorConnector(executor: Executor, moduleConfig: ModuleConfig) : Connector {

    private val delegate = YourConnector(executor, moduleConfig.copy(baseUrl = moduleConfig.mirrorUrl))

    override val executor get() = delegate.executor
    override val config = ConnectorConfig(id = "your-connector-mirror", ...)
    override val baseUrls = listOf("mirror.example.com")

    override suspend fun searchSeries(query: String) = delegate.searchSeries(query)
    // ... remaining methods delegate the same way
}
```

---

## Step 4 — Build the module JAR

```bash
./gradlew build
```

The output is a fat JAR at:

```
build/libs/my-module-1.0.0.jar
```

It contains your code and any `implementation` dependencies. `compileOnly` dependencies
(core, logging) are **not** bundled — the host provides them at runtime.

---

## Step 5 — Install the module

Drop the JAR into the ChapterVault modules directory (default: `~/ChapterVault/modules/`):

```bash
cp build/libs/my-module-1.0.0.jar ~/ChapterVault/modules/
```

Restart the server. On startup you should see:

```
Loaded module 'My Awesome Module' v1.0.0 (my-module-1.0.0.jar)
Enabled module 'My Awesome Module' v1.0.0 — registered 2 connector(s)
```

Your connectors will appear in `GET /api/v1/catalog/connectors`.

---

## Using JitPack instead of Maven Local

If you prefer not to clone ChapterVault locally, you can resolve core from JitPack.

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
| `No module.yml found in my-module.jar` | Missing manifest | Ensure `module.yml` is in `src/main/resources/` |
| `apiVersion=0, minimum is 1` | Wrong apiVersion in module.yml | Set `apiVersion: 1` |
| `ClassNotFoundException: YourModule` | Wrong `main` in module.yml | Check the fully-qualified class name |
| `NoClassDefFoundError` during enable | core accidentally bundled | Ensure core is declared `compileOnly`, not `implementation` |
| Connector not in API response | Not registered | Call `context.registerConnector(...)` in `onEnable` |
