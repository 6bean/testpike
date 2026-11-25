package com.sixbean.testpike.core.session.domain.service.impl;

import org.springframework.test.context.TestPropertySource;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Pins the capture contract suites to a dedicated Redis logical database.
 *
 * <p>Root cause (family-B flake pin, 2026-11-26): a live executor process
 * polls the capture hand-off endpoint of the running core service, which
 * shares the main-slot Redis logical database with plain {@code @SpringBootTest}
 * runs. A fixture session enqueued by {@code open()} can therefore be claimed
 * cross-process mid-test, and the claim/report path rewrites its session key
 * with the full window TTL — reviving a key the TTL-expiry contract just
 * shortened to one second. The revival is timing-lottery, so the class flaked
 * red under load and green solo.
 *
 * <p>Fix = structural isolation, not assertion relaxation: every capture
 * contract class runs against logical database 13, which the slot plane never
 * allocates (slots take small indices, see scripts/slot_topology.py
 * redis_db_for) and no live executor reads. Real sessions in the main-slot
 * queue stay untouched.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
@TestPropertySource(properties = "spring.data.redis.database=13")
public @interface IsolatedCaptureRedis {
}
