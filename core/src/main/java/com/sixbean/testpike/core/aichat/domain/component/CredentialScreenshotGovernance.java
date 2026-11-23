package com.sixbean.testpike.core.aichat.domain.component;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.executor.domain.component.StorageRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Credential-screenshot governance (BD batch piece E, adjudication D2:
 * delete-on-terminal-state + honest placeholder).
 * <p>A DevTools Application-panel screenshot pasted into the dialog is a
 * credential carrier: its pixels must not sit in object storage after the
 * parsing run reaches a terminal state. This component owns the two
 * mechanical halves of that promise:
 * <ul>
 * <li>marker contract — one independent {@code credLedger} key inside the
 * same user-turn state frame that already books {@code chatImages}
 * . Image entries keep their exact meaning; the
 * marker only adds (resolvedAt, objectKeys).</li>
 * <li>anchor-pinned purge — originals are deleted strictly by the booked
 * objectKeys through the single storage door
 * ({@code StorageRouter.systemProvider}; provider delete is
 * idempotent, Q6 posture), never by prefix or pattern.</li>
 * </ul>
 * <p>R57 dual inspection: the inline purge at the detection point is the
 * entry-side path (piece D wiring calls {@link #purgeResolved}); this class
 * also carries {@link #sweepPastGrace} as the periodic orphan pass for
 * rows whose inline delete never landed (crash between mark and delete).
 * The grace window is a hot K8 key so operators can widen it while a
 * replay-verification retry is still in flight.
 */
@Component
public class CredentialScreenshotGovernance {

    /** Frame-meta marker key booking the credential-carrier originals (piece D writes it). */
    public static final String META_CRED_KEY = "credLedger";
    /** Minutes a booked credential frame may age before the compensating sweep purges it. */
    public static final String KEY_GRACE_MINUTES = "ai.chat.credential-grace-minutes";
    /** Reader fallback when the key is absent or unparsable (short, fail-closed). */
    public static final int DEFAULT_GRACE_MINUTES = 10;
    /** Frame rows inspected per sweep pass; the rest is picked up next pass (idempotent). */
    static final int SWEEP_BATCH_LIMIT = 500;

    private static final Logger log = LoggerFactory.getLogger(CredentialScreenshotGovernance.class);

    private final StorageRouter storageRouter;
    private final ConfigService configService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    public CredentialScreenshotGovernance(StorageRouter storageRouter,
                                          ConfigService configService,
                                          JdbcTemplate jdbcTemplate) {
        this.storageRouter = storageRouter;
        this.configService = configService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Merge the credential marker into one frame meta without touching any
     * other key (the chatImages array stays byte-identical). Built with
     * Jackson so a user-chosen file name can never break out of the JSON —
     * same construction posture as the image ledger itself.
     */
    public String withCredLedger(String frameMetaJson, List<String> objectKeys,
                                 LocalDateTime resolvedAt) {
        ObjectNode root = parseToEmptyObject(frameMetaJson);
        ObjectNode ledger = root.putObject(META_CRED_KEY);
        if (resolvedAt != null) {
            ledger.put("resolvedAt", resolvedAt.toString());
        }
        var booked = ledger.putArray("objectKeys");
        if (objectKeys != null) {
            for (String key : objectKeys) {
                if (key != null && !key.isBlank()) {
                    booked.add(key);
                }
            }
        }
        return root.toString();
    }

    /**
     * Booked credential object keys of one frame meta, in booked order.
     * Absent marker, blank input and unparsable rows all answer "nothing
     * booked": one malformed ledger row must not throw the sweep off its rail,
     * and a plain image-ledger row is not a credential row.
     */
    public List<String> credentialKeysOf(String metaJson) {
        if (metaJson == null || metaJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode booked = mapper.readTree(metaJson)
                    .path(META_CRED_KEY).path("objectKeys");
            if (!booked.isArray()) {
                return List.of();
            }
            List<String> keys = new ArrayList<>();
            for (JsonNode key : booked) {
                String value = key.asText("");
                if (!value.isBlank()) {
                    keys.add(value);
                }
            }
            return List.copyOf(keys);
        } catch (JacksonException ex) {
            log.warn("凭证截图台账行不可解析，本轮按无凭证登记处理: {}", ex.toString());
            return List.of();
        }
    }

    /**
     * Delete the originals booked in one frame meta by anchor. Idempotent by
     * the provider contract (missing object = quiet success), so the inline
     * terminal-state call and the sweep pass may both fire on the same row.
     *
     * @return number of booked keys sent through the storage door
     */
    public int purgeResolved(String metaJson) {
        List<String> keys = credentialKeysOf(metaJson);
        if (keys.isEmpty()) {
            return 0;
        }
        keys.forEach(storageRouter.systemProvider()::delete);
        return keys.size();
    }

    /**
     * Compensating sweep: frames carrying the marker since before the grace
     * window get their booked originals purged. Covers the crash window
     * between "marker landed" and "inline delete ran" (R57 entry+exit pair).
     *
     * @return keys purged this pass (0 = nothing due, zero side effects)
     */
    public int sweepPastGrace(LocalDateTime now) {
        LocalDateTime cutoff = now.minusMinutes(readGraceMinutes());
        List<String> metas = jdbcTemplate.queryForList(
                "SELECT meta_json::text FROM tp_ai_chat_frame"
                        + " WHERE jsonb_exists(meta_json, ?) AND created_at < ?"
                        + " ORDER BY id LIMIT ?",
                String.class, META_CRED_KEY, cutoff, SWEEP_BATCH_LIMIT);
        int purged = 0;
        for (String meta : metas) {
            purged += purgeResolved(meta);
        }
        return purged;
    }

    private int readGraceMinutes() {
        String raw = configService.getValue(KEY_GRACE_MINUTES,
                String.valueOf(DEFAULT_GRACE_MINUTES));
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : DEFAULT_GRACE_MINUTES;
        } catch (RuntimeException ex) {
            return DEFAULT_GRACE_MINUTES;
        }
    }

    private ObjectNode parseToEmptyObject(String metaJson) {
        if (metaJson == null || metaJson.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            JsonNode parsed = mapper.readTree(metaJson);
            if (parsed instanceof ObjectNode object) {
                return object;
            }
        } catch (JacksonException ex) {
            log.warn("帧 meta 不可解析，凭证标记按新建空对象落账: {}", ex.toString());
        }
        return mapper.createObjectNode();
    }
}
