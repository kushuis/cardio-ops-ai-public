package io.cardioops.api;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * API 进程上的执行器。网关检查的 SSE 不能占着请求线程把图跑完。
 */

@Configuration(proxyBeanMethods = false)
class ApiExecutionConfiguration {

    /**
     * 每次调查使用一个 Java 21 虚拟线程，避免 Graph 执行阻塞 Tomcat 请求线程。
     * 虚拟线程适合包含 HTTP、数据库等阻塞 I/O 的大量并发任务，但它不等于无限资源，
     * 后续仍需并发上限、超时和背压。
     */
    @Bean(destroyMethod = "close")
    ExecutorService investigationExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
