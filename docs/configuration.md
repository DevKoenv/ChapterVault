# Configuration Guide

ChapterVault can be configured through environment variables or YAML configuration files.

## Environment Variables

### Server Configuration

| Variable                | Default                 | Description                                |
|-------------------------|-------------------------|--------------------------------------------|
| `CHAPTERVAULT_HOST`     | `0.0.0.0`               | HTTP server bind address                   |
| `CHAPTERVAULT_PORT`     | `8080`                  | HTTP server port                           |
| `CHAPTERVAULT_BASE_URL` | `http://localhost:8080` | Public base URL (for OPDS links)           |
| `CHAPTERVAULT_ENV`      | `production`            | Environment: `development` or `production` |

> **Note:** Setting `CHAPTERVAULT_ENV=development` enables mock connectors for testing. In production mode, only explicitly registered connectors are available. See [LEGAL_SOURCES.md](LEGAL_SOURCES.md) for information on adding connectors.

### Storage Configuration

| Variable                         | Default                    | Description                            |
|----------------------------------|----------------------------|----------------------------------------|
| `CHAPTERVAULT_DATA_PATH`         | `~/ChapterVault/data`      | Database and cache location            |
| `CHAPTERVAULT_STORAGE_PATH`      | `~/ChapterVault/downloads` | Downloaded content location            |
| `CHAPTERVAULT_MIN_FREE_SPACE_MB` | `500`                      | Minimum free disk space in megabytes   |

### Database Configuration

| Variable                   | Default          | Description                                              |
|----------------------------|------------------|----------------------------------------------------------|
| `CHAPTERVAULT_DB_TYPE`     | `sqlite`         | Database type: `sqlite`, `h2`, `h2_memory`, `postgresql` |
| `CHAPTERVAULT_DB_PATH`     | (auto-generated) | Path to database file (for file-based DBs)               |
| `CHAPTERVAULT_DB_HOST`     | `localhost`      | Database host (PostgreSQL)                               |
| `CHAPTERVAULT_DB_PORT`     | `5432`           | Database port (PostgreSQL)                               |
| `CHAPTERVAULT_DB_NAME`     | `chaptervault`   | Database name (PostgreSQL)                               |
| `CHAPTERVAULT_DB_USERNAME` | (none)           | Database username (PostgreSQL)                           |
| `CHAPTERVAULT_DB_PASSWORD` | (none)           | Database password (PostgreSQL)                           |

#### Database Examples

**SQLite (Default - Recommended)**

```bash
CHAPTERVAULT_DB_TYPE=sqlite
# Path auto-generated: $DATA_PATH/chaptervault.db
```

**H2 (File-based)**

```bash
CHAPTERVAULT_DB_TYPE=h2
# Path auto-generated: $DATA_PATH/chaptervault
```

**H2 In-Memory (For Testing)**

```bash
CHAPTERVAULT_DB_TYPE=h2_memory
# Data is lost on restart
```

**PostgreSQL**

```bash
CHAPTERVAULT_DB_TYPE=postgresql
CHAPTERVAULT_DB_HOST=localhost
CHAPTERVAULT_DB_PORT=5432
CHAPTERVAULT_DB_NAME=chaptervault
CHAPTERVAULT_DB_USERNAME=chaptervault
CHAPTERVAULT_DB_PASSWORD=yourpassword
```

## YAML Configuration

Create a `config/chaptervault.yaml` file in your working directory:

```yaml
server:
    port: 8080
    host: 0.0.0.0
    base_url: http://localhost:8080

storage:
    path: /path/to/downloads
    format: cbz
    min_free_space_mb: 500

database:
    type: h2
    path: /path/to/data/chaptervault
    username: null
    password: null

cache:
    cleanup:
        enabled: true           # Enable automatic cache cleanup
        ttl_days: 90            # Days before non-library series are cleaned up
        run_interval_hours: 24  # How often cleanup job runs

http:
    user_agent: "ChapterVault/0.1"
    connect_timeout_seconds: 30
    read_timeout_seconds: 60
    follow_redirects: true
    max_redirects: 5

browser:
    enabled: false
    headless: true
    max_browsers: 2

connectors:
    # Per-connector configuration (no nesting under "specific:")
    ExampleConnector:
        enabled: true
        priority: 10
        rate_limit:
            min_delay_millis: 1000
            max_concurrent: 1
```

## Cache Configuration

ChapterVault caches series metadata from search results. Series in your library are protected from cleanup, while cached-only series are subject to TTL-based cleanup.

| Setting                  | Default | Description                                      |
|--------------------------|---------|--------------------------------------------------|
| `cache.cleanup.enabled`  | `true`  | Enable automatic cleanup of stale cached series  |
| `cache.cleanup.ttl_days` | `90`    | Days before non-library series are cleaned up    |
| `cache.cleanup.run_interval_hours` | `24` | How often the cleanup job runs          |

### Manual Cache Cleanup

Trigger cleanup manually via the admin API:

```bash
# Check cache status
curl "http://localhost:8080/api/v1/admin/cache/status"

# Run cleanup
curl -X POST "http://localhost:8080/api/v1/admin/cache/cleanup"
```

## Connector Configuration

### Per-Connector Settings

Each connector is configured directly under the `connectors:` key:

```yaml
connectors:
    MyConnector:
        enabled: true         # Enable/disable connector
        priority: 10          # Higher = preferred
        auth:
            username: "user"
            password: "pass"
            # Or API key:
            # api_key: "your-api-key"
            # Or custom headers:
            # headers:
            #   Authorization: "Bearer token"
        rate_limit:
            min_delay_millis: 2000
            max_concurrent: 1
            max_requests_per_window: 30
            # window_duration_millis: 60000  # Default: 60 seconds
        # Connector-specific custom settings
        preferred_quality: "high"
```

### Site-Level Rate Limiting

In addition to the orchestrator-level `rate_limit`, each connector can configure domain-aware site rate limits that throttle the actual outgoing HTTP requests. This two-layer approach lets you control both how many connector operations run simultaneously and how aggressively each domain is hit.

```yaml
connectors:
    MyConnector:
        rate_limit:               # Orchestrator layer: controls connector task concurrency
            min_delay_millis: 500
            max_concurrent: 2
            max_requests_per_window: 60
        site_rate_limits:         # Site layer: controls outgoing HTTP request rate per domain
            defaults:             # Applied to all auto-created per-host buckets
                min_delay_millis: 300
                max_concurrent: 3
                max_requests_per_window: 120
                window_duration_millis: 60000
            buckets:
                cdn:              # Unlimited throughput for CDN image requests
                    unlimited: true
                api:              # Stricter limits for the API subdomain
                    min_delay_millis: 100
                    max_concurrent: 4
                    max_requests_per_window: 200
```

Connectors tag individual instructions with a `rateLimitBucket` name to route them to a named bucket instead of the default host-based bucket. When a 429 response is received, the limiter automatically increases the delay using AIMD-based adaptive backoff.

### Rate Limit Status

Inspect live rate limiter state via the admin API:

```bash
curl http://localhost:8080/api/v1/admin/ratelimits
```

The response includes:
- **`orchestrator`**: Per-connector state (concurrent slots, window counts, last request time)
- **`site`**: Active per-domain buckets including backoff state and adaptive delay multiplier

## Docker Configuration

Build the image locally:

```bash
git clone https://github.com/DevKoenv/ChapterVault.git
cd ChapterVault
docker build -t chaptervault .
```

Using environment variables with Docker:

```bash
docker run -d \
  -p 8080:8080 \
  -e CHAPTERVAULT_DB_TYPE=postgresql \
  -e CHAPTERVAULT_DB_HOST=db \
  -e CHAPTERVAULT_DB_PORT=5432 \
  -e CHAPTERVAULT_DB_NAME=chaptervault \
  -e CHAPTERVAULT_DB_USERNAME=chaptervault \
  -e CHAPTERVAULT_DB_PASSWORD=secret \
  -v /path/to/downloads:/downloads \
  -v /path/to/data:/data \
  chaptervault
```

Using a config file with Docker:

```bash
docker run -d \
  -p 8080:8080 \
  -v /path/to/config:/app/config:ro \
  -v /path/to/downloads:/downloads \
  -v /path/to/data:/data \
  chaptervault
```

## Logging Configuration

Set log level via environment variable:

```bash
CHAPTERVAULT_LOG_LEVEL=DEBUG  # TRACE, DEBUG, INFO, WARN, ERROR
```

Or in `logback.xml`:

```xml

<configuration>
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="STDOUT"/>
    </root>

    <logger name="dev.koenv.chaptervault" level="DEBUG"/>
</configuration>
```

## Reverse Proxy Configuration

### Nginx

```nginx
server {
    listen 80;
    server_name comics.example.com;
    return 301 https://$server_name$request_uri;
}

server {
    listen 443 ssl http2;
    server_name comics.example.com;

    ssl_certificate /etc/letsencrypt/live/comics.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/comics.example.com/privkey.pem;

    location / {
        proxy_pass http://localhost:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # WebSocket support (for future features)
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }

    # Increase timeout for large downloads
    proxy_read_timeout 300s;
    proxy_send_timeout 300s;

    # Increase max body size for uploads
    client_max_body_size 100M;
}
```

### Traefik

```yaml
# docker-compose.yml
services:
    chaptervault:
        build: .
        labels:
            - "traefik.enable=true"
            - "traefik.http.routers.chaptervault.rule=Host(`comics.example.com`)"
            - "traefik.http.routers.chaptervault.entrypoints=websecure"
            - "traefik.http.routers.chaptervault.tls.certresolver=letsencrypt"
            - "traefik.http.services.chaptervault.loadbalancer.server.port=8080"
```

## Troubleshooting

### Common Issues

**Database connection errors:**

- Check `CHAPTERVAULT_DB_TYPE` matches your database
- Verify connection URL, username, and password
- Ensure database server is running and accessible

**Permission denied errors:**

- Ensure the application has write access to data and storage paths
- In Docker, check volume mount permissions

**Rate limiting errors:**

- Increase `min_delay_millis` in connector `rate_limit` configuration
- Decrease `max_concurrent` and `max_requests_per_window`
- Check the live rate limiter state: `GET /api/v1/admin/ratelimits`

**Out of memory errors:**

- Increase JVM heap: `JAVA_OPTS="-Xmx512m"`
- Check for memory leaks in long-running downloads
