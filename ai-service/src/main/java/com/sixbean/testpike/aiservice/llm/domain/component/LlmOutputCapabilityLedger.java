package com.sixbean.testpike.aiservice.llm.domain.component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Capability ledger of schema-constrained decoding per model and request
 * form (V615). tp_llm_config.structured_output is one binary per row and
 * cannot express "this endpoint enforces the json_schema block only when no
 * tools ride the same request" — a live-probed class where the gateway
 * accepts the block and silently drops it under the function-calling shape.
 * <p>The generation chain observes the class at the enforcement-notice site
 * (a format-class verdict landing while the block was mounted proves
 * accept-without-enforce for the mounted form) and records it here; the
 * next schema-bearing call consults the row and steers into the form the
 * endpoint actually enforces (tool-free completion) instead of burning the
 * whole deliberation ring in a shape that can never converge.
 * <p>Storage posture: an in-process cache fronts the rows (bounded at
 * {@link #MAX_TRACKED_FORMS} entries — the model catalog is finite, the cap
 * only stops pathological keys; R57 no unbounded map), reads and writes are
 * fail-soft: a ledger door outage never blocks generation — the worst case
 * is re-learning the class on the next ring notice.
 */
@org.springframework.stereotype.Component
public class LlmOutputCapabilityLedger {

    /** Schema block rode alongside the function-calling tool list. */
    public static final String FORM_TOOLS_SCHEMA = "tools_schema";

    /** Schema block rode a plain completion (no tools on the wire). */
    public static final String FORM_SCHEMA_ONLY = "schema_only";

    /** Closed vocabulary of tracked wire forms (row-count bound). */
    public static final List<String> KNOWN_FORMS = List.of(FORM_TOOLS_SCHEMA, FORM_SCHEMA_ONLY);

    private static final Logger log =
            LoggerFactory.getLogger(LlmOutputCapabilityLedger.class);

    /** Cache bound: distinct model keys tracked in-process (catalog-finite). */
    static final int MAX_TRACKED_FORMS = 256;

    private static final String FIND_SQL =
            "SELECT enforced FROM tp_llm_output_capability"
                    + " WHERE model_name = :model AND call_form = :form";

    /** Promotion write-back of a live probe that proved enforcement (ticket-②). */
    static final String PROMOTE_SQL =
            "UPDATE tp_llm_output_capability SET enforced = 1,"
                    + " last_probed_at = now(), updated_at = now()"
                    + " WHERE model_name = :model AND call_form = :form";

    /** Timestamp-only refresh when a probe re-confirms the unenforced class. */
    static final String MARK_PROBED_SQL =
            "UPDATE tp_llm_output_capability"
                    + " SET last_probed_at = now(), updated_at = now()"
                    + " WHERE model_name = :model AND call_form = :form";

    /**
 * Due rows for the scheduled reprobe (ticket-② : unenforced rows
 * whose last probe fell out of the interval window (never probed counts
 * as due). Query shape is pinned by the ledger contract test.
 */
    public static final String FIND_STALE_SQL =
            "SELECT model_name, call_form FROM tp_llm_output_capability"
                    + " WHERE enforced = 0"
                    + "   AND (last_probed_at IS NULL"
                    + "        OR last_probed_at < now() + (:negativeHours || ' hours')::interval)";

    private static final String UPSERT_SQL =
            "INSERT INTO tp_llm_output_capability (model_name, call_form, enforced, hits, updated_at)"
                    + " VALUES (:model, :form, 0, 1, now())"
                    + " ON CONFLICT (model_name, call_form) DO UPDATE"
                    + " SET enforced = 0, hits = tp_llm_output_capability.hits + 1,"
                    + "     updated_at = now()";

    private final NamedParameterJdbcTemplate jdbc;

    /** model|form -> unenforced seen (cache authority even when the door is down). */
    private final Map<String, Boolean> cache = new ConcurrentHashMap<>();

    public LlmOutputCapabilityLedger(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** True when the (model, form) pair was observed to accept the schema without enforcing it. */
    public boolean isFormUnenforced(String model, String form) {
        if (model == null || form == null) {
            return false;
        }
        String key = cacheKey(model, form);
        Boolean known = cache.get(key);
        if (known != null) {
            return known;
        }
        boolean loaded = loadFromLedger(model, form);
        if (cache.size() < MAX_TRACKED_FORMS) {
            cache.put(key, loaded);
        }
        return loaded;
    }

    /**
     * Record one accepted-but-not-enforced observation for a mounted form
     * (cache first — the process keeps the truth even if the door is down —
     * then a fail-soft row upsert with the hit bump journaled on failure).
     */
    public void observeNotEnforced(String model, String form) {
        if (model == null || form == null) {
            return;
        }
        String key = cacheKey(model, form);
        boolean firstSighting = !Boolean.TRUE.equals(cache.get(key));
        if (firstSighting && cache.size() >= MAX_TRACKED_FORMS) {
            log.warn("Capability ledger cache reached its limit {}, observation for model {} form {} is persisted to the database only, not kept in cache",
                    MAX_TRACKED_FORMS, model, form);
        } else {
            cache.put(key, true);
        }
        if (firstSighting) {
            log.warn("Endpoint accepts structured output but does not enforce it (model {} form {}); later schema calls of this form are rerouted to the enforceable form",
                    model, form);
        }
        try {
            jdbc.update(UPSERT_SQL, new MapSqlParameterSource()
                    .addValue("model", model).addValue("form", form));
        } catch (RuntimeException doorDown) {
            log.warn("Capability ledger persist failed (in-process cache already applied) model={} form={} reason={}",
                    model, form, doorDown.getMessage());
        }
    }

    /**
     * Promote a (model, form) pair after a live probe proved the endpoint
     * now enforces the schema block: the row flips to enforced = 1 and the
     * in-process cache loses its grip on the key, so the next read consults
     * the door and schema-bearing calls naturally ride this form again.
     * Fail-soft like every other ledger write — a door outage keeps the
     * cached truth stale for one more interval, never blocks the probe.
     */
    public void promoteEnforced(String model, String form) {
        if (model == null || form == null) {
            return;
        }
        cache.remove(cacheKey(model, form));
        try {
            jdbc.update(PROMOTE_SQL, new MapSqlParameterSource()
                    .addValue("model", model).addValue("form", form));
        } catch (RuntimeException doorDown) {
            log.warn("Capability ledger promotion persist failed (cache grant revoked; database truth will be read on next access) model={} form={} reason={}",
                    model, form, doorDown.getMessage());
        }
    }

    /**
     * Refresh the probe timestamp of a row whose unenforced class was just
     * re-confirmed; enforced stays 0 (the steering rule keeps holding).
     */
    public void markProbed(String model, String form) {
        if (model == null || form == null) {
            return;
        }
        try {
            jdbc.update(MARK_PROBED_SQL, new MapSqlParameterSource()
                    .addValue("model", model).addValue("form", form));
        } catch (RuntimeException doorDown) {
            log.warn("Capability ledger probe timestamp refresh failed model={} form={} reason={}",
                    model, form, doorDown.getMessage());
        }
    }

    /**
     * Rows due for a scheduled reprobe: unenforced rows whose last probe fell
     * outside the interval window (never-probed rows count as due). Fail-soft
     * like every ledger door — an unreadable ledger answers "nothing due"
     * (P18: absence of evidence never becomes evidence of a due round).
     *
     * @param intervalHours hours a row may sit unprobed before it is due again
     * @return (model_name, call_form) pairs, empty when nothing qualifies
     */
    public List<String[]> findStaleObservations(int intervalHours) {
        try {
            return jdbc.query(FIND_STALE_SQL,
                    new MapSqlParameterSource().addValue("negativeHours", -intervalHours),
                    (rs, rowNum) -> new String[]{rs.getString("model_name"),
                            rs.getString("call_form")});
        } catch (RuntimeException doorDown) {
            log.warn("Capability ledger due-row read failed (no reprobe this round, auto retry next round) intervalHours={} reason={}",
                    intervalHours, doorDown.getMessage());
            return List.of();
        }
    }

    private boolean loadFromLedger(String model, String form) {
        try {
            List<Integer> rows = jdbc.queryForList(FIND_SQL,
                    new MapSqlParameterSource().addValue("model", model).addValue("form", form),
                    Integer.class);
            return !rows.isEmpty() && rows.get(0) == 0;
        } catch (RuntimeException doorDown) {
            // Fail-soft read: an unreachable ledger answers "no observation",
            // i.e. the legacy form stays chosen — never a generation blocker.
            log.warn("Capability ledger read gate unreachable, continuing with no observation model={} form={} reason={}",
                    model, form, doorDown.getMessage());
            return false;
        }
    }

    private static String cacheKey(String model, String form) {
        return model + "|" + form;
    }
}
