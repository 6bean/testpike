package com.sixbean.testpike.core.behavior.domain.component;

import com.sixbean.testpike.core.behavior.domain.service.impl.BehaviorOutcomeServiceImpl;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Adoption form derivation for AI-generated material ( piece 1, BA ruling
 * item 7). Compares the producing AI snapshot with the current human-touched
 * text and classifies the reaction into one of the four ledger outcomes:
 * adopted (straight adoption), partial (light edit within the soft-edit
 * threshold), corrected (heavy rewrite that still keeps some of the material)
 * and deprecated (deleted, or rewritten past recognition).
 *
 * <p>Pure statistics, zero interception: this component only classifies, it
 * never blocks an edit or a review — the write-side human gates stay exactly
 * as they are. The rows it describes are recorded through the shared
 * {@link BehaviorOutcomeServiceImpl#SOURCE_ADOPTION} channel of the single
 * outcome ledger (R40: same table as , no second ledger).</p>
 *
 * <p>The soft-edit threshold is K8 hot-reloaded: re-read on every call, with
 * out-of-range or unparsable values falling back to the shipped default so a
 * typo on the settings page skews nothing.</p>
 */
@Component
public class AdoptionFormDeriver {

    private static final Logger log = LoggerFactory.getLogger(AdoptionFormDeriver.class);

    /** K8: normalized edit distance up to which a touched case still counts as a light edit. */
    public static final String KEY_SOFTEDIT_RATIO = "ai.gen.adoption-softedit-ratio";
    public static final double SHIPPED_DEFAULT_RATIO = 0.2;

    /** Above this edit ratio (similarity below 1 - floor) the touched material no
     *  longer recognizably derives from the AI output — it counts as thrown away. */
    static final double MAX_USEFUL_EDIT_RATIO = 0.8;

    private final ConfigService configService;

    public AdoptionFormDeriver(ConfigService configService) {
        this.configService = configService;
    }

    /**
     * Derived classification of one AI output against its post-edit state.
     *
     * @param outcome     adopted / partial / corrected / deprecated (ledger vocabulary)
     * @param similarity  1 - normalized edit distance, in [0, 1]
     */
    public record AdoptionForm(String outcome, double similarity) {
    }

    /**
     * Classifies the human reaction on one AI-produced text.
     * Returns null when the material has no AI snapshot (not AI-produced —
     * hand-written cases are never adoption observations) and when the raw
     * text exists but the edited text is blank (a deletion: deprecated by
     * definition, no distance needed and no threshold read).
     */
    public AdoptionForm derive(String aiRawText, String editedText) {
        if (aiRawText == null || aiRawText.isBlank()) {
            return null;
        }
        if (editedText == null || editedText.isBlank()) {
            return new AdoptionForm("deprecated", 0.0);
        }
        double similarity = similarity(aiRawText, editedText);
        return new AdoptionForm(formFor(similarity, softEditRatio()), similarity);
    }

    /**
     * Pure mapping from similarity to the outcome vocabulary. The edit ratio
     * (1 - similarity) decides: zero edit is a straight adoption, an edit up to
     * and including the soft-edit threshold is a light edit, a heavier rewrite
     * that still recognizably derives from the AI output is a correction, and a
     * rewrite past the useful floor (or a full replacement) is a rejection.
     */
    public static String formFor(double similarity, double softEditRatio) {
        double editRatio = 1.0 - similarity;
        if (editRatio <= 0.0) {
            return "adopted";
        }
        if (editRatio <= softEditRatio) {
            return "partial";
        }
        return editRatio <= MAX_USEFUL_EDIT_RATIO ? "corrected" : "deprecated";
    }

    /** Normalized Levenshtein similarity: 1 - distance / max(len). Identical blanks never reach here. */
    static double similarity(String a, String b) {
        int distance = editDistance(a, b);
        int longest = Math.max(a.length(), b.length());
        if (longest == 0) {
            return 1.0;
        }
        return 1.0 - (double) distance / longest;
    }

    /** Two-row dynamic programming Levenshtein distance (case sensitive, no normalisation tricks). */
    static int editDistance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] swap = prev;
            prev = curr;
            curr = swap;
        }
        return prev[b.length()];
    }

    /** Re-reads the K8 key on every call (hot reload); bad values fall back to the shipped default. */
    private double softEditRatio() {
        String raw = configService.getValue(KEY_SOFTEDIT_RATIO, String.valueOf(SHIPPED_DEFAULT_RATIO));
        try {
            double value = Double.parseDouble(raw.trim());
            if (value > 0 && value < 1) {
                return value;
            }
        } catch (RuntimeException parsed) {
            // fall through to the shipped default below
        }
        log.warn("adoption soft-edit threshold '{}' is out of range or unparsable, using shipped default {}",
                raw, SHIPPED_DEFAULT_RATIO);
        return SHIPPED_DEFAULT_RATIO;
    }
}
