# ChapterVault Dockerfile
# Multi-stage build for smaller final image

# ============================================
# Stage 1: Build
# ============================================
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /app

# Copy Gradle files first for better caching
COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts .
COPY settings.gradle.kts .
COPY gradle.properties .

# Make gradlew executable
RUN chmod +x gradlew

# Download dependencies (cached layer)
COPY modules/core/build.gradle.kts modules/core/
COPY modules/connectors/build.gradle.kts modules/connectors/
COPY modules/orchestration/build.gradle.kts modules/orchestration/
COPY modules/storage/build.gradle.kts modules/storage/
COPY modules/database/build.gradle.kts modules/database/
COPY modules/api/build.gradle.kts modules/api/
COPY modules/opds/build.gradle.kts modules/opds/
COPY modules/app/build.gradle.kts modules/app/

RUN ./gradlew dependencies --no-daemon || true

# Copy source code
COPY modules modules

# Build the application
RUN ./gradlew :app:installDist --no-daemon

# ============================================
# Stage 2: Runtime
# ============================================
FROM eclipse-temurin:21-jre

LABEL org.opencontainers.image.title="ChapterVault"
LABEL org.opencontainers.image.description="Self-hosted manga/comic library server with OPDS support"
LABEL org.opencontainers.image.source="https://github.com/DevKoenv/ChapterVault"
LABEL org.opencontainers.image.licenses="MIT"

# Create non-root user
RUN groupadd -r chaptervault && useradd -r -g chaptervault chaptervault

WORKDIR /app

# Copy built application
COPY --from=builder /app/modules/app/build/install/app .

# Create data directories
RUN mkdir -p /data /downloads && \
    chown -R chaptervault:chaptervault /app /data /downloads

# Switch to non-root user
USER chaptervault

# Environment variables
ENV CHAPTERVAULT_DATA_PATH=/data
ENV CHAPTERVAULT_STORAGE_PATH=/downloads
ENV PORT=8080
ENV HOST=0.0.0.0

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=10s --retries=3 \
    CMD curl -f http://localhost:8080/health || exit 1

# Volume mounts
VOLUME ["/data", "/downloads"]

# Run the application
ENTRYPOINT ["./bin/app"]
