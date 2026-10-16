package io.cardioops.infrastructure.persistence;

import io.cardioops.application.integrationevent.IntegrationEventCommand;
import io.cardioops.application.integrationevent.IntegrationEventIntakeUseCase;
import io.cardioops.application.integrationevent.IntegrationIntakeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 给进门用例套一层本地事务。两道幂等写在里面那个用例里，这一层只保证它们一起提交，或者一起回滚。
 *
 * <p>Inbox 要是先提交、后面的投影又失败，Kafka 再投时第一道门会说见过这个 eventId，事情却没办成。
 * 所以事务包住整个 consume。异常继续往外抛，让 Kafka 去重试。这里不吞，也不在回滚之后假装成功。</p>
 *
 * <p>
 * 用例要能在没有数据库的测试里直接跑。事务是落地方式。套在外面，回滚边界仍然是整段 consume。</p>
 */
public final class TransactionalIntegrationEventIntakeUseCase implements IntegrationEventIntakeUseCase {

    private static final Logger log = LoggerFactory.getLogger(TransactionalIntegrationEventIntakeUseCase.class);

    private final IntegrationEventIntakeUseCase delegate;
    private final TransactionTemplate transactionTemplate;

    public TransactionalIntegrationEventIntakeUseCase(
            IntegrationEventIntakeUseCase delegate,
            TransactionTemplate transactionTemplate
    ) {
        this.delegate = delegate;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public IntegrationIntakeResult consume(IntegrationEventCommand command) {
        log.debug(
                "integration intake transaction started eventId={} traceId={} businessKey={}",
                command.eventId(), command.traceId(), command.businessKey());
        try {
            var result = transactionTemplate.execute(status -> delegate.consume(command));
            log.debug(
                    "integration intake transaction committed eventId={} traceId={} status={}",
                    command.eventId(), command.traceId(), result == null ? null : result.status());
            return result;
        } catch (RuntimeException exception) {
            // 走到这里，Inbox 和 Intake 已经一起回滚。异常还得继续抛，Kafka 才会重试。
            log.warn(
                    "integration intake transaction rolled_back eventId={} traceId={} businessKey={} errorType={}",
                    command.eventId(), command.traceId(), command.businessKey(),
                    exception.getClass().getSimpleName());
            throw exception;
        }
    }
}
