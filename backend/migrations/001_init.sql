CREATE TABLE IF NOT EXISTS gpx_files (
    id            BIGSERIAL PRIMARY KEY,
    user_uid      TEXT        NOT NULL,
    name          TEXT        NOT NULL,
    distance_m    DOUBLE PRECISION NOT NULL,
    duration_s    BIGINT      NOT NULL,
    point_count   INTEGER     NOT NULL,
    body          BYTEA       NOT NULL,
    size_bytes    INTEGER     NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS gpx_files_user_uid_idx
    ON gpx_files (user_uid, created_at DESC);

CREATE TABLE IF NOT EXISTS _migrations (
    name       TEXT PRIMARY KEY,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
