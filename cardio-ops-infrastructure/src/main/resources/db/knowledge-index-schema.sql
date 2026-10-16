-- Register immutable knowledge revisions that have completed vector indexing.
-- PGVector 自身的表由 Spring AI 按固定维度创建；生产环境应在确定 Embedding 模型后转成正式迁移。
CREATE TABLE IF NOT EXISTS knowledge_revision (
    revision_key VARCHAR(256) PRIMARY KEY,
    content_checksum VARCHAR(64) NOT NULL,
    chunk_count INTEGER NOT NULL,
    indexed_at TIMESTAMP WITH TIME ZONE NOT NULL
);
