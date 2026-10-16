package io.cardioops.application.agentrun;

/**
 * 协作式停下来的信号。租约没了，不代表这次调查业务失败。别人可能已经接走了，所以不能再 markFailed。
 */
public final class AgentRunLeaseLostException extends RuntimeException {

    public AgentRunLeaseLostException(String message) {
        super(message);
    }
}
