package io.cardioops.worker.knowledge;

import io.cardioops.application.knowledge.KnowledgeRevisionRegistry;
import io.cardioops.application.knowledge.UnavailableVersionedKnowledgeSearch;
import io.cardioops.application.knowledge.VersionedKnowledgeIndex;
import io.cardioops.application.knowledge.VersionedKnowledgeSearch;
import io.cardioops.infrastructure.knowledge.ClasspathVersionedKnowledgeCatalog;
import io.cardioops.infrastructure.knowledge.SpringAiVectorKnowledgeRepository;
import io.cardioops.infrastructure.persistence.knowledge.JdbcKnowledgeRevisionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import io.micrometer.observation.ObservationRegistry;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

/**
 * 知识库关掉时（默认就是关）提供不可用检索，图会把知识工具从白名单拿掉。打开时才建向量库、登记表，并在启动时把还没索引的版本发布出去。
 * 检索 Span 在仓库里按次打开。导出看 CARDIO_OPS_OTEL_ENABLED，默认关。
 *
 *
 * 没有这个口，装配会在调查要检索时才发现缺依赖。显式不可用，工具名单可以在准备阶段就把检索拿掉。
 */

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KnowledgeBaseProperties.class)
public class KnowledgeBaseConfiguration {

    /** 未启用 RAG 时提供显式不可用端口，Graph 会从 Tool 白名单移除知识检索。 */
    @Bean
    @ConditionalOnProperty(
            prefix = "cardio-ops.knowledge", name = "enabled",
            havingValue = "false", matchIfMissing = true)
    VersionedKnowledgeSearch unavailableVersionedKnowledgeSearch() {
        return new UnavailableVersionedKnowledgeSearch();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "cardio-ops.knowledge", name = "enabled", havingValue = "true")
    static class EnabledKnowledgeBaseConfiguration {

        @Bean
        ClasspathVersionedKnowledgeCatalog versionedKnowledgeCatalog(ObjectMapper objectMapper) {
            return new ClasspathVersionedKnowledgeCatalog(
                    objectMapper, new ClassPathResource("knowledge/scheduled-miss-v1.json"));
        }

        @Bean
        SpringAiVectorKnowledgeRepository vectorKnowledgeRepository(
                VectorStore vectorStore,
                KnowledgeBaseProperties properties,
                ObservationRegistry observationRegistry
        ) {
            return new SpringAiVectorKnowledgeRepository(
                    vectorStore, properties.minimumSimilarity(), properties.embeddingBatchSize(),
                    observationRegistry);
        }

        @Bean
        KnowledgeRevisionRegistry knowledgeRevisionRegistry(JdbcClient jdbcClient) {
            return new JdbcKnowledgeRevisionRegistry(jdbcClient);
        }

        @Bean
        KnowledgeBootstrapRunner knowledgeBootstrapRunner(
                ClasspathVersionedKnowledgeCatalog catalog,
                VersionedKnowledgeIndex index,
                KnowledgeRevisionRegistry registry,
                Clock clock
        ) {
            return new KnowledgeBootstrapRunner(catalog, index, registry, clock);
        }
    }

    /**
     * 启动时只向量化尚未发布的不可变版本。相同 revisionKey 内容变化会失败，要求发布者升级 version。
     */
    static final class KnowledgeBootstrapRunner implements ApplicationRunner {
        private static final Logger log = LoggerFactory.getLogger(KnowledgeBootstrapRunner.class);
        private final ClasspathVersionedKnowledgeCatalog catalog;
        private final VersionedKnowledgeIndex index;
        private final KnowledgeRevisionRegistry registry;
        private final Clock clock;

        KnowledgeBootstrapRunner(
                ClasspathVersionedKnowledgeCatalog catalog,
                VersionedKnowledgeIndex index,
                KnowledgeRevisionRegistry registry,
                Clock clock
        ) {
            this.catalog = catalog;
            this.index = index;
            this.registry = registry;
            this.clock = clock;
        }

        @Override
        public void run(ApplicationArguments args) {
            for (var revision : catalog.load()) {
                var indexed = registry.find(revision.revisionKey());
                if (indexed.isPresent()) {
                    if (!indexed.orElseThrow().checksum().equals(revision.checksum())) {
                        throw new IllegalStateException(
                                "KNOWLEDGE_VERSION_IMMUTABLE: " + revision.revisionKey());
                    }
                    log.info("knowledge revision already indexed revisionKey={} chunkCount={}",
                            revision.revisionKey(), indexed.orElseThrow().chunkCount());
                    continue;
                }
                index.addRevision(revision.chunks());
                registry.markIndexed(revision.revisionKey(), revision.checksum(),
                        revision.chunks().size(), clock.instant());
                log.info("knowledge revision published revisionKey={} chunkCount={}",
                        revision.revisionKey(), revision.chunks().size());
            }
        }
    }
}
