-- ChapterVault initial schema (1.0.0), PostgreSQL.
--
-- Mirrors db/migration/sqlite/V1__initial_schema.sql column-for-column, with the types Exposed's
-- PostgreSQL dialect actually expects: BOOLEAN (not INTEGER) for bool() columns, TIMESTAMP (not TEXT)
-- for timestamp() columns, DOUBLE PRECISION (not REAL, which is single precision here) for double().
--
-- Rules for this folder:
--   * Flyway runs these files; the name must be VN__description.sql with N counting up from 1 without gaps
--   * never edit a migration after a release has shipped it; add the next VN__name.sql
--   * the checksum of every applied migration is verified at startup
--   * keep this file and the sqlite/ one in step: same tables, columns and constraints, every release

CREATE TABLE users (
    id TEXT NOT NULL,
    username TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    roles TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_users PRIMARY KEY (id)
);

-- names are compared case-insensitively: "Admin" and "admin" are the same account
CREATE UNIQUE INDEX users_username_ci_uq ON users (lower(username));

CREATE TABLE sessions (
    id TEXT NOT NULL,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- SHA-256 of the bearer token; the token itself is never stored
    token_hash TEXT NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_sessions PRIMARY KEY (id)
);

CREATE UNIQUE INDEX sessions_token_hash_uq ON sessions (token_hash);
CREATE INDEX idx_sessions_user_id ON sessions (user_id);

CREATE TABLE series (
    id TEXT NOT NULL,
    title TEXT NOT NULL,
    connector_id TEXT,
    external_id TEXT,
    status TEXT NOT NULL,
    auto_download BOOLEAN NOT NULL DEFAULT FALSE,
    default_format TEXT,
    cover_url TEXT,
    description TEXT,
    language TEXT NOT NULL DEFAULT 'en',
    author TEXT,
    artist TEXT,
    year INTEGER,
    upstream_status TEXT,
    -- comma separated names of the fields a user edited by hand; refreshes from the source leave them alone
    user_edited_fields TEXT NOT NULL DEFAULT '',
    added_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_series PRIMARY KEY (id)
);

-- the same source series in the same language can only be in the library once
CREATE UNIQUE INDEX series_source_uq ON series (connector_id, external_id, language)
    WHERE connector_id IS NOT NULL AND external_id IS NOT NULL;

CREATE TABLE chapters (
    id TEXT NOT NULL,
    series_id TEXT NOT NULL REFERENCES series(id) ON DELETE CASCADE,
    title TEXT NOT NULL,
    chapter_index DOUBLE PRECISION NOT NULL,
    external_id TEXT,
    download_status TEXT NOT NULL DEFAULT 'AVAILABLE',
    format TEXT,
    page_count INTEGER,
    added_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_chapters PRIMARY KEY (id)
);

CREATE UNIQUE INDEX chapters_series_external_uq ON chapters (series_id, external_id) WHERE external_id IS NOT NULL;
CREATE INDEX idx_chapters_series_id ON chapters (series_id);

CREATE TABLE tasks (
    id TEXT NOT NULL,
    type TEXT NOT NULL,
    status TEXT NOT NULL,
    target_type TEXT NOT NULL,
    target_id TEXT NOT NULL,
    payload TEXT NOT NULL DEFAULT '{}',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    error_message TEXT,
    retry_count INTEGER NOT NULL DEFAULT 0,
    -- a retry does not run before this moment; kept in the database so a restart does not lose the wait
    run_after TIMESTAMP,
    CONSTRAINT pk_tasks PRIMARY KEY (id)
);

CREATE TABLE user_series_status (
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    series_id TEXT NOT NULL REFERENCES series(id) ON DELETE CASCADE,
    status TEXT NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_user_series_status PRIMARY KEY (user_id, series_id)
);

CREATE INDEX idx_uss_user_id ON user_series_status (user_id);

CREATE TABLE notification_targets (
    id TEXT NOT NULL,
    name TEXT NOT NULL,
    type TEXT NOT NULL,
    url TEXT NOT NULL,
    token TEXT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_notification_targets PRIMARY KEY (id)
);

CREATE TABLE series_genres (
    series_id TEXT NOT NULL REFERENCES series(id) ON DELETE CASCADE,
    genre TEXT NOT NULL,
    CONSTRAINT pk_series_genres PRIMARY KEY (series_id, genre)
);

CREATE INDEX idx_series_genres_series_id ON series_genres (series_id);

CREATE TABLE user_series_progress (
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    series_id TEXT NOT NULL REFERENCES series(id) ON DELETE CASCADE,
    latest_chapter_id TEXT REFERENCES chapters(id) ON DELETE SET NULL,
    latest_page INTEGER NOT NULL DEFAULT 0,
    read_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_user_series_progress PRIMARY KEY (user_id, series_id)
);

CREATE INDEX idx_usp_user_id ON user_series_progress (user_id);
