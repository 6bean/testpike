package com.sixbean.testpike.aiservice.llm.pipeline;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * No-mental-math rule of the behaviour kernel , adjudication
 * item 2): quantitative-shaped figures in an answer — counts, rates, amounts,
 * durations, sizes — must be backed by this run's tool returns, never
 * invented. This is the pipeline-level enforcement of the user's standing
 * ruling "statistics are computed server-side, never read out of retrieved
 * text", shared by every reasoning domain instead of each re-deriving its
 * own regex.
 * <p>Exemption head-note (the false-positive firewall; each family has its
 * own L2 hit case so an exemption can never sit vacuous):
 * <ul>
 * markers: positional labels, not measurements;</li>
 * the 1900..2099 window;</li>
 * <li>figures without any unit word — "version 3" carries no quantity
 * shape and stays out of the rule entirely.</li>
 * </ul>
 * Only quantity shapes <em>with</em> a unit are claims worth auditing: the
 */
public final class NumberProvenance {

    /** A figure glued to (or one space from) a unit word — the audited shape. */
    private static final Pattern QUANTITY_SHAPE = Pattern.compile(
            "(\\d+(?:\\.\\d+)?) ?(条|个|项|次|例|人|行|字符|页|轮|台|天|小时|分钟|秒|毫秒|万元|元|%|％)");

    /** Exemption family: ordinal / step label in front of the figure. */
    private static final Pattern ORDINAL_PREFIX = Pattern.compile("(第|步骤)\\s*$");

    
    private static final Pattern YEAR_SHAPE = Pattern.compile("(19|20)\\d{2}");

    private NumberProvenance() {
    }

    /**
     * Every quantity-shaped mention of the answer, in order, exemptions
     * applied. Each entry keeps the literal figure plus its unit so a
     * reminder can quote what it points at.
     */
    public static List<String> findQuantityMentions(String answer) {
        List<String> found = new ArrayList<>();
        if (answer == null || answer.isBlank()) {
            return found;
        }
        Matcher matcher = QUANTITY_SHAPE.matcher(answer);
        while (matcher.find()) {
            String figure = matcher.group(1);
            int figureStart = matcher.start(1);
            if (isOrdinalOrStep(answer, figureStart)
                    || isYearForm(answer, figure, figureStart, matcher.end())) {
                continue;
            }
            found.add(figure + " " + matcher.group(2));
        }
        return found;
    }

    /**
     * Quantity mentions whose figure does not appear anywhere in the run's
     * tool evidence — the candidates for "invented numbers". Blank evidence
     * texts are ignored, so an empty list can only mean "nothing claimed".
     */
    public static List<String> unsourcedQuantities(String answer,
                                                   Collection<String> toolEvidenceTexts) {
        List<String> evidence = new ArrayList<>();
        if (toolEvidenceTexts != null) {
            for (String text : toolEvidenceTexts) {
                if (text != null && !text.isBlank()) {
                    evidence.add(text);
                }
            }
        }
        List<String> unsourced = new ArrayList<>();
        for (String mention : findQuantityMentions(answer)) {
            String figure = mention.split(" ")[0];
            boolean backed = evidence.stream().anyMatch(text -> text.contains(figure));
            if (!backed) {
                unsourced.add(mention);
            }
        }
        return unsourced;
    }

    private static boolean isOrdinalOrStep(String answer, int figureStart) {
        int from = Math.max(0, figureStart - 3);
        return ORDINAL_PREFIX.matcher(answer.substring(from, figureStart)).find();
    }

    private static boolean isYearForm(String answer, String figure, int figureStart, int matchEnd) {
        if (!YEAR_SHAPE.matcher(figure).matches()) {
            return false;
        }
        int value = Integer.parseInt(figure.split("\\.")[0]);
        boolean yearWindow = value >= 1900 && value <= 2099;
        boolean followedByNian = figureStart > 0
                && matchEnd + 1 <= answer.length() && answer.startsWith("年", matchEnd);
        return yearWindow && (followedByNian || figure.chars().allMatch(Character::isDigit)
                && figure.length() == 4);
    }
}
