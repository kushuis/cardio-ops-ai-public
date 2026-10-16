package io.cardioops.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 后台进程入口。消费事件、领任务、跑调查都在这里。它不开面向用户的业务 API，只留受保护的监控和手动巡检。
 */

@SpringBootApplication(scanBasePackages = "io.cardioops")
public class CardioOpsWorkerApplication {

    public static void main(String[] args) {
        var application = new SpringApplication(CardioOpsWorkerApplication.class);
        // Worker 仍是独立进程；Servlet 只用于健康检查、Prometheus 和受保护的巡检入口。
        application.setWebApplicationType(WebApplicationType.SERVLET);
        application.run(args);
    }
}
