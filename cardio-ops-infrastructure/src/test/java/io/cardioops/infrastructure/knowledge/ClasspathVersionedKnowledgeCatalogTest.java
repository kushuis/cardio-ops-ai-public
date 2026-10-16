package io.cardioops.infrastructure.knowledge;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** 确认版本化知识目录能解析，并且同一通用片段会按 Profile 拆成受限向量文档。 */
class ClasspathVersionedKnowledgeCatalogTest {

    @Test
    void loadsImmutableRevisionsAndExplodesProfileScopes() {
        var catalog = new ClasspathVersionedKnowledgeCatalog(
                JsonMapper.builder().findAndAddModules().build(),
                new ClassPathResource("knowledge/scheduled-miss-v1.json"));

        var revisions = catalog.load();

        assertThat(revisions).extracting(KnowledgeCatalogRevision::revisionKey)
                .containsExactly(
                        "scheduled-miss-service-playbook@1.0.0",
                        "icm-v1-scheduled-miss-trigger-guide@1.0.0",
                        "icm-v2-scheduled-miss-trigger-guide@1.0.0");
        assertThat(revisions.getFirst().chunks()).hasSize(12);
        assertThat(revisions.getFirst().chunks())
                .extracting(chunk -> chunk.profileId())
                .contains("demo-icm-v1", "demo-icm-v2", "demo-icm-v2-secure");
        assertThat(revisions).allSatisfy(revision ->
                assertThat(revision.checksum()).hasSize(64));
    }
}
