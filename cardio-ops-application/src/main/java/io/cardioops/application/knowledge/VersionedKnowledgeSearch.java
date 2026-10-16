package io.cardioops.application.knowledge;

import io.cardioops.domain.knowledge.KnowledgeSnippet;

import java.util.List;

/**
 * 调查只依赖这个只读口。底下是向量库还是测试替身，模型代码不用知道。知识留在本地，不走 MCP。
 */
public interface VersionedKnowledgeSearch {

    /** 当前运行环境是否真正配置了知识库；false 时不能把 RAG Tool 暴露给模型。 */
    default boolean available() {
        return true;
    }

    /** 返回已经按 Profile、标签、生效时间和相似度过滤的知识片段。 */
    List<KnowledgeSnippet> search(KnowledgeSearchQuery query);
}
