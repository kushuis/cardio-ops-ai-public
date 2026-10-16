package io.cardioops.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "cardio-ops.integration.enabled=false",
                "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"
        }
)
class CardioOpsApiApplicationTest {

    @Test
    void contextLoadsWithoutModelApiKeyOrKafkaBroker() {
    }
}
