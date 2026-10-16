package io.cardioops.infrastructure.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Topic、消费组和地址放在配置里。换环境不用改 Java 常量。
 */

@ConfigurationProperties(prefix = "cardio-ops.integration.kafka")
public class KafkaIntegrationProperties {

    /** Kafka 集群地址；本地为 localhost:9092，生产环境由部署配置注入。 */
    private String bootstrapServers;
    /** DevicePlatform Outbox Relay 发布、CardioOps Worker 消费的 V1 主 Topic。 */
    private String topic = KafkaTopicNames.INTEGRATION_EVENTS_V1;
    /** 主 Topic 消息重试耗尽后的死信 Topic，分区数必须与主 Topic 一致。 */
    private String dltTopic = KafkaTopicNames.INTEGRATION_EVENTS_DLT_V1;
    /** CardioOps Worker 消费组；同组实例分摊分区，而不是每台机器都重复执行业务。 */
    private String consumerGroup = "cardio-ops-worker-v1";

    public String getBootstrapServers() {
        return bootstrapServers;
    }

    public void setBootstrapServers(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getDltTopic() {
        return dltTopic;
    }

    public void setDltTopic(String dltTopic) {
        this.dltTopic = dltTopic;
    }

    public String getConsumerGroup() {
        return consumerGroup;
    }

    public void setConsumerGroup(String consumerGroup) {
        this.consumerGroup = consumerGroup;
    }
}
