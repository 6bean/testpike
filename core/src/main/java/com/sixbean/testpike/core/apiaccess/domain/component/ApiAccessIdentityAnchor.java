package com.sixbean.testpike.core.apiaccess.domain.component;

import com.sixbean.testpike.core.apiaccess.api.dto.ApiEndpointSpec;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Server-side conformance of the anchors the pass-through doors consume (single source,
 * shared by the read door and the change landing).
 *
 * <p>Two postures live here, one per injected context value:
 *
 * <p>1. Acting-person re-stamp. Endpoints like the project list scope their rows by
 * {@code userId} — a real ownership form the catalog can judge for itself. Before this component
 * such entries were classified as "no project anchor, therefore unreachable", which wrongly
 * lumped identity-scoped data in with the genuinely unscoped; and letting a caller fill
 * {@code userId} by hand would turn the door into an identity-forgery seam. The stamp closes
 * both halves: the door owns the value (a claimed one is overwritten, never honoured), and the
 * entry becomes reachable because its scope is now provably the acting person.
 *
 * <p>2. Project-slice conformance. The dialog link stamps the conversation's project segment
 * under {@link #DIALOG_PROJECT_SLICE_KEY} into every pass-through read before dispatch, because
 * the model never sees — and must never name — the raw id of its own session. A blind stamp
 * however poisoned every entry whose declared shape does not consume that exact key: an entry
 * without any project scope died on the stray-argument guard ("projectId is not a declared
 * parameter"), and an entry spelling its anchor {@code projectIds} or {@code pid} could never
 * be filled at all. Field-found on the prompt-template families (2026-11 dialog incident),
 * where every project-anchored conversation was blocked from a reachable global asset.
 * The conformance makes the stamp shape-aware at the one place that knows the shape: the segment
 * is transferred onto the entry's own declared anchor (server truth over any payload claim,
 * same posture as the person anchor), or dropped when the entry carries no project scope.
 * The tolerance stays deliberately narrow — only the exact stamped key is forgiven, any other
 * undeclared argument still refuses, so the door never becomes a mass-assignment seam.
 */
public final class ApiAccessIdentityAnchor {

    /**
 * The literal key the dialog dispatcher stamps the session segment under
 * (mirrors {@code PassThroughReadAnchor} on the AI service; the two spellings are one
 * contract and must stay byte-identical).
 */
    public static final String DIALOG_PROJECT_SLICE_KEY = "projectId";

    private ApiAccessIdentityAnchor() {
    }

    /**
 * Conform the dialog-stamped project segment to the entry's declared shape.
 *
 * @param spec catalog entry being served
 * @param arguments caller argument bag (may carry the stamped segment key)
 * @return a fresh map with the segment moved onto the declared anchor (or dropped when the
 * entry has no project scope), or the original instance when nothing changes
 */
    public static Map<String, Object> conformProjectSlice(ApiEndpointSpec spec,
                                                          Map<String, Object> arguments) {
        if (arguments == null || !arguments.containsKey(DIALOG_PROJECT_SLICE_KEY)) {
            return arguments;
        }
        String anchor = spec.projectAnchor();
        if (DIALOG_PROJECT_SLICE_KEY.equals(anchor)) {
            return arguments;
        }
        Object slice = arguments.get(DIALOG_PROJECT_SLICE_KEY);
        Map<String, Object> conformed = new LinkedHashMap<>(arguments);
        if (anchor != null) {
            // The session segment decides which project the read is anchored to, under the name
            // the endpoint itself declares; a payload claim on that name is overwritten.
            conformed.put(anchor, slice);
        }
        // anchor == null: the entry carries no project scope, the stamped key is dialog
        // plumbing that nothing downstream can consume — drop it instead of poisoning the read.
        conformed.remove(DIALOG_PROJECT_SLICE_KEY);
        return conformed;
    }

    /**
     * Re-stamp {@code spec.userAnchor()} with the trusted operator id, dropping a model claim.
     * Returns a fresh mutable map when anything changes, the original instance when not.
     */
    public static Map<String, Object> restamp(ApiEndpointSpec spec, Map<String, Object> arguments,
                                              long operatorId) {
        String anchor = spec.userAnchor();
        if (anchor == null) {
            return arguments;
        }
        boolean strayProjectSlice = spec.projectAnchor() == null
                && (arguments.containsKey("projectId") || arguments.containsKey("projectIds")
                        || arguments.containsKey("pid"));
        Object claimed = arguments.get(anchor);
        boolean claimMatches = claimed != null && String.valueOf(claimed).equals(String.valueOf(operatorId));
        if (claimMatches && !strayProjectSlice) {
            return arguments;
        }
        Map<String, Object> stamped = new LinkedHashMap<>(arguments);
        stamped.put(anchor, operatorId);
        if (strayProjectSlice) {
            stamped.remove("projectId");
            stamped.remove("projectIds");
            stamped.remove("pid");
        }
        return stamped;
    }
}
