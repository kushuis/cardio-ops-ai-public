package io.cardioops.api;

import io.cardioops.domain.agent.ToolCapability;
import io.cardioops.domain.device.DeviceDescriptor;
import io.cardioops.domain.device.DeviceFamily;
import io.cardioops.domain.device.InvestigationProfileResolver;
import io.cardioops.domain.device.ProfileResolutionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.Set;

/**
 * 给运营和排障看一台设备为什么落到这份档案、允许哪些工具。它只解释解析结果，不启动调查。
 */

@RestController
@RequestMapping("/api/v1/device-capabilities")
public class DeviceCapabilityController {

    private static final Logger log = LoggerFactory.getLogger(DeviceCapabilityController.class);

    private final InvestigationProfileResolver resolver;
    private final Clock clock;

    public DeviceCapabilityController(InvestigationProfileResolver resolver, Clock clock) {
        this.resolver = resolver;
        this.clock = clock;
    }

    @GetMapping("/resolve")
    public ResolutionResponse resolve(
            @RequestParam DeviceFamily family,
            @RequestParam String modelCode,
            @RequestParam String firmwareVersion,
            @RequestParam long firmwareVersionNumber
    ) {
        var result = resolver.resolve(
                new DeviceDescriptor(family, modelCode, firmwareVersion, firmwareVersionNumber),
                clock.instant());
        log.info(
                "device capability resolved family={} modelCode={} firmwareVersionNumber={} status={} "
                        + "profileId={} writeActionsAllowed={}",
                family, modelCode, firmwareVersionNumber, result.status(), result.profileId(),
                result.writeActionsAllowed());
        return new ResolutionResponse(
                result.status(), result.profileId(), result.allowedTools(), result.sopTags(),
                result.writeActionsAllowed(), result.reason());
    }

    /**
     * @param status Profile 是否唯一匹配，未知或冲突时前端必须明确提示运营人员
     * @param profileId 实际匹配的 Profile；未支持/冲突时为空
     * @param allowedTools 此设备能力允许的工具集合，不代表当前登录用户一定有权调用
     * @param sopTags 后续 SOP/RAG 检索范围标签
     * @param writeActionsAllowed Profile 层写动作开关；仍不能绕过鉴权、审批和 DevicePlatform 校验
     * @param reason 可展示、可审计的解析原因，避免能力路由成为黑盒
     */
    public record ResolutionResponse(
            ProfileResolutionStatus status,
            String profileId,
            Set<ToolCapability> allowedTools,
            Set<String> sopTags,
            boolean writeActionsAllowed,
            String reason
    ) {
    }
}
