package com.sixbean.testpike.core.webelement.domain.component;

import com.sixbean.testpike.core.visual.domain.service.VisualBaselineService;
import com.sixbean.testpike.core.webelement.api.dto.ElementEvidenceGrantView;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Evidence-plane baseline grant read path for the equivalent re-anchor
 * compare port (Page Element Library design 8.1 scale B; piece 2b).
 * <p>One query over the live ledger row (evidence-&gt;&gt;'screenshotKey' plus
 * revision) — a dedicated read surface instead of widening WebElementView,
 * because the frontend display contract must not carry internal evidence
 * keys. The presigned GET reuses the visual baseline grant as the single
 * storage-url source (R40: no second presign implementation); when storage
 * cannot grant, the answer stays found=true with url=null and the executor
 * degrades scale B to unprovable, which files a suggestion rather than
 * silently passing a heal.
 * <p>No writer installs evidence.screenshotKey yet ( baseline channel
 * is the scheduled first consumer), so the live path answers found=false
 * and every total-miss heal keeps the proposal route. This component is
 * the read contract the moment a writer lands — the injection tests prove
 * the channel, not a production pass-rate.
 */
@Component
public class WebElementEvidenceGrantQuery {

    private final NamedParameterJdbcTemplate jdbc;
    private final VisualBaselineService visualBaselineService;

    public WebElementEvidenceGrantQuery(NamedParameterJdbcTemplate jdbc,
                                        VisualBaselineService visualBaselineService) {
        this.jdbc = jdbc;
        this.visualBaselineService = visualBaselineService;
    }

    /** Ledger grant for one element id; unknown/dirty ids never throw. */
    public ElementEvidenceGrantView grant(long elementId) {
        if (elementId <= 0) {
            return new ElementEvidenceGrantView(false, null, null, null);
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT revision, evidence ->> 'screenshotKey' AS screenshot_key"
                        + " FROM tp_web_element WHERE id = :id",
                new MapSqlParameterSource("id", elementId));
        if (rows.isEmpty()) {
            return new ElementEvidenceGrantView(false, null, null, null);
        }
        Long revision = rows.get(0).get("revision") == null
                ? null : ((Number) rows.get(0).get("revision")).longValue();
        Object rawKey = rows.get(0).get("screenshot_key");
        String key = rawKey == null ? "" : String.valueOf(rawKey).trim();
        if (key.isEmpty()) {
            return new ElementEvidenceGrantView(false, null, null, revision);
        }
        String url;
        try {
            url = visualBaselineService.grantBaselineViewUrl(key);
        } catch (RuntimeException e) {
            // Storage grants unavailable (unconfigured slot, expired creds):
            // the grant still answers honestly with url=null. Fail-soft to
            // the proposal route beats breaking the result relay over a
            // missing baseline channel.
            url = null;
        }
        return new ElementEvidenceGrantView(true, key, url, revision);
    }
}
