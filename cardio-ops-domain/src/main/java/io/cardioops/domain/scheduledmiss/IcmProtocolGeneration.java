package io.cardioops.domain.scheduledmiss;

/**
 * ICM 蓝牙协议代际。同一个整数触发码，在 V1 和 V2 里意思可以不同。先由档案定代际，再翻译，不能让模型直接读原始整数。
 */
public enum IcmProtocolGeneration {
    /** 1A 当前使用的旧协议，磁铁=1、定时=2，复位广播已经废弃。 */
    V1,
    /** 1C/2A 当前使用的新协议，包含磁铁、事件、家庭监护定时和告警触发。 */
    V2
}
