package io.cardioops.infrastructure.knowledge;

import io.cardioops.application.knowledge.KnowledgeIndexChunk;
import io.cardioops.application.knowledge.KnowledgeSearchQuery;
import io.cardioops.application.knowledge.VersionedKnowledgeIndex;
import io.cardioops.application.knowledge.VersionedKnowledgeSearch;
import io.cardioops.domain.knowledge.KnowledgeSnippet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 向量相似只负责召回。档案、生效时间和 SOP 标签由代码再滤一遍。模型把问题写得很宽，也读不到另一份档案的知识。
 * 检索是本地的，不走 MCP。Span 叫 rag.search，一次检索一段。要不要导出看 management.opentelemetry.enabled，CARDIO_OPS_OTEL_ENABLED，默认关。
 * 写入按修订键去重，重复启动不会把同一版嵌两遍。
 *
 *
 * 相似度高只说明文本像。另一份设备档案的说明也可以很像。范围必须是硬条件。
 */
public final class SpringAiVectorKnowledgeRepository
        implements VersionedKnowledgeSearch, VersionedKnowledgeIndex {

    private static final Logger log = LoggerFactory.getLogger(SpringAiVectorKnowledgeRepository.class);
    private static final String PROFILE_ID = "profile_id";
    private static final String REVISION_KEY = "revision_key";
    private static final String PUBLISH_STATUS = "publish_status";
    private static final String EFFECTIVE_FROM_EPOCH = "effective_from_epoch";
    private static final String EFFECTIVE_UNTIL_EPOCH = "effective_until_epoch";
    private final VectorStore vectorStore;
    private final double minimumSimilarity;
    private final int embeddingBatchSize;
    private final ObservationRegistry observationRegistry;

    public SpringAiVectorKnowledgeRepository(
            VectorStore vectorStore, double minimumSimilarity, int embeddingBatchSize) {
        this(vectorStore, minimumSimilarity, embeddingBatchSize, ObservationRegistry.NOOP);
    }

    public SpringAiVectorKnowledgeRepository(
            VectorStore vectorStore,
            double minimumSimilarity,
            int embeddingBatchSize,
            ObservationRegistry observationRegistry) {
        if (minimumSimilarity < 0.0 || minimumSimilarity > 1.0) {
            throw new IllegalArgumentException("minimumSimilarity must be between 0 and 1");
        }
        if (embeddingBatchSize < 1) {
            throw new IllegalArgumentException("embeddingBatchSize must be at least 1");
        }
        this.vectorStore = vectorStore;
        this.minimumSimilarity = minimumSimilarity;
        this.embeddingBatchSize = embeddingBatchSize;
        this.observationRegistry = observationRegistry;
    }

    @Override
    public void addRevision(List<KnowledgeIndexChunk> chunks) {
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("knowledge revision chunks must not be empty");
        }
        String revisionKey = chunks.getFirst().revisionKey();
        if (chunks.stream().anyMatch(chunk -> !revisionKey.equals(chunk.revisionKey()))) {
            throw new IllegalArgumentException("all chunks must belong to one revision");
        }
        List<Document> documents = chunks.stream().map(this::toDocument).toList();
        for (int offset = 0; offset < documents.size(); offset += embeddingBatchSize) {
            int endExclusive = Math.min(offset + embeddingBatchSize, documents.size());
            List<Document> embeddingBatch = documents.subList(offset, endExclusive);
            log.info("knowledge embedding batch submitting revisionKey={} batchStart={} batchSize={} totalChunkCount={}",
                    revisionKey, offset, embeddingBatch.size(), documents.size());
            // VectorStore.add() 会在这里调用 EmbeddingModel；必须先按供应商 API 上限拆批，
            // PgVectorStore 自己的 JDBC 写入批次无法限制这一次远程 Embedding 请求。
            vectorStore.add(embeddingBatch);
        }
        log.info("knowledge revision indexed revisionKey={} chunkCount={}", revisionKey, chunks.size());
    }

    @Override
    /**
     * 先按档案、发布状态和生效时间收窄，再看相似度和标签。召回不是授权。
     */
    public List<KnowledgeSnippet> search(KnowledgeSearchQuery query) {
        var observation = Observation.createNotStarted("rag.search", observationRegistry)
                .contextualName("pgvector knowledge search")
                .lowCardinalityKeyValue("rag.store", "pgvector")
                .lowCardinalityKeyValue("rag.profile", query.profileId())
                .start();
        try (var ignored = observation.openScope()) {
            List<KnowledgeSnippet> result = doSearch(query);
            observation.lowCardinalityKeyValue("rag.result", result.isEmpty() ? "miss" : "hit");
            return result;
        } catch (RuntimeException exception) {
            observation.error(exception);
            throw exception;
        } finally {
            observation.stop();
        }
    }

    private List<KnowledgeSnippet> doSearch(KnowledgeSearchQuery query) {
        long at = query.effectiveAt().getEpochSecond();
        String filter = PROFILE_ID + " == '" + query.profileId() + "' && "
                + PUBLISH_STATUS + " == 'PUBLISHED' && "
                + EFFECTIVE_FROM_EPOCH + " <= " + at + " && ("
                + EFFECTIVE_UNTIL_EPOCH + " == -1 || " + EFFECTIVE_UNTIL_EPOCH + " > " + at + ") && ("
                + query.revisionKeys().stream().sorted()
                .map(revision -> REVISION_KEY + " == '" + revision + "'")
                .collect(Collectors.joining(" || ")) + ")";
        SearchRequest request = SearchRequest.builder()
                .query(query.queryText())
                // 先多召回，再由 Java 做标签与生效时间双重过滤。
                .topK(Math.min(30, query.topK() * 3))
                .similarityThreshold(minimumSimilarity)
                .filterExpression(filter)
                .build();
        List<Document> documents = vectorStore.similaritySearch(request);
        Map<String, KnowledgeSnippet> unique = new LinkedHashMap<>();
        for (Document document : documents) {
            KnowledgeSnippet snippet = toSnippet(document);
            if (!query.profileId().equals(snippet.profileId())
                    || !snippet.isEffectiveAt(query.effectiveAt())
                    || !query.revisionKeys().contains(snippet.revisionKey())
                    || disjoint(snippet.sopTags(), query.sopTags())) {
                continue;
            }
            unique.merge(snippet.knowledgeRef(), snippet,
                    (left, right) -> left.similarityScore() >= right.similarityScore() ? left : right);
        }
        List<KnowledgeSnippet> result = unique.values().stream()
                .sorted(Comparator.comparingDouble(KnowledgeSnippet::similarityScore).reversed())
                .limit(query.topK())
                .toList();
        log.info("versioned knowledge searched profileId={} queryDigest={} candidateCount={} resultCount={}",
                query.profileId(), digest(query.queryText()), documents.size(), result.size());
        return result;
    }

    private Document toDocument(KnowledgeIndexChunk chunk) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("knowledge_ref", chunk.knowledgeRef());
        metadata.put(REVISION_KEY, chunk.revisionKey());
        metadata.put("document_id", chunk.documentId());
        metadata.put("version", chunk.version());
        metadata.put("source", chunk.source());
        metadata.put("applicability", chunk.applicability());
        metadata.put(PUBLISH_STATUS, chunk.publishStatus());
        metadata.put("title", chunk.title());
        metadata.put("section", chunk.section());
        metadata.put(PROFILE_ID, chunk.profileId());
        metadata.put("sop_tags", chunk.sopTags().stream().sorted().collect(Collectors.joining(",")));
        metadata.put(EFFECTIVE_FROM_EPOCH, chunk.effectiveFrom().getEpochSecond());
        metadata.put(EFFECTIVE_UNTIL_EPOCH,
                chunk.effectiveUntil() == null ? -1L : chunk.effectiveUntil().getEpochSecond());
        return Document.builder()
                .id(chunk.vectorDocumentId())
                .text(chunk.content())
                .metadata(metadata)
                .build();
    }

    private KnowledgeSnippet toSnippet(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        double score = document.getScore() == null ? 0.0 : document.getScore();
        long until = asLong(metadata.get(EFFECTIVE_UNTIL_EPOCH));
        return new KnowledgeSnippet(
                asText(metadata, "knowledge_ref"), asText(metadata, "document_id"),
                asText(metadata, "version"), asText(metadata, REVISION_KEY),
                asText(metadata, "source"), asText(metadata, "applicability"),
                asText(metadata, PUBLISH_STATUS), asText(metadata, PROFILE_ID),
                asText(metadata, "title"),
                asText(metadata, "section"), document.getText(), score,
                Instant.ofEpochSecond(asLong(metadata.get(EFFECTIVE_FROM_EPOCH))),
                until < 0 ? null : Instant.ofEpochSecond(until),
                parseTags(asText(metadata, "sop_tags")));
    }

    private static String asText(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalStateException("KNOWLEDGE_METADATA_MISSING_" + key.toUpperCase());
        }
        return value.toString();
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private static Set<String> parseTags(String value) {
        return Arrays.stream(value.split(","))
                .map(String::trim).filter(tag -> !tag.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    private static boolean disjoint(Set<String> left, Set<String> right) {
        return left.stream().noneMatch(right::contains);
    }

    private static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }
}
