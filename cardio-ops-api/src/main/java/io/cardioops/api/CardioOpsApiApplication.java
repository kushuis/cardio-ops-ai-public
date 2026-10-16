package io.cardioops.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * HTTP 进程入口。它接请求、返回结果，不在这里消费 Kafka。后台调查在 Worker 进程。
 */

@SpringBootApplication(scanBasePackages = "io.cardioops")
public class CardioOpsApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(CardioOpsApiApplication.class, args);
    }
}
