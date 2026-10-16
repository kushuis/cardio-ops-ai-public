package io.cardioops.worker.reconciliation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 手动触发一次扫描的内部入口。令牌没配就拒绝，不把没保护的接口留在那里。配了令牌也只是调用那次只读扫描，不在这里补数据。
 *
 *
 * 空令牌如果放行，任何能打到 Worker 的人都能触发扫描。没配好就关掉这个入口。
 */

@RestController
@RequestMapping("/internal/v1/operations/reconciliation")
@ConditionalOnExpression("'${cardio-ops.runtime.role:}' == 'worker' && ${cardio-ops.integration.enabled:false}")
public final class ReconciliationController {

    private final ConsistencyReconciliationService service;
    private final ReconciliationProperties properties;

    public ReconciliationController(
            ConsistencyReconciliationService service,
            ReconciliationProperties properties
    ) {
        this.service = service;
        this.properties = properties;
    }

    @PostMapping
    public ReconciliationReport run(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        String configured = properties.serviceToken();
        if (configured.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "reconciliation service token is not configured");
        }
        String actual = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : "";
        if (!MessageDigest.isEqual(configured.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid service token");
        }
        return service.scan();
    }
}
