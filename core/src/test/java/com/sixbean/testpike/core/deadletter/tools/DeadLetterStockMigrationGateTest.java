package com.sixbean.testpike.core.deadletter.tools;

import com.rabbitmq.client.Connection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p>Runs only when the operator sets {@code TP_DLQ_STOCK_MIGRATE=run}: it
 * peeks every dead-letter queue of the live topology, pre-books the stock into
 * tp_mq_dead_letter through the same single-source booker the listeners use,
 * and asserts a per-queue reconciliation diff of zero. Nothing is ever
 * acknowledged, so a failed run leaves the stock untouched on the broker.
 * Normal regression never executes this (the env var is unset).
 */
@EnabledIfEnvironmentVariable(named = "TP_DLQ_STOCK_MIGRATE", matches = "run")
class DeadLetterStockMigrationGateTest {

    @Test
    void migratesEveryDeadLetterQueueWithZeroDiff() throws Exception {
        Path report = Path.of(System.getenv()
                .getOrDefault("TP_DLQ_STOCK_REPORT", "/tmp/testpike-dlq-stock-migration.json"));
        List<DeadLetterStockTally> tallies;
        try (Connection connection = DeadLetterStockMigrator.connectFromEnvironment()) {
            tallies = new DeadLetterStockMigrator(connection,
                    DeadLetterStockMigrator.bookerFromEnvironment()).migrateAll();
        }
        DeadLetterStockMigrator.writeReport(report, tallies);
        for (DeadLetterStockTally tally : tallies) {
            assertTrue(tally.isCleared(),
                    "queue " + tally.queueName() + " failed the stock gate: " + tally);
        }
    }
}
