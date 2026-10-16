#!/usr/bin/env python3
"""生成 CardioOps V2 集成事件，用于没有真实设备时验证 Incident 投影。"""

from __future__ import annotations

import argparse
import json
import sys
import uuid
from datetime import datetime, timezone
from typing import Any


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def device(family: str = "ICM", model_code: str = "DEMO_ICM_V2_SECURE", firmware_number: int = 17408) -> dict[str, Any]:
    return {
        "family": family,
        "modelCode": model_code,
        "firmwareVersion": "1.2.0",
        "firmwareVersionNumber": firmware_number,
    }


def envelope(
    event_type: str,
    payload: dict[str, Any],
    correlation_ref: str,
    event_id: str | None = None,
) -> dict[str, Any]:
    return {
        "eventId": event_id or str(uuid.uuid4()),
        "schemaVersion": 2,
        "eventType": event_type,
        "traceId": f"sim-{uuid.uuid4()}",
        "occurredAt": now_iso(),
        "correlationRef": correlation_ref,
        "payload": payload,
    }


def scenario(name: str, event_id: str | None = None) -> list[dict[str, Any]]:
    """只生成三种会跨系统进入 Kafka 的异常事件。

    正常传输是 Agent 复查时从 DevicePlatform 查到的证据，不再作为 Kafka 事件模拟。
    """
    common = {
        "deviceRef": "sim-device-001",
        "sourceRecordRef": f"sim-session-{name}-001",
        "device": device(),
    }
    monitoring_correlation = "MONITORING:sim-device-001:1788480000"
    expected_at = "2026-09-04T00:00:00Z"
    next_at = "2026-09-05T00:00:00Z"
    if name == "partial_transmission":
        payload = common | {
            "issueType": "TRANSMISSION_EVENTS_INCOMPLETE",
            "totalEventCount": 10,
            "receivedEventCount": 6,
        }
        return [envelope(
            "ISSUE_DETECTED", payload,
            "TRANSMISSION:sim-device-001:sim-session-partial_transmission-001", event_id,
        )]
    if name == "scheduled_missed":
        payload = common | {
            "issueType": "SCHEDULED_MISSED",
            # 上游由 icm_device_settings.deviceTime 结合业务日期及时区转换得到。
            "expectedTransmissionAt": expected_at,
            "nextTransmissionAt": next_at,
        }
        return [envelope("ISSUE_DETECTED", payload, monitoring_correlation, event_id)]
    if name == "low_battery":
        payload = common | {
            "issueType": "LOW_BATTERY",
            # 合成关联键保留生产协议形状，但 ID 不对应任何真实记录。
            "sourceRecordRef": "ICM_PATIENT_SERVICE:900001",
        }
        return [envelope(
            "ISSUE_DETECTED", payload,
            "BATTERY:sim-device-001:ICM_PATIENT_SERVICE:900001", event_id,
        )]
    if name == "duplicate_delivery":
        fixed_id = event_id or str(uuid.uuid4())
        original = scenario("scheduled_missed", fixed_id)[0]
        return [original, dict(original)]
    raise ValueError(f"unknown scenario: {name}")


def scheduled_missed_batch(count: int, device_prefix: str = "sim-load") -> list[dict[str, Any]]:
    """生成可并发投递的应传未传事件，用于接入链路压测。

    每条事件使用不同设备和 correlationRef，避免被 Incident 幂等键合并为同一案件。
    这里不模拟真实患者或设备，只验证 Kafka 消费、建案和 AgentRun 入队的吞吐能力。
    """
    if count <= 0:
        raise ValueError("count must be positive")
    events: list[dict[str, Any]] = []
    for index in range(1, count + 1):
        device_ref = f"{device_prefix}-{index:05d}"
        correlation_ref = f"MONITORING:{device_ref}:1788480000"
        payload = {
            "deviceRef": device_ref,
            "sourceRecordRef": f"{device_ref}-scheduled-missed",
            "device": device(),
            "issueType": "SCHEDULED_MISSED",
            # 与单条场景一致：预期时间来自上游设备设置，供下游生成 Incident 使用。
            "expectedTransmissionAt": "2026-09-04T00:00:00Z",
            "nextTransmissionAt": "2026-09-05T00:00:00Z",
        }
        events.append(envelope("ISSUE_DETECTED", payload, correlation_ref))
    return events


def main() -> int:
    parser = argparse.ArgumentParser(description="CardioOps remote-monitoring event simulator")
    parser.add_argument(
        "scenario",
        choices=[
            "partial_transmission", "scheduled_missed", "low_battery",
            "duplicate_delivery", "scheduled_missed_batch",
        ],
    )
    parser.add_argument("--event-id", help="固定 eventId，便于复现和幂等测试")
    parser.add_argument("--count", type=int, default=1,
                        help="scheduled_missed_batch 的事件数量，默认 1")
    parser.add_argument("--device-prefix", default="sim-load",
                        help="scheduled_missed_batch 的模拟设备前缀")
    args = parser.parse_args()
    if args.scenario == "scheduled_missed_batch":
        if args.event_id:
            parser.error("scheduled_missed_batch cannot use --event-id; every event must be unique")
        events = scheduled_missed_batch(args.count, args.device_prefix)
    else:
        if args.count != 1:
            parser.error("--count is only supported by scheduled_missed_batch")
        events = scenario(args.scenario, args.event_id)
    for event in events:
        print(json.dumps(event, ensure_ascii=False, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    sys.exit(main())
