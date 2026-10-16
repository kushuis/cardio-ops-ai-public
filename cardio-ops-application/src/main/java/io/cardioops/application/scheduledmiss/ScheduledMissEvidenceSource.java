package io.cardioops.application.scheduledmiss;

import io.cardioops.domain.scheduledmiss.GatewayStatusSnapshot;
import io.cardioops.domain.scheduledmiss.ScheduleChangeObservation;
import io.cardioops.domain.scheduledmiss.ScheduledMissContext;

import java.util.List;

/**
 * 读 DevicePlatform 只读证据的口。模型只把 incidentId 交给工具，范围由当前案件填。知识检索不在这个口里。
 */
public interface ScheduledMissEvidenceSource {

    /** 读取本次应传未传的初始事实；找不到必须失败，不能合成默认上下文。 */
    ScheduledMissContext getScheduledMissContext(ScheduledMissEvidenceScope scope, String traceId);

    /** 查询该 Incident 应传时间至下次应传时间之间的有限传输批次。 */
    List<DevicePlatformTransmissionObservation> queryTransmissionsInWindow(ScheduledMissEvidenceScope scope, String traceId);

    /** 查询设备当前绑定网关的最新状态。 */
    GatewayStatusSnapshot getCurrentGatewaySnapshot(ScheduledMissEvidenceScope scope, String traceId);

    /** 查询该应传窗口附近、与广播时间有关的 Web 远程参数修改。 */
    List<ScheduleChangeObservation> queryScheduleChangeHistory(ScheduledMissEvidenceScope scope, String traceId);
}
