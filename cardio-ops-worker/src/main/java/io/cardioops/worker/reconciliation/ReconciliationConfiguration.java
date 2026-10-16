package io.cardioops.worker.reconciliation;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 巡检跟模型开关解开。模型关掉时仍然可以发现运行、结果和死信对不齐。它不因为巡检去改那些行。
 */

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(ReconciliationProperties.class)
public class ReconciliationConfiguration {
}
