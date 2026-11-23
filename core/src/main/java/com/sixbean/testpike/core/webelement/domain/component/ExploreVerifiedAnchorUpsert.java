package com.sixbean.testpike.core.webelement.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.webelement.api.dto.WebElementAnchorInput;
import com.sixbean.testpike.core.webelement.api.dto.WebElementInput;
import com.sixbean.testpike.core.webelement.api.dto.WebElementView;
import com.sixbean.testpike.core.webelement.domain.service.WebElementService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Verified-locator ledger sync (WE batch S3, , W2): an exploration
 * step that acted on an element successfully just PROVED the locator on the
 * live page — that proof must persist into the page element ledger instead
 * of dying with the session (repeated re-surveying burns tokens and reviewer
 * attention again on every run).
 *
 * <p>Success mirror of {@link ExploreAnchorReconcile} (its mismatch side);
 * every write goes through the single-source doors — page-key folding
 * (WebElementPageKeys), the lifecycle transition table plus revision CAS and
 * audit event (WebElementService.create/changeState/updateAnchors). A hit on
 * a candidate or stale row promotes it (execution-verified edge); an unseen
 * locator is created and immediately promoted through the same door; retired
 * rows are terminal and never resurrect; already-confirmed rows replay as a
 * pure no-op.
 *
 * <p>Honesty pins: only the ledger locator dialects (css/xpath/text/
 * placeholder/alt/testid/rel) carried by the step target count as verified;
 * free-form envelope keys (role, url, ...) never fabricate an anchor, and a
 * blank locator value is ignored instead of creating an unusable row.
 */
@Component
public class ExploreVerifiedAnchorUpsert {

    /** Locator dialects the ledger accepts (mirrors the service value domain). */
    private static final Set<String> LOCATOR_DIALECTS =
            Set.of("css", "xpath", "text", "placeholder", "alt", "testid", "rel");

    /** Execution just proved this anchor on the live page: strongest evidence tier. */
    private static final double VERIFIED_STRENGTH = 0.9;

    /** One verified locator as carried by a successful step target. */
    public record Verified(String type, String value) {
    }

    /** What the ledger door did for one verified locator. */
    public enum Action {
        /** Existing candidate/stale row hit by this anchor, promoted to confirmed. */
        PROMOTED,
        /** Same-name row gained this anchor beside its old ones (and was promoted). */
        ANCHORS_EXTENDED,
        /** Unseen locator: row created through the create door, then promoted. */
        CREATED_CONFIRMED,
        /** Row already confirmed: replayed with zero writes. */
        ALREADY_CONFIRMED,
        /** Row retired (terminal): left untouched, never resurrected. */
        RETIRED_SKIPPED,
        /** Malformed or non-locator input: ignored, nothing fabricated. */
        IGNORED
    }

    public record Result(Action action, Long elementId) {
    }

    /**
     * Verified facts extracted from one journaled step: the page acted on, an
     * optional human label (the element's visible text) and the ledger-dialect
     * locators the executor just used successfully.
     */
    public record StepVerification(String pageUrl, String label, List<Verified> verified) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WebElementService webElementService;
    private final WebElementPageKeys pageKeys;

    public ExploreVerifiedAnchorUpsert(WebElementService webElementService,
                                       WebElementPageKeys pageKeys) {
        this.webElementService = webElementService;
        this.pageKeys = pageKeys;
    }

    /**
     * Read the step wire pair (action JSON + result JSON as journaled by the
     * exploration loop); null unless the step SUCCEEDED on a page URL with at
     * least one ledger-dialect locator in its target. Fail-soft by contract —
     * a missing verdict here is never an empty verdict.
     */
    public static StepVerification forStep(String stepActionJson, String stepResultJson) {
        if (stepActionJson == null || stepActionJson.isBlank()
                || stepResultJson == null || stepResultJson.isBlank()) {
            return null;
        }
        try {
            JsonNode result = MAPPER.readTree(stepResultJson);
            if (!result.path("ok").asBoolean(false)) {
                return null;
            }
            JsonNode arguments = MAPPER.readTree(stepActionJson).path("arguments");
            String url = arguments.path("url").asText(null);
            JsonNode target = arguments.path("target");
            if (url == null || url.isBlank() || !target.isObject()) {
                return null;
            }
            List<Verified> verified = new ArrayList<>();
            target.fields().forEachRemaining(entry -> {
                String dialect = entry.getKey().toLowerCase();
                if (LOCATOR_DIALECTS.contains(dialect) && entry.getValue().isTextual()
                        && !entry.getValue().asText().isBlank()) {
                    verified.add(new Verified(dialect, entry.getValue().asText().trim()));
                }
            });
            if (verified.isEmpty()) {
                return null;
            }
            String label = target.path("text").asText(null);
            return new StepVerification(url, label == null || label.isBlank() ? null : label.trim(),
                    List.copyOf(verified));
        } catch (Exception malformed) {
            return null;
        }
    }

    /** Upsert one verified locator into the page's ledger through the doors. */
    public Result upsert(long projectId, String pageUrl, Verified verified, String label) {
        if (verified == null || verified.type() == null || verified.value() == null) {
            return new Result(Action.IGNORED, null);
        }
        String type = verified.type().trim().toLowerCase();
        String value = verified.value().trim();
        if (!LOCATOR_DIALECTS.contains(type) || value.isEmpty()) {
            return new Result(Action.IGNORED, null);
        }
        String pageKey = pageKeys.pageKey(pageUrl);
        String effectiveName = label == null || label.isBlank() ? type + ":" + value : label.trim();
        WebElementView sameName = null;
        for (WebElementView row : webElementService.listByProject(projectId, pageKey)) {
            for (WebElementAnchorInput anchor : row.anchors()) {
                if (anchor.type() != null && type.equalsIgnoreCase(anchor.type().trim())
                        && value.equals(anchor.value() == null ? null : anchor.value().trim())) {
                    return settleOnHit(row);
                }
            }
            if (sameName == null && row.name() != null && row.name().trim().equals(effectiveName)) {
                sameName = row;
            }
        }
        return sameName != null ? extendAnchors(sameName, type, value)
                : createConfirmed(projectId, pageUrl, effectiveName, type, value);
    }

    /** Promote a hit row through the state door; confirmed replays, retired stays. */
    private Result settleOnHit(WebElementView row) {
        return switch (row.state()) {
            case WebElementLifecycle.CONFIRMED -> new Result(Action.ALREADY_CONFIRMED, row.id());
            case WebElementLifecycle.RETIRED -> new Result(Action.RETIRED_SKIPPED, row.id());
            default -> {
                webElementService.changeState(row.id(), WebElementLifecycle.CONFIRMED,
                        row.revision());
                yield new Result(Action.PROMOTED, row.id());
            }
        };
    }

    /**
     * Same-name row missing this anchor: prepend it (freshly execution-proved,
     * strongest tier) over the retained old anchors, then promote when the
     * lifecycle allows. Both writes consume the CAS token returned by the
     * previous door — no raw write, no second revision read.
     */
    private Result extendAnchors(WebElementView row, String type, String value) {
        List<WebElementAnchorInput> merged = new ArrayList<>();
        merged.add(new WebElementAnchorInput(type, value, VERIFIED_STRENGTH,
                "探索步进执行验证自动登记"));
        for (WebElementAnchorInput old : row.anchors()) {
            merged.add(old);
        }
        WebElementView updated = webElementService.updateAnchors(row.id(), List.copyOf(merged),
                row.revision());
        if (updated.state() == WebElementLifecycle.CANDIDATE
                || updated.state() == WebElementLifecycle.STALE) {
            webElementService.changeState(updated.id(), WebElementLifecycle.CONFIRMED,
                    updated.revision());
        }
        return new Result(Action.ANCHORS_EXTENDED, row.id());
    }

    /**
     * Unseen locator: enter through the create door (candidates only by
     * design) and immediately cross the execution-verified edge — the ledger
     * never bypasses the state table even for freshly proved anchors.
     */
    private Result createConfirmed(long projectId, String pageUrl, String name,
                                   String type, String value) {
        WebElementView created = webElementService.create(new WebElementInput(projectId, name,
                pageUrl, List.of(new WebElementAnchorInput(type, value, VERIFIED_STRENGTH,
                        "探索步进执行验证自动登记"))));
        WebElementView confirmed = webElementService.changeState(created.id(),
                WebElementLifecycle.CONFIRMED, created.revision());
        return new Result(Action.CREATED_CONFIRMED, confirmed.id());
    }
}
