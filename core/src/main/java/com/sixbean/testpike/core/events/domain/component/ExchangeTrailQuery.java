package com.sixbean.testpike.core.events.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Gated read doors over one exchange-trail row of the process-event ledger
 * .
 *
 * <p>The red line stays: plaintext never rides the event stream. What these
 * doors add is the audited exception - a viewer who passed the login +
 * project-membership checks at the admin bridge may ask THIS row for
 * (a) the plaintext of one sealed header ({@link #reveal}) or (b) the
 * unclipped trail beyond the display budget ({@link #fullText}). Every call
 * lands an audit row, mirroring the credential reveal precedent.
 *
 * <p>Sealed sidecar shape (written by {@link ProbeExchangeRunner}): one line
 * per masked value, {@code request:<name>} or {@code response:<name>} tab the
 * sealed token, parsed line by line - never a whole-column decrypt.
 */
@Component
public class ExchangeTrailQuery {

    /** Upper bound for one page of the full-text read door (chars). */
    static final int MAX_PAGE_CHARS = 20000;

    /** Revealed plaintext of one field (multi-valued headers return one entry
     *  per stored line, in trail order). */
    public record TrailRevealView(String field, List<String> values) {
    }

    /** One page of the unclipped trail plus where the cursor stands. */
    public record TrailFullTextView(String text, int from, int totalChars, boolean hasMore) {
    }

    private final JdbcTemplate jdbc;
    private final ExchangeSecretLedger secrets;

    public ExchangeTrailQuery(JdbcTemplate jdbc, ExchangeSecretLedger secrets) {
        this.jdbc = jdbc;
        this.secrets = secrets;
    }

    /**
     * Decrypts the sealed values of ONE field of ONE trail row. The row must
     * belong to the addressed door object (job kind + business anchor), so a
     * guessed event id of another login state / credential is refused.
     */
    @Audit(objectType = "probe-trail", action = "reveal", objectId = "#eventId")
    public TrailRevealView reveal(String jobKind, String jobRef, long eventId, String fieldKey) {
        Map<String, Object> row = loadOwnedRow(jobKind, jobRef, eventId);
        String sealed = asText(row.get("sealed"));
        String asked = fieldKey == null ? "" : fieldKey.strip().toLowerCase(Locale.ROOT);
        List<String> values = new ArrayList<>();
        Set<String> available = new LinkedHashSet<>();
        boolean unsealedShape = false;
        if (sealed != null) {
            for (String line : sealed.split("\n")) {
                int tab = line.indexOf('\t');
                if (tab <= 0) {
                    continue;
                }
                String key = line.substring(0, tab).strip().toLowerCase(Locale.ROOT);
                String token = line.substring(tab + 1).strip();
                available.add(key);
                if (!key.equals(asked)) {
                    continue;
                }
                if (!secrets.isSealed(token)) {
                    unsealedShape = true;
                    continue;
                }
                values.add(secrets.unseal(token));
            }
        }
        if (unsealedShape) {
            throw new ServiceException("字段「" + asked + "」当时未能密文留存（加密服务未配置，"
                    + "密文未留存），无法解显——请先在「系统设置 → 加密参数」配置密钥，"
                    + "再重新执行一次测试获得新的密文。", BizErrorCode.VALIDATION_ERROR);
        }
        if (values.isEmpty()) {
            throw new ServiceException("字段「" + asked + "」在该步骤没有密文留存，无法解显。"
                    + (available.isEmpty() ? "该步骤本身不含被加密的敏感头。"
                    : "该步骤可解显的字段：" + String.join("、", available) + "。")
                    + "（仅敏感头族 authorization/cookie/set-cookie 等会被加密留存）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return new TrailRevealView(asked, values);
    }

    /**
     * Paged window over the unclipped trail of one row: the honest
     * "expand full text" read door behind every over-budget marker. Rows that
     * never went over budget simply answer their plain detail.
     */
    @Audit(objectType = "probe-trail", action = "read-full", objectId = "#eventId")
    public TrailFullTextView fullText(String jobKind, String jobRef, long eventId,
                                      int from, Integer limit) {
        Map<String, Object> row = loadOwnedRow(jobKind, jobRef, eventId);
        String full = asText(row.get("detail_full"));
        String source = full != null && !full.isBlank() ? full : asText(row.get("detail"));
        if (source == null) {
            source = "";
        }
        int start = Math.max(0, Math.min(from, source.length()));
        int page = limit == null || limit <= 0
                ? MAX_PAGE_CHARS
                : Math.min(limit, MAX_PAGE_CHARS);
        int end = Math.min(source.length(), start + page);
        return new TrailFullTextView(source.substring(start, end), start,
                source.length(), end < source.length());
    }

    /** Row load with the door-ownership cross-check (id alone is not enough). */
    private Map<String, Object> loadOwnedRow(String jobKind, String jobRef, long eventId) {
        Map<String, Object> row;
        try {
            row = jdbc.queryForMap("SELECT job_kind, job_ref, detail, detail_full, sealed"
                    + " FROM tp_ai_task_event WHERE id = ?", eventId);
        } catch (DataAccessException gone) {
            throw new ServiceException("过程事件里找不到该步骤（job=" + jobKind + " id=" + eventId
                    + "）——该运行可能已被留存裁剪清理，请重新执行一次测试后再展开。",
                    BizErrorCode.NOT_FOUND);
        }
        if (!String.valueOf(row.get("job_kind")).equals(jobKind)
                || !String.valueOf(row.get("job_ref")).equals(jobRef)) {
            throw new ServiceException("该步骤（id=" + eventId + "）不属于当前对象的测试运行，"
                    + "请从本页面的运行窗展开后重试。", BizErrorCode.FORBIDDEN);
        }
        return row;
    }

    private static String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
