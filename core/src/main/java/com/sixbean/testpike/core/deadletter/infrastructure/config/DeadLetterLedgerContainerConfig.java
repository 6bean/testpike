package com.sixbean.testpike.core.deadletter.infrastructure.config;

import com.sixbean.testpike.common.mq.MqDeadLetterTopology;
import com.sixbean.testpike.core.deadletter.domain.component.DeadLetterEgressListener;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Wires one manual-ack listener container per dead-letter queue .
 * <p>Why programmatic containers instead of {@code @RabbitListener}: there is no
 * consumer-queue header to route on (spring-amqp 3.2 does not expose one), so the queue
 * identity is bound per container at wiring time; the egress surface comes from the
 * single-source topology list in common — 16 queues by live scan, not the eleven from
 * the October stock count.
 * <p>The containers are returned as a collection bean so the context manages each one's
 * lifecycle; {@code autoStartup} follows the egress gate property and stays false until
 * the stock-migration hard gate clears. Ack mode is MANUAL because the book-then-ack
 * rule lives in {@code DeadLetterEgressListener}, not in the container.
 */
@Configuration
@EnableConfigurationProperties(DeadLetterLedgerContainerProperties.class)
public class DeadLetterLedgerContainerConfig {

    private final ConnectionFactory connectionFactory;
    private final DeadLetterLedgerContainerProperties properties;
    private final DeadLetterEgressListener egressListener;

    public DeadLetterLedgerContainerConfig(ConnectionFactory connectionFactory,
                                           DeadLetterLedgerContainerProperties properties,
                                           DeadLetterEgressListener egressListener) {
        this.connectionFactory = connectionFactory;
        this.properties = properties;
        this.egressListener = egressListener;
    }

    /** The single-source egress surface: every business queue's dead-letter queue. */
    public static List<String> egressDlqNames() {
        return MqDeadLetterTopology.deadLetterQueueNames();
    }

    /** Acknowledgement mode every egress container must run with. */
    public static AcknowledgeMode acknowledgeMode() {
        return AcknowledgeMode.MANUAL;
    }

    /** Collection bean: the context starts/stops each container as its own component. */
    @Bean
    public List<SimpleMessageListenerContainer> deadLetterEgressContainers() {
        return containers();
    }

    /**
     * The ledger declares its own egress surface. A freshly created vhost (chain slots,
     * new installs) only carries queues of services that already ran there; without this
     * declarable set every container whose producer never started sits in a permanent
     * passive-declare 404 storm and the egress gate opens degraded (live scan on
     * /tp-slot4: 14/16 queues missing). Durable + argument-free keeps this declaration
     * byte-identical to the producer side ({@code QueueBuilder.durable(dlq).build()}),
     * so vhosts shared by both paths never hit a PRECONDITION_FAILED property conflict.
     */
    @Bean
    public Declarables deadLetterEgressQueueDeclarables() {
        List<Declarable> queues = egressDlqNames().stream()
                .map(name -> (Declarable) new Queue(name, true))
                .toList();
        return new Declarables(queues);
    }

    /**
     * Gate handover bean: the collection bean above is not driven per element by the
     * context, so this lifecycle participant starts/stops the containers when and only
     * when the stock-migration gate flag is open.
     */
    @Bean
    public DeadLetterEgressLifecycle deadLetterEgressLifecycle(
            List<SimpleMessageListenerContainer> deadLetterEgressContainers) {
        return new DeadLetterEgressLifecycle(deadLetterEgressContainers, properties);
    }

    /** Container build, kept package-visible for the wiring tests. */
    List<SimpleMessageListenerContainer> containers() {
        List<SimpleMessageListenerContainer> built = new ArrayList<>();
        for (String dlqName : egressDlqNames()) {
            SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
            container.setQueueNames(dlqName);
            container.setAcknowledgeMode(acknowledgeMode());
            container.setMessageListener(egressListener.forQueue(dlqName));
            container.setAutoStartup(properties.isEnabled());
            container.setConcurrentConsumers(1);
            container.setPrefetchCount(1);
            built.add(container);
        }
        return built;
    }
}
