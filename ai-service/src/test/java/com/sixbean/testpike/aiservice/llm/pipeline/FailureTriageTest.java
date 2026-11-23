package com.sixbean.testpike.aiservice.llm.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for the failure triage single-source piece :
 * a runtime tool failure is typed into one of three buckets — transient
 * environment, stale expectation, or genuine product fault — by word family,
 * the normalized error form collapses digits and keeps only the first line so
 * two occurrences of the same fault hash identically, and an unknown error
 * never disappears into a silent default: it lands in the genuine bucket.
 */
class FailureTriageTest {

    @Test
    void classifiesTransientEnvironmentWords() {
        assertEquals(FailureTriage.ENVIRONMENT,
                FailureTriage.classify("java.net.SocketTimeoutException: read timed out"));
        assertEquals(FailureTriage.ENVIRONMENT,
                FailureTriage.classify("Connection refused to host 10.0.0.1:8083"));
        assertEquals(FailureTriage.ENVIRONMENT,
                FailureTriage.classify("HTTP 503 Service Unavailable from upstream"));
    }

    @Test
    void classifiesStaleExpectationWords() {
        assertEquals(FailureTriage.STALE_EXPECTATION,
                FailureTriage.classify("Unknown field 'case_state' in response schema"));
        assertEquals(FailureTriage.STALE_EXPECTATION,
                FailureTriage.classify("HTTP 404: endpoint not found"));
    }

    @Test
    void unknownAndValidationErrorsFallIntoTheGenuineBucket() {
        assertEquals(FailureTriage.GENUINE_FAILURE,
                FailureTriage.classify("HTTP 400: priority must be one of P0..P3"));
        assertEquals(FailureTriage.GENUINE_FAILURE,
                FailureTriage.classify("完全陌生的报错形态"));
        assertEquals(FailureTriage.GENUINE_FAILURE, FailureTriage.classify(null));
    }

    @Test
    void normalizedFormCollapsesDigitsAndKeepsFirstLine() {
        assertEquals("http # from upstream gateway on retry",
                FailureTriage.normalizeForm("HTTP 429 from upstream gateway on retry\nstacktrace line 1\nline 2"));
        // the same fault with different counts hashes the same
        assertEquals(FailureTriage.normalizeForm("row 3 rejected at step 17"),
                FailureTriage.normalizeForm("row 91 rejected at step 4"));
    }

    @Test
    void fingerprintBindsToolAndErrorForm() {
        String a = FailureTriage.fingerprint("query_cases", "read timed out after 30 seconds");
        String b = FailureTriage.fingerprint("query_cases", "read timed out after 5 seconds");
        String c = FailureTriage.fingerprint("query_defects", "read timed out after 30 seconds");
        assertEquals(a, b, "same tool + same normalized fault = same fingerprint");
        assertNotEquals(a, c, "a different tool is never the same fingerprint");
        assertTrue(a.startsWith("query_cases|"),
                "fingerprint carries the tool name for traceability");
    }
}
