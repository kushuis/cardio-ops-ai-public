package io.cardioops.application.knowledge;

import io.cardioops.domain.knowledge.KnowledgeSnippet;

import java.util.List;

/**
 * 没配嵌入或向量库时的空实现。普通 Worker 不会因此去调付费模型。调用方看到不可用，就把知识检索从这轮工具里拿掉。
 *
 *
 * 空列表像是没有相关知识。显式不可用，图才知道这项工具不该交给模型。
 */
public final class UnavailableVersionedKnowledgeSearch implements VersionedKnowledgeSearch {
    @Override
    public boolean available() {
        return false;
    }

    @Override
    public List<KnowledgeSnippet> search(KnowledgeSearchQuery query) {
        throw new IllegalStateException("VERSIONED_KNOWLEDGE_SEARCH_NOT_CONFIGURED");
    }
}
