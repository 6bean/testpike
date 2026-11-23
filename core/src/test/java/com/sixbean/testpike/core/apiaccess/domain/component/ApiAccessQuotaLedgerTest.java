package com.sixbean.testpike.core.apiaccess.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.config.domain.component.SystemConfigReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-caller budget of the pass-through door, including the branch that only ever shows up when
 * Redis is gone.
 *
 * <p>Posture, stated because the last rule of this project is that a request path is never faked:
 * the ledger runs against the real Redis the suite already uses, so the counting, the window expiry
 * and the refusal wording are exercised as shipped. The one branch that cannot be produced by a live
 * store - the store being unreachable - is produced by pointing a second template at a port where
 * nothing listens. That is a genuinely unreachable counter store, not a stub pretending to be one;
 * an in-memory map would have proven nothing here, since the whole claim is about transport failure.
 *
 * <p>Why this file exists: the fail-closed behaviour was attested only by the class comment
 * ("when the counter store itself is unreachable the door refuses rather than running without a
 * budget"). A security default that no test can go red on is a comment, not an invariant - a future
 * refactor that swaps the catch for a permissive default would have passed the whole suite.
 */
@SpringBootTest(classes = CoreApplication.class)
class ApiAccessQuotaLedgerTest {

    /** Port where nothing listens; the connection attempt fails for real. */
    private static final int UNREACHABLE_PORT = 6399;

    @Autowired
    private StringRedisTemplate redis;

    private ConfigCacheHolder configCache;
    private ApiAccessQuotaLedger ledger;
    private String bucket;

    @BeforeEach
    void setUp() {
        configCache = new ConfigCacheHolder();
        ledger = new ApiAccessQuotaLedger(redis, new SystemConfigReader(configCache));
        bucket = "operator:unit-" + System.nanoTime();
    }

    @AfterEach
    void cleanUp() {
        redis.delete(ApiAccessQuotaLedger.KEY_PREFIX + bucket);
    }

    @Test
    void claimsUpToTheCeilingThenRefusesNamingTheKeyAndWhereToChangeIt() {
        configCache.put(ApiAccessQuotaLedger.KEY_MAX_CALLS, "2");
        configCache.put(ApiAccessQuotaLedger.KEY_WINDOW_SECONDS, "60");

        assertNull(ledger.tryClaim(bucket), "the first call is inside the budget");
        assertNull(ledger.tryClaim(bucket), "the second call is the last allowed one");

        String refusal = ledger.tryClaim(bucket);
        assertNotNull(refusal, "the third call must be refused");
        assertTrue(refusal.contains("2"), "the refusal states the ceiling: " + refusal);
        assertTrue(refusal.contains(ApiAccessQuotaLedger.KEY_MAX_CALLS),
                "a refusal that does not name the parameter leaves the operator hunting: " + refusal);
        assertTrue(refusal.contains("/configs"),
                "the refusal has to say where the ceiling lives: " + refusal);
    }

    @Test
    void theWindowStartsCountingFromTheFirstCall() {
        configCache.put(ApiAccessQuotaLedger.KEY_MAX_CALLS, "5");
        configCache.put(ApiAccessQuotaLedger.KEY_WINDOW_SECONDS, "40");

        ledger.tryClaim(bucket);
        Long ttlAfterFirst = redis.getExpire(ApiAccessQuotaLedger.KEY_PREFIX + bucket, TimeUnit.SECONDS);
        assertNotNull(ttlAfterFirst);
        assertTrue(ttlAfterFirst > 0 && ttlAfterFirst <= 40,
                "the first claim arms the window: ttl=" + ttlAfterFirst);

        ledger.tryClaim(bucket);
        Long ttlAfterSecond = redis.getExpire(ApiAccessQuotaLedger.KEY_PREFIX + bucket, TimeUnit.SECONDS);
        assertNotNull(ttlAfterSecond);
        assertTrue(ttlAfterSecond <= ttlAfterFirst,
                "a later claim must not stretch the window it is counted in");
    }

    @Test
    void absentUnparsableOrNonPositiveCeilingsFallBackToTheDocumentedDefault() {
        assertEquals(ApiAccessQuotaLedger.DEFAULT_MAX_CALLS, ledger.maxCalls(),
                "with no key at all the shipped default is the ceiling");

        configCache.put(ApiAccessQuotaLedger.KEY_MAX_CALLS, "not-a-number");
        assertEquals(ApiAccessQuotaLedger.DEFAULT_MAX_CALLS, ledger.maxCalls(),
                "an unparsable value cannot silently disable the budget");

        configCache.put(ApiAccessQuotaLedger.KEY_MAX_CALLS, "0");
        assertEquals(ApiAccessQuotaLedger.DEFAULT_MAX_CALLS, ledger.maxCalls(),
                "zero is not a way to switch the ceiling off from the settings page");

        configCache.put(ApiAccessQuotaLedger.KEY_MAX_CALLS, " -3 ");
        assertEquals(ApiAccessQuotaLedger.DEFAULT_MAX_CALLS, ledger.maxCalls(),
                "a negative ceiling falls back instead of trusting the input");
    }

    @Test
    void anUnreachableCounterStoreClosesTheDoorInsteadOfRunningUnbudgeted() {
        LettuceConnectionFactory dead = new LettuceConnectionFactory("127.0.0.1", UNREACHABLE_PORT);
        dead.afterPropertiesSet();
        StringRedisTemplate blind = new StringRedisTemplate();
        blind.setConnectionFactory(dead);
        blind.afterPropertiesSet();
        try {
            ApiAccessQuotaLedger offline =
                    new ApiAccessQuotaLedger(blind, new SystemConfigReader(configCache));
            configCache.put(ApiAccessQuotaLedger.KEY_MAX_CALLS, "5");

            String refusal = offline.tryClaim(bucket);
            assertNotNull(refusal, "with no ledger there is no budget, so the call is refused");
            assertTrue(refusal.contains("Redis"), "the answer names what is broken: " + refusal);
            assertTrue(refusal.contains("不可用") || refusal.toLowerCase().contains("unavailable"),
                    "and says the door is closed rather than degraded quietly: " + refusal);
        } finally {
            dead.destroy();
        }
    }
}
