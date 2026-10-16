package io.cardioops.application.deadletter;
/**
 * 按死信编号重放。调用方不能另交一段 JSON 来冒充当时的消息。
 */


public interface DeadLetterReplayUseCase {
    DeadLetterReplayResult replay(String deadLetterId);
}
