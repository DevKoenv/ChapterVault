# Configuration Guide

ChapterVault can be configured through environment variables or YAML configuration files.

## Environment Variables

### Server Configuration

| Variable                | Default                 | Description                                |
|-------------------------|-------------------------|--------------------------------------------|
| `PORT`                  | `8080`                  | HTTP server port                           |
| `HOST`                  | `0.0.0.0`               | HTTP server bind address                   |
| `CHAPTERVAULT_BASE_URL` | `http://localhost:8080` | Public base URL (for OPDS links)           |
| `CHAPTERVAULT_ENV`      | `production`            | Environment: `development` or `production` |

> **Note:** Setting `CHAPTERVAULT_ENV=development` enables mock connectors for testing. In production mode, only explicitly registered connectors are available. See [LEGAL_SOURCES.md](LEGAL_SOURCES.md) for information on adding connectors.

### Storage Configuration

| Variable                      | Default                    | Description                                    |
|-------------------------------|----------------------------|------------------------------------------------|
| `CHAPTERVAULT_DATA_PATH`      | `~/ChapterVault/data`      | Database and cache location                    |
| `CHAPTERVAULT_STORAGE_PATH`   | `~/ChapterVault/downloads` | Downloaded content location                    |
| `CHAPTERVAULT_MIN_FREE_SPACE` | `524288000`                | Minimum free disk space (bytes, default 500MB) |

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

Create a `config/application.yml` file in your data directory:

```yaml
server:
    port: 8080
    host: 0.0.0.0
    baseUrl: http://localhost:8080

storage:
    path: /path/to/downloads
    minFreeSpace: 524288000  # 500MB

database:
    type: h2
    # url: jdbc:h2:file:/path/to/data/chaptervault
    # user: null
    # password: null

http:
    userAgent: "ChapterVault/0.1"
    timeout: 30000
    followRedirects: true
    maxRedirects: 5

browser:
    enabled: false
    headless: true
    timeout: 60000
    poolSize: 2

connectors:
    # Global connector settings
    defaultRateLimit:
        minDelayMs: 500
        maxConcurrent: 2
        maxRequestsPerMinute: 60

    # Per-connector overrides
    specific:
        ExampleConnector:
            enabled: true
            priority: 10
            rateLimit:
                minDelayMs: 1000
                maxConcurrent: 1
```

## Connector Configuration

### Global Rate Limits

```yaml
connectors:
    defaultRateLimit:
        minDelayMs: 500         # Minimum delay between requests
        maxConcurrent: 2        # Maximum concurrent requests
        maxRequestsPerMinute: 60
```

### Per-Connector Settings

```yaml
connectors:
    specific:
        MyConnector:
            enabled: true         # Enable/disable connector
            priority: 10          # Higher = preferred
            auth:
                username: "user"
                password: "pass"
                # Or API key:
                # apiKey: "your-api-key"
                # Or custom headers:
                # headers:
                #   Authorization: "Bearer token"
            rateLimit:
                minDelayMs: 2000
                maxConcurrent: 1
                maxRequestsPerMinute: 30
            custom:
                # Connector-specific settings
                preferredQuality: "high"
```

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
  -e CHAPTERVAULT_DB_URL=jdbc:postgresql://db:5432/chaptervault \
  -e CHAPTERVAULT_DB_USER=chaptervault \
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

- Increase `minDelayMs` in connector configuration
- Decrease `maxConcurrent` and `maxRequestsPerMinute`

**Out of memory errors:**

- Increase JVM heap: `JAVA_OPTS="-Xmx512m"`
- Check for memory leaks in long-running downloads
