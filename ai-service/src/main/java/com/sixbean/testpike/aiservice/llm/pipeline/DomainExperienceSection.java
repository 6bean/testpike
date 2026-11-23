package com.sixbean.testpike.aiservice.llm.pipeline;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * L0 assembly slot's domain-experience section : the newest
 * distilled project memory and the SKILL usage ledger, each line annotated
 * with the measured evidence behind its confidence and the date that
 * evidence was refreshed. Pure renderer — rows arrive already project-scoped
 * (the read door owns the bucket filter), this piece owns the shape.
 *
 * <p>Three-state contract (L2): rows present = injected with annotations;
 * no rows or non-positive cap = the section is absent entirely (zero noise,
 * never an empty header); more rows than the cap = newest head kept and the
 * cut honestly disclosed in the tail instead of silently dropped (kernel
 * invariant: an assembly that drops work says so).
 */
public final class DomainExperienceSection {

    /** Section header; the caveat keeps experience below the real evidence. */
    static final String HEADER = "—— 项目经验（本项目的历史装配与使用账，"
            + "仅供参考，与本次检索到的材料冲突时以本次材料为准）——";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private DomainExperienceSection() {
    }

    /**
     * Render the section for one reasoning call.
     *
     * @param lines    project-scoped experience rows (memory + SKILL usage)
     * @param maxLines hot K8 cap {@code ai.behavior.experience-max-lines};
     *                 absent rows or a non-positive cap yield "" (absence)
     * @return the section block starting with a blank line, or "" when absent
     */
    public static String render(List<DomainExperienceLine> lines, int maxLines) {
        return render(lines, maxLines, lines == null ? 0 : lines.size());
    }

    /**
     * Same render with the true row count behind the fetched page, so the
     * disclosed cut counts rows that exist, not just rows that were read
     * (kernel invariant: disclosed numbers come from actual queries).
     *
     * @param totalAvailable total rows the sources hold for this bucket
     */
    public static String render(List<DomainExperienceLine> lines, int maxLines,
                                int totalAvailable) {
        if (lines == null || lines.isEmpty() || maxLines <= 0) {
            return "";
        }
        int kept = Math.min(lines.size(), maxLines);
        StringBuilder section = new StringBuilder("\n\n").append(HEADER).append('\n');
        for (int i = 0; i < kept; i++) {
            section.append("- ").append(renderLine(lines.get(i))).append('\n');
        }
        int cut = Math.max(Math.max(totalAvailable, lines.size()) - kept, 0);
        if (cut > 0) {
            section.append("（另有 ").append(cut)
                    .append(" 条较旧经验未注入本轮）\n");
        }
        return section.toString();
    }

    private static String renderLine(DomainExperienceLine line) {
        if (line.kind() == DomainExperienceLine.KIND_SKILL) {
            return "SKILL《" + line.text() + "》【置信：近 30 天调用 " + line.evidenceCount()
                    + " 次｜时效：最后使用 " + DATE.format(line.freshness()) + "】";
        }
        return line.text() + "【置信：历史装配命中 " + line.evidenceCount()
                + " 次｜时效：" + DATE.format(line.freshness()) + "】";
    }
}
