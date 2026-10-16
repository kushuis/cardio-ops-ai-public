package io.cardioops.infrastructure.knowledge;

import io.cardioops.application.knowledge.KnowledgeIndexChunk;
import io.cardioops.application.knowledge.KnowledgeSearchQuery;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证向量召回之外仍存在 Profile、历史时间和 SOP 标签的确定性边界。 */
class SpringAiVectorKnowledgeRepositoryTest {

    private static final Instant AT = Instant.parse("2026-09-06T01:00:00Z");

    @Test
    void searchBuildsServerControlledScopeAndRejectsWrongSopTag() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(org.mockito.ArgumentMatchers.any(SearchRequest.class)))
                .thenReturn(List.of(
                        document("knowledge:valid", "icm,transmission", 0.92),
                        document("knowledge:wrong-tag", "icd,parameter-readback", 0.99),
                        document("knowledge:wrong-profile", "icm,gateway", "demo-icm-v2-secure", 0.98)));
        var repository = new SpringAiVectorKnowledgeRepository(vectorStore, 0.55, 10);

        var result = repository.search(new KnowledgeSearchQuery(
                "只有手动上传时如何复核", "demo-icm-v1", Set.of("icm", "gateway"),
                Set.of("playbook@1.0"), AT, 4));

        assertThat(result).extracting(value -> value.knowledgeRef())
                .containsExactly("knowledge:valid");
        var captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());
        assertThat(captor.getValue().getFilterExpression().toString())
                .contains("profile_id", "demo-icm-v1", "revision_key", "playbook@1.0",
                        "publish_status", "PUBLISHED", "effective_from_epoch", "effective_until_epoch");
    }

    @Test
    void indexingKeepsVersionAndProfileMetadataOutOfTheEmbeddedText() {
        VectorStore vectorStore = mock(VectorStore.class);
        var repository = new SpringAiVectorKnowledgeRepository(vectorStore, 0.55, 10);
        var chunk = new KnowledgeIndexChunk(
                "vector-1", "knowledge:playbook:1.0:manual", "playbook@1.0", "playbook", "1.0",
                "CardioOps 审核知识库", "ICM 应传未传复核", "PUBLISHED",
                "排查手册", "manual", "demo-icm-v1", Set.of("icm", "gateway"),
                Instant.parse("2026-01-01T00:00:00Z"), null, "手动上传不能证明自动唤醒恢复。");

        repository.addRevision(List.of(chunk));

        @SuppressWarnings("unchecked")
        var captor = ArgumentCaptor.forClass((Class<List<Document>>) (Class<?>) List.class);
        verify(vectorStore).add(captor.capture());
        Document stored = captor.getValue().getFirst();
        assertThat(stored.getText()).isEqualTo(chunk.content());
        assertThat(stored.getMetadata())
                .containsEntry("profile_id", "demo-icm-v1")
                .containsEntry("knowledge_ref", chunk.knowledgeRef())
                .containsEntry("revision_key", "playbook@1.0")
                .containsEntry("publish_status", "PUBLISHED")
                .containsEntry("effective_until_epoch", -1L);
    }

    @Test
    void indexingSplitsRemoteEmbeddingRequestsAtConfiguredBatchSize() {
        VectorStore vectorStore = mock(VectorStore.class);
        var repository = new SpringAiVectorKnowledgeRepository(vectorStore, 0.55, 10);
        List<KnowledgeIndexChunk> chunks = IntStream.range(0, 11)
                .mapToObj(index -> new KnowledgeIndexChunk(
                        "vector-" + index, "knowledge:playbook:1.0:" + index, "playbook@1.0",
                        "playbook", "1.0", "CardioOps 审核知识库", "ICM 应传未传复核",
                        "PUBLISHED", "排查手册", "section-" + index, "demo-icm-v1",
                        Set.of("icm", "gateway"), Instant.parse("2026-01-01T00:00:00Z"), null,
                        "第 " + index + " 条测试知识。"))
                .toList();

        repository.addRevision(chunks);

        @SuppressWarnings("unchecked")
        var captor = ArgumentCaptor.forClass((Class<List<Document>>) (Class<?>) List.class);
        verify(vectorStore, times(2)).add(captor.capture());
        assertThat(captor.getAllValues()).extracting(List::size).containsExactly(10, 1);
    }

    private Document document(String knowledgeRef, String tags, double score) {
        return document(knowledgeRef, tags, "demo-icm-v1", score);
    }

    private Document document(String knowledgeRef, String tags, String profileId, double score) {
        return Document.builder()
                .id(knowledgeRef)
                .text("测试知识内容")
                .score(score)
                .metadata(Map.ofEntries(
                        Map.entry("knowledge_ref", knowledgeRef),
                        Map.entry("document_id", "playbook"),
                        Map.entry("version", "1.0"),
                        Map.entry("revision_key", "playbook@1.0"),
                        Map.entry("source", "CardioOps 审核知识库"),
                        Map.entry("applicability", "ICM 应传未传复核"),
                        Map.entry("publish_status", "PUBLISHED"),
                        Map.entry("title", "排查手册"),
                        Map.entry("section", "manual"),
                        Map.entry("profile_id", profileId),
                        Map.entry("sop_tags", tags),
                        Map.entry("effective_from_epoch",
                                Instant.parse("2026-01-01T00:00:00Z").getEpochSecond()),
                        Map.entry("effective_until_epoch", -1L)))
                .build();
    }
}
