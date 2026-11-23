package com.sixbean.testpike.core.deadletter.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.GetResponse;
import com.rabbitmq.client.LongString;
import com.rabbitmq.client.Channel;
import com.sixbean.testpike.common.mq.MqDeadLetterTopology;
import com.sixbean.testpike.core.deadletter.domain.component.DeadLetterBooker;
import com.sixbean.testpike.core.deadletter.domain.component.DeadLetterFailureClassifier;
import com.sixbean.testpike.core.deadletter.domain.component.DeadLetterMessageDecoder;
import com.sixbean.testpike.core.deadletter.domain.service.DeadLetterLedgerService;
import com.sixbean.testpike.core.deadletter.domain.service.impl.DeadLetterLedgerServiceImpl;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.TimeoutException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One-off stock-migration tool for the DLQ hard gate (AJ batch ,
 * on the product surface).
 * <p>Why the tool exists: hanging an ack-ing listener on a DLQ that still holds
 * stock would drain those messages into the ledger AND delete them from the
 * broker in one pass; if the booking half ever failed mid-run the stock would
 * be gone. So the gate is: pre-book the stock first (peek, never ack), prove a
 * per-queue reconciliation diff of zero, and only then let a human flip
 * {@code tp.mq.deadletter.egress.enabled}. Un-acked peeks return to the queue
 * when the channel closes, so this run is read-only on the broker.
 * <p>Booking goes through {@link DeadLetterBooker} — the identical decode →
 * classify → assemble path the live listeners use, so fingerprints agree and
 * later listener re-booking lands as an idempotent duplicate (R40 single source,
 * no second copy of the derivation).
 */
public final class DeadLetterStockMigrator {

    private final Connection connection;
    private final DeadLetterBooker booker;

    public DeadLetterStockMigrator(Connection connection, DeadLetterBooker booker) {
        this.connection = connection;
        this.booker = booker;
    }

    /** Peek one DLQ end to end and return its reconciliation row. */
    public DeadLetterStockTally migrateQueue(String deadLetterQueueName) throws IOException, TimeoutException {
        Channel channel = connection.createChannel();
        int snapshot;
        int peeked = 0;
        int bookedNew = 0;
        int bookedDuplicate = 0;
        int failures = 0;
        List<String> failureSamples = new ArrayList<>();
        try {
            snapshot = (int) channel.queueDeclarePassive(deadLetterQueueName).getMessageCount();
            while (true) {
                GetResponse response = channel.basicGet(deadLetterQueueName, false);
                if (response == null) {
                    break;
                }
                peeked++;
                try {
                    Optional<Long> booked = booker.book(deadLetterQueueName, toMessage(response));
                    if (booked.isPresent()) {
                        bookedNew++;
                    } else {
                        bookedDuplicate++;
                    }
                } catch (Exception bookingFailure) {
                    // Honest red: the row is missing, the message stays on the broker.
                    failures++;
                    if (failureSamples.size() < 5) {
                        String text = bookingFailure.getClass().getSimpleName() + ": "
                                + bookingFailure.getMessage();
                        failureSamples.add(text.length() > 400 ? text.substring(0, 400) : text);
                    }
                }
            }
        } finally {
            // Closing without ever acking puts every peeked message back on the queue.
            channel.close();
        }
        int depthAfter;
        try (Channel verifyChannel = connection.createChannel()) {
            depthAfter = (int) verifyChannel.queueDeclarePassive(deadLetterQueueName).getMessageCount();
        }
        return new DeadLetterStockTally(deadLetterQueueName, snapshot, peeked,
                bookedNew, bookedDuplicate, failures, List.copyOf(failureSamples), depthAfter);
    }

    /** Run the migration across the full single-source DLQ surface. */
    public List<DeadLetterStockTally> migrateAll() throws IOException, TimeoutException {
        List<DeadLetterStockTally> tallies = new ArrayList<>();
        for (String dlqName : MqDeadLetterTopology.deadLetterQueueNames()) {
            tallies.add(migrateQueue(dlqName));
        }
        return tallies;
    }

    /**
     * Convert a raw basic.get response into the same spring-amqp {@link Message}
     * shape the listener container delivers, so the decoder sees one input form.
     * LongString header values (the raw client type) are normalised to String to
     * keep x-death JSON identical to what the listener path would store.
     */
    static Message toMessage(GetResponse response) {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(response.getProps().getMessageId());
        properties.setDeliveryTag(response.getEnvelope().getDeliveryTag());
        properties.setRedelivered(response.getEnvelope().isRedeliver());
        properties.setReceivedExchange(response.getEnvelope().getExchange());
        properties.setReceivedRoutingKey(response.getEnvelope().getRoutingKey());
        properties.setHeaders(normalizeHeaders(response.getProps().getHeaders()));
        return new Message(response.getBody() == null ? new byte[0] : response.getBody(), properties);
    }

    private static Map<String, Object> normalizeHeaders(Map<String, Object> raw) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        if (raw == null) {
            return normalized;
        }
        raw.forEach((key, value) -> normalized.put(key, normalizeValue(value)));
        return normalized;
    }

    private static Object normalizeValue(Object value) {
        if (value instanceof LongString longString) {
            return longString.toString();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(String.valueOf(k), normalizeValue(v)));
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(normalizeValue(item)));
            return copy;
        }
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return value;
    }

    /** Shared environment wiring for callers (main and the live gate test): one assembly path. */
    public static DeadLetterBooker bookerFromEnvironment() {
        return new DeadLetterBooker(new DeadLetterMessageDecoder(),
                new DeadLetterFailureClassifier(), ledgerServiceFromEnvironment());
    }

    /** Wire the ledger service from the install env (DB_URL / DB_USERNAME / DB_PASSWORD). */
    public static DeadLetterLedgerService ledgerServiceFromEnvironment() {
        String url = requireEnv("DB_URL");
        String username = requireEnv("DB_USERNAME");
        String password = requireEnv("DB_PASSWORD");
        // A small fixed pool, deliberately tiny: the dev machine's PostgreSQL slots are
        // mostly held by the running services, and an un-pooled per-statement driver
        // connection exhausted them mid-run (CannotGetJdbcConnection under the live gate,
        // live scan 2026-11-06). Pool max 3 with a generous checkout timeout means the
        // tool queues behind itself, never behind a thousand sockets.
        com.zaxxer.hikari.HikariConfig config = new com.zaxxer.hikari.HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(3);
        config.setConnectionTimeout(60_000);
        config.setPoolName("dlq-stock-migration");
        return new DeadLetterLedgerServiceImpl(
                new NamedParameterJdbcTemplate(new com.zaxxer.hikari.HikariDataSource(config)));
    }

    /** Open the AMQP connection from the install env (RABBITMQ_* / MQ_VHOST). */
    public static Connection connectFromEnvironment() throws IOException, TimeoutException {
        com.rabbitmq.client.ConnectionFactory factory = new com.rabbitmq.client.ConnectionFactory();
        factory.setHost(requireEnv("RABBITMQ_HOST"));
        factory.setPort(Integer.parseInt(System.getenv().getOrDefault("RABBITMQ_PORT", "5672")));
        factory.setUsername(requireEnv("RABBITMQ_USERNAME"));
        factory.setPassword(System.getenv().getOrDefault("RABBITMQ_PASSWORD", ""));
        factory.setVirtualHost(System.getenv().getOrDefault("MQ_VHOST", "/"));
        return factory.newConnection();
    }

    private static String requireEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("install env variable " + key + " is not set");
        }
        return value;
    }

    /** Write the machine-readable reconciliation report for the audit trail. */
    public static void writeReport(Path reportPath, List<DeadLetterStockTally> tallies) throws IOException {
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (DeadLetterStockTally tally : tallies) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("queue", tally.queueName());
            row.put("snapshot", tally.snapshotCount());
            row.put("peeked", tally.peekedCount());
            row.put("booked_new", tally.bookedNew());
            row.put("booked_duplicate", tally.bookedDuplicate());
            row.put("booking_failures", tally.bookingFailures());
            row.put("failure_samples", tally.failureSamples());
            row.put("depth_after", tally.depthAfter());
            row.put("diff", tally.diff());
            row.put("cleared", tally.isCleared());
            rows.add(row);
        }
        mapper.writeValue(reportPath.toFile(), rows);
    }

    /**
     * Fixture entry point: run against the live broker described by the loaded
     * install env, print the per-queue lines, exit 0 only when every queue
     * cleared the gate.
     */
    public static void main(String[] args) throws IOException, TimeoutException {
        Path reportPath = Path.of(args.length > 0 ? args[0]
                : System.getenv().getOrDefault("TP_DLQ_STOCK_REPORT", "/tmp/testpike-dlq-stock-migration.json"));
        try (Connection connection = connectFromEnvironment()) {
            List<DeadLetterStockTally> tallies =
                    new DeadLetterStockMigrator(connection, bookerFromEnvironment()).migrateAll();
            writeReport(reportPath, tallies);
            boolean allCleared = true;
            for (DeadLetterStockTally tally : tallies) {
                allCleared &= tally.isCleared();
                System.out.printf("%-32s snapshot=%d peeked=%d new=%d dup=%d fail=%d after=%d diff=%d %s%n",
                        tally.queueName(), tally.snapshotCount(), tally.peekedCount(), tally.bookedNew(),
                        tally.bookedDuplicate(), tally.bookingFailures(), tally.depthAfter(), tally.diff(),
                        tally.isCleared() ? "CLEARED" : "GATE-RED");
            }
            System.out.println("report=" + reportPath);
            System.exit(allCleared ? 0 : 1);
        }
    }
}
