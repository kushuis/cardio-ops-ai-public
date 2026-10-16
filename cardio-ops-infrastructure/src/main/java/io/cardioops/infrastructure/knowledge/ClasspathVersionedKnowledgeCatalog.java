package io.cardioops.infrastructure.knowledge;

import io.cardioops.application.knowledge.KnowledgeIndexChunk;
import org.springframework.core.io.Resource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 最小知识目录来自受版本管理的 JSON，方便审和回滚。Agent 不直接扫任意上传文件。发布仍走 VersionedKnowledgeIndex。
 */
public final class ClasspathVersionedKnowledgeCatalog {

    private final ObjectMapper objectMapper;
    private final Resource resource;

    public ClasspathVersionedKnowledgeCatalog(ObjectMapper objectMapper, Resource resource) {
        this.objectMapper = objectMapper;
        this.resource = resource;
    }

    public List<KnowledgeCatalogRevision> load() {
        try (var input = resource.getInputStream()) {
            List<CatalogDocument> documents = objectMapper.readValue(
                    input, new TypeReference<List<CatalogDocument>>() { });
            var revisions = new ArrayList<KnowledgeCatalogRevision>();
            var seenRevisionKeys = new HashSet<String>();
            for (var document : documents) {
                String revisionKey = document.documentId() + "@" + document.version();
                if (!seenRevisionKeys.add(revisionKey)) {
                    throw new IllegalStateException("DUPLICATE_KNOWLEDGE_REVISION: " + revisionKey);
                }
                String checksum = sha256(objectMapper.writeValueAsBytes(document));
                revisions.add(new KnowledgeCatalogRevision(
                        revisionKey, checksum, explode(document, revisionKey)));
            }
            return List.copyOf(revisions);
        } catch (Exception exception) {
            throw new IllegalStateException("KNOWLEDGE_CATALOG_LOAD_FAILED", exception);
        }
    }

    /** 每个 Profile 单独写一个向量文档，使数据库过滤发生在相似度召回之前。 */
    private List<KnowledgeIndexChunk> explode(CatalogDocument document, String revisionKey) {
        var chunks = new ArrayList<KnowledgeIndexChunk>();
        for (var section : document.sections()) {
            String knowledgeRef = "knowledge:" + document.documentId() + ":"
                    + document.version() + ":" + section.sectionId();
            for (String profileId : document.profileIds()) {
                String vectorId = UUID.nameUUIDFromBytes(
                        (knowledgeRef + "|" + profileId).getBytes(StandardCharsets.UTF_8)).toString();
                chunks.add(new KnowledgeIndexChunk(
                        vectorId, knowledgeRef, revisionKey, document.documentId(), document.version(),
                        document.source(), document.applicability(), document.publishStatus(),
                        document.title(), section.sectionId(), profileId, document.sopTags(),
                        document.effectiveFrom(), document.effectiveUntil(), section.content()));
            }
        }
        return List.copyOf(chunks);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    /** JSON 顶层文档；字段名直接表达发布语义，避免为此引入通用 CMS 模型。 */
    private record CatalogDocument(
            String documentId,
            String version,
            String source,
            String applicability,
            String publishStatus,
            String title,
            List<String> profileIds,
            Set<String> sopTags,
            Instant effectiveFrom,
            Instant effectiveUntil,
            List<CatalogSection> sections
    ) { }

    private record CatalogSection(String sectionId, String content) { }
}
