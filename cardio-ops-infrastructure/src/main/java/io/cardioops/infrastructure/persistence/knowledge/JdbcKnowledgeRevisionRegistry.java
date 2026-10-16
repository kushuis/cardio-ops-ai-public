package io.cardioops.infrastructure.persistence.knowledge;

import io.cardioops.application.knowledge.KnowledgeRevisionRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 登记表避免每次启动把同一不可变版本再送去嵌入。它不保存向量本身。
 */
public final class JdbcKnowledgeRevisionRegistry implements KnowledgeRevisionRegistry {

    private final JdbcClient jdbc;

    public JdbcKnowledgeRevisionRegistry(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<IndexedKnowledgeRevision> find(String revisionKey) {
        return jdbc.sql("""
                        SELECT revision_key, content_checksum, chunk_count, indexed_at
                        FROM knowledge_revision
                        WHERE revision_key = :revisionKey
                        """)
                .param("revisionKey", revisionKey)
                .query((rs, rowNum) -> new IndexedKnowledgeRevision(
                        rs.getString("revision_key"), rs.getString("content_checksum"),
                        rs.getInt("chunk_count"), rs.getTimestamp("indexed_at").toInstant()))
                .optional();
    }

    @Override
    public void markIndexed(String revisionKey, String checksum, int chunkCount, Instant indexedAt) {
        jdbc.sql("""
                        INSERT INTO knowledge_revision (
                            revision_key, content_checksum, chunk_count, indexed_at
                        ) VALUES (
                            :revisionKey, :checksum, :chunkCount, :indexedAt
                        )
                        ON CONFLICT (revision_key) DO NOTHING
                        """)
                .param("revisionKey", revisionKey)
                .param("checksum", checksum)
                .param("chunkCount", chunkCount)
                .param("indexedAt", Timestamp.from(indexedAt))
                .update();
    }
}
