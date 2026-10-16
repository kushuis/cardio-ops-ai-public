package io.cardioops.infrastructure.integration.scheduledmiss;

import io.cardioops.application.scheduledmiss.DevicePlatformTransmissionObservation;
import io.cardioops.application.scheduledmiss.ScheduledMissEvidenceSource;
import io.cardioops.application.scheduledmiss.ScheduledMissEvidenceScope;
import io.cardioops.domain.device.DeviceDescriptor;
import io.cardioops.domain.scheduledmiss.GatewayConnectivity;
import io.cardioops.domain.scheduledmiss.GatewayStatusSnapshot;
import io.cardioops.domain.scheduledmiss.ScheduleChangeObservation;
import io.cardioops.domain.scheduledmiss.ScheduledMissContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Instant;
import java.util.List;

/**
 * 显式走 HTTP 时的只读查询。不连 DevicePlatform 的数据库。跨系统用对方发布的 correlationRef，本地 incidentId 不送去当对方的主键。
 * 范围由当前案件填，模型不能改。这不是 MCP 失败后的自动退路。知识检索也不在这条客户端里。
 */
public final class HttpDevicePlatformScheduledMissEvidenceClient implements ScheduledMissEvidenceSource {

    private static final String TRACE_HEADER = "X-Trace-Id";
    private static final Logger log = LoggerFactory.getLogger(HttpDevicePlatformScheduledMissEvidenceClient.class);
    private final RestClient restClient;

    public HttpDevicePlatformScheduledMissEvidenceClient(DevicePlatformScheduledMissApiProperties properties) {
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.timeout())
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.timeout());
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.serviceToken())
                .build();
    }

    @Override
    public ScheduledMissContext getScheduledMissContext(ScheduledMissEvidenceScope scope, String traceId) {
        DevicePlatformScheduledMissContext response = get(
                "/internal/v1/cardio-ops/scheduled-windows/{correlationRef}/context",
                scope, traceId, DevicePlatformScheduledMissContext.class);
        if (!scope.deviceRef().equals(response.deviceRef())) {
            throw new IllegalStateException("DEVICE_PLATFORM_SCOPE_MISMATCH");
        }
        // incidentId 是 CardioOps 本地 ID，不能要求 DevicePlatform 保存或回显它。
        return new ScheduledMissContext(
                response.evidenceRef(), scope.incidentId(), response.deviceRef(), response.device(),
                response.expectedTransmissionAt(), response.nextExpectedTransmissionAt(),
                response.gatewayAtExpected(), response.latestBatteryLow(), response.batteryObservedAt(),
                response.lastDeviceReportedBroadcastAt(), response.deviceBroadcastObservedAt());
    }

    @Override
    public List<DevicePlatformTransmissionObservation> queryTransmissionsInWindow(
            ScheduledMissEvidenceScope scope,
            String traceId
    ) {
        return get("/internal/v1/cardio-ops/scheduled-windows/{correlationRef}/transmissions",
                scope, traceId, new ParameterizedTypeReference<>() { });
    }

    @Override
    public GatewayStatusSnapshot getCurrentGatewaySnapshot(ScheduledMissEvidenceScope scope, String traceId) {
        return get("/internal/v1/cardio-ops/scheduled-windows/{correlationRef}/gateway/current",
                scope, traceId, GatewayStatusSnapshot.class);
    }

    @Override
    public List<ScheduleChangeObservation> queryScheduleChangeHistory(
            ScheduledMissEvidenceScope scope,
            String traceId
    ) {
        return get("/internal/v1/cardio-ops/scheduled-windows/{correlationRef}/schedule-changes",
                scope, traceId, new ParameterizedTypeReference<>() { });
    }

    private <T> T get(
            String path,
            ScheduledMissEvidenceScope scope,
            String traceId,
            Class<T> responseType
    ) {
        log.debug("device-platform evidence query started incidentId={} path={}", scope.incidentId(), path);
        T response = restClient.get()
                .uri(path, scope.correlationRef())
                .header(TRACE_HEADER, traceId)
                .retrieve()
                .body(responseType);
        if (response == null) {
            throw new IllegalStateException("DEVICE_PLATFORM_EMPTY_RESPONSE");
        }
        return response;
    }

    private <T> T get(
            String path,
            ScheduledMissEvidenceScope scope,
            String traceId,
            ParameterizedTypeReference<T> responseType
    ) {
        log.debug("device-platform evidence query started incidentId={} path={}", scope.incidentId(), path);
        T response = restClient.get()
                .uri(path, scope.correlationRef())
                .header(TRACE_HEADER, traceId)
                .retrieve()
                .body(responseType);
        if (response == null) {
            throw new IllegalStateException("DEVICE_PLATFORM_EMPTY_RESPONSE");
        }
        return response;
    }

    /** DevicePlatform 返回的上游事实，不错误地包含下游生成的 CardioOps incidentId。 */
    private record DevicePlatformScheduledMissContext(
            String evidenceRef,
            String deviceRef,
            DeviceDescriptor device,
            Instant expectedTransmissionAt,
            Instant nextExpectedTransmissionAt,
            GatewayConnectivity gatewayAtExpected,
            boolean latestBatteryLow,
            Instant batteryObservedAt,
            Instant lastDeviceReportedBroadcastAt,
            Instant deviceBroadcastObservedAt
    ) { }
}
