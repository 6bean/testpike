package com.sixbean.testpike.common.idempotent;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * <ol>
 * </ol>
 *
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {

    
    String key() default "";

    
    long ttlSeconds() default 24 * 60 * 60;

    /**
 * SpEL for the in-flight ({@code E:}) marker TTL in seconds, evaluated
 * against the same method-parameter context as {@link #key} (e.g.
 * {@code "#inflightTtlSeconds"}). Why: a crashed holder used to leave the
 * E: marker for the full done-window (24h), parking every redelivery in
 * that window as a duplicate ack . MQ consumers
 * size it to twice their consume lease so the marker self-heals shortly
 * after the lease ceiling; the done ({@code D:}) replay window always
 * stays {@link #ttlSeconds}. Blank / non-numeric / non-positive falls
 * back to {@link #ttlSeconds} — every existing annotation usage keeps
 * byte-identical behavior.
 */
    String inflightTtl() default "";
}
