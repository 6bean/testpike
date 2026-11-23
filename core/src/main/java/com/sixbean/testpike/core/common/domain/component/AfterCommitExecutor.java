package com.sixbean.testpike.core.common.domain.component;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Executor that runs after transaction commit (extracted from the afterCommit pattern in RabbitCaseEventPublisher for DRY).
 * <p>Semantics: if an active transaction exists, register an afterCommit callback so it runs only after commit and not on rollback, preventing false alerts.
 * If no active transaction exists, execute immediately; suitable for scenarios where side effects are allowed only after a database write succeeds.
 * (MQ event publishing, lock notifications, etc.), eliminating residual side effects after rollback.
 *
 */
@Component
public class AfterCommitExecutor {

    /**
     * Execute the action according to the current transaction context: register a post-commit callback inside a transaction (not triggered on rollback), or execute immediately outside a transaction.
     *
     * @param action side-effect action (it must catch its own exceptions and must not block the main flow).
     */
    public void execute(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
