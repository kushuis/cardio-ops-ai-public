package io.cardioops.infrastructure.integration.lowbattery;

import io.cardioops.application.lowbattery.LowBatteryEvidenceScope;
import io.cardioops.application.lowbattery.LowBatteryEvidenceSource;
import io.cardioops.domain.device.DeviceDescriptor;
import io.cardioops.domain.lowbattery.LowBatteryContext;
import io.cardioops.domain.lowbattery.LowBatteryTransmissionObservation;
import io.cardioops.domain.scheduledmiss.GatewayStatusSnapshot;
import io.cardioops.infrastructure.integration.scheduledmiss.DevicePlatformScheduledMissApiProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Instant;
import java.util.List;

/**
 * 显式配置 transport=http 时使用的低电量只读适配器。
 *
 * <p>它不是 MCP 失败后的自动降级路径，只用于兼容迁移期配置。</p>
 */
public final class HttpDevicePlatformLowBatteryEvidenceClient implements LowBatteryEvidenceSource {
    private static final String TRACE_HEADER = "X-Trace-Id";
    private final RestClient client;

    public HttpDevicePlatformLowBatteryEvidenceClient(DevicePlatformScheduledMissApiProperties properties) {
        var httpClient = HttpClient.newBuilder().connectTimeout(properties.timeout()).build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.timeout());
        client = RestClient.builder().baseUrl(properties.baseUrl()).requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.serviceToken()).build();
    }

    @Override
    public LowBatteryContext getContext(LowBatteryEvidenceScope scope, String traceId) {
        var value = get("/internal/v1/cardio-ops/low-battery/{ref}/context", scope, traceId,
                DevicePlatformLowBatteryContext.class);
        requireDevice(scope, value.deviceRef());
        return new LowBatteryContext(value.evidenceRef(), value.deviceRef(), value.device(),
                value.observedAt(), value.batteryMessage());
    }

    @Override
    public List<LowBatteryTransmissionObservation> listRecentTransmissions(
            LowBatteryEvidenceScope scope, String traceId) {
        List<RawTransmission> values = get(
                "/internal/v1/cardio-ops/low-battery/{ref}/transmissions", scope, traceId,
                new ParameterizedTypeReference<>() { });
        return values.stream().map(value -> new LowBatteryTransmissionObservation(
                value.evidenceRef(), value.channel(), value.completedAt(),
                value.totalEventCount() == null ? 0 : value.totalEventCount(),
                value.receivedEventCount() == null ? 0 : value.receivedEventCount())).toList();
    }

    @Override
    public GatewayStatusSnapshot getCurrentGateway(LowBatteryEvidenceScope scope, String traceId) {
        return get("/internal/v1/cardio-ops/low-battery/{ref}/gateway/current", scope, traceId,
                GatewayStatusSnapshot.class);
    }

    private <T> T get(String path, LowBatteryEvidenceScope scope, String traceId, Class<T> type) {
        T value = client.get().uri(path, scope.correlationRef()).header(TRACE_HEADER, traceId)
                .retrieve().body(type);
        if (value == null) throw new IllegalStateException("DEVICE_PLATFORM_EMPTY_RESPONSE");
        return value;
    }

    private <T> T get(String path, LowBatteryEvidenceScope scope, String traceId,
                      ParameterizedTypeReference<T> type) {
        T value = client.get().uri(path, scope.correlationRef()).header(TRACE_HEADER, traceId)
                .retrieve().body(type);
        if (value == null) throw new IllegalStateException("DEVICE_PLATFORM_EMPTY_RESPONSE");
        return value;
    }

    private static void requireDevice(LowBatteryEvidenceScope scope, String returnedDevice) {
        if (!scope.deviceRef().equals(returnedDevice)) {
            throw new IllegalStateException("DEVICE_PLATFORM_SCOPE_MISMATCH");
        }
    }

    private record DevicePlatformLowBatteryContext(String evidenceRef, String deviceRef,
            DeviceDescriptor device, Instant observedAt, String batteryMessage) { }

    /** 完整接收 Admin DTO，再显式收窄为低电量领域对象。 */
    private record RawTransmission(String evidenceRef, String channel, Integer rawTriggerType,
            Instant startedAt, Instant completedAt, Integer totalEventCount,
            Integer receivedEventCount, Instant deviceReportedNextBroadcastAt) { }
}
