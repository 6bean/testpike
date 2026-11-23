package com.sixbean.testpike.core.linkage.support;

import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Shared observation fixture for change-linkage bus suites: the JdbcTemplate
 * plus the AMQP trio that builds an {@link ObjectChangedEventQueueProbe}.
 * Suites used to re-declare the identical five-field wiring block one by one
 * (R40 second-consumer trigger), so the wiring lives here once and every bus
 * test autowires this single component instead.
 */
@Component
public class BusObservationFixture {

    public final JdbcTemplate jdbc;
    private final AmqpAdmin amqpAdmin;
    private final RabbitTemplate rabbitTemplate;
    private final MessageConverter converter;

    @Autowired
    public BusObservationFixture(JdbcTemplate jdbc, AmqpAdmin amqpAdmin,
                                 RabbitTemplate rabbitTemplate,
                                 MessageConverter caseEventMessageConverter) {
        this.jdbc = jdbc;
        this.amqpAdmin = amqpAdmin;
        this.rabbitTemplate = rabbitTemplate;
        this.converter = caseEventMessageConverter;
    }

    /** A probe on the real exchange with the project's JSON converter. */
    public ObjectChangedEventQueueProbe probe() {
        return new ObjectChangedEventQueueProbe(amqpAdmin, rabbitTemplate, converter);
    }
}
