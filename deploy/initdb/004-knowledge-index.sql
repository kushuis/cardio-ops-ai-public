-- Register immutable knowledge revisions. Spring AI PgVectorStore owns the vector table.
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS hstore;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE IF NOT EXISTS knowledge_revision (
    revision_key VARCHAR(256) PRIMARY KEY,
    content_checksum VARCHAR(64) NOT NULL,
    chunk_count INTEGER NOT NULL,
    indexed_at TIMESTAMPTZ NOT NULL
);
