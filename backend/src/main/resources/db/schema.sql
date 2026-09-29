-- Job metadata in PostgreSQL. Large files (uploads, result GeoJSON, map archives) stay in the job storage directory.
CREATE TABLE IF NOT EXISTS heat_jobs (
    job_id VARCHAR(36) PRIMARY KEY,
    status VARCHAR(16) NOT NULL,
    stage VARCHAR(16) NOT NULL,
    mode VARCHAR(8) NOT NULL,
    diagnostics TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX IF NOT EXISTS heat_jobs_updated_at ON heat_jobs (updated_at);
-- One row per exported variant: exact official summary JSON plus queryable values.
CREATE TABLE IF NOT EXISTS heat_job_variants (
    job_id VARCHAR(36) NOT NULL REFERENCES heat_jobs (job_id) ON DELETE CASCADE,
    variant_rank INTEGER NOT NULL,
    variant_id VARCHAR(255) NOT NULL,
    score NUMERIC(30, 10) NOT NULL,
    calculated_cost NUMERIC(24, 2) NOT NULL,
    new_network_length DOUBLE PRECISION NOT NULL,
    unconnected_count INTEGER NOT NULL,
    summary TEXT NOT NULL,
    PRIMARY KEY (job_id, variant_rank)
);
