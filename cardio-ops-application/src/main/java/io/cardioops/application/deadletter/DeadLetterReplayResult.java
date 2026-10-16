package io.cardioops.application.deadletter;

/**
 * 重放的结果：这次真的重新发布了，还是之前已经重放过。调用方要能分清这两种，不要当成失败。
 */
public record DeadLetterReplayResult(String deadLetterId, String originalEventId, String status) {
}
