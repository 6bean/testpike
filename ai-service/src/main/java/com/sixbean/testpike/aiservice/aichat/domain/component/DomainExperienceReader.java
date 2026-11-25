package com.sixbean.testpike.aiservice.aichat.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.SkillAssemblyRemoteItem;
import com.sixbean.testpike.aiservice.llm.domain.component.SysConfigInts;
import com.sixbean.testpike.aiservice.llm.pipeline.DomainExperienceLine;
import com.sixbean.testpike.aiservice.llm.pipeline.DomainExperienceSection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Domain-experience read door : assembles the rows the
 * L0 section renderer needs for one dialog project bucket.
 *
 * <p>Sources per the ruling: distilled project memory (tp_ai_ctx_memory,
 * aictx-owned write side, read-only here) and the SKILL usage ledger
 * (tp_skill_usage, the same 30-day aggregate window the ledger index pins).
 * The SKILL candidate set is taken from the core project-assembly door — the
 * re-implements visibility and a SKILL of another project can never cross
 * the same project id in SQL, tp_ai_ctx_memory entries never cross projects
 * by table contract).
 *
 * a dead door, a broken config row — degrades to NO section and never takes
 * the dialog turn down; an absent section is zero noise, not an error.
 * The K8 cap {@code ai.behavior.experience-max-lines} is read hot per call
 * through SysConfigInts (no caching, illegal values fall back to the shipped
 * default), matching the StallBlockPolicy read shape. Stateless besides the
 * injected doors — concurrent turns never share paging state.
 */
@Component
public class DomainExperienceReader {

    private static final Logger log = LoggerFactory.getLogger(DomainExperienceReader.class);

    /** K8 line cap key, single-sourced (seed V408, hot update, no restart). */
    public static final String KEY_MAX_LINES = "ai.behavior.experience-max-lines";

    /** Shipped default = the migration seed value (fallback only). */
    static final int DEFAULT_MAX_LINES = 20;

    private final NamedParameterJdbcTemplate jdbc;
    private final CoreGateway coreGateway;

    public DomainExperienceReader(NamedParameterJdbcTemplate jdbc, CoreGateway coreGateway) {
        this.jdbc = jdbc;
        this.coreGateway = coreGateway;
    }

    /**
     * The rendered experience section for one project bucket, or "" when the
     * project has no experience or the read path fails (absence = zero noise).
     */
    public String sectionFor(long projectId) {
        if (projectId <= 0) {
            return "";
        }
        try {
            int cap = SysConfigInts.positiveInt(
                    coreGateway.getSysConfig(KEY_MAX_LINES), DEFAULT_MAX_LINES);
            DomainExperienceSourcePage memory = memoryPage(projectId, cap);
            DomainExperienceSourcePage usage = skillUsagePage(projectId, cap);
            List<DomainExperienceLine> lines = new ArrayList<>(memory.lines());
            lines.addAll(usage.lines());
            return DomainExperienceSection.render(
                    lines, cap, memory.total() + usage.total());
        } catch (RuntimeException e) {
            log.warn("Domain experience read path unavailable, no experience section injected this turn (chat execution unaffected): {}", e.toString());
            return "";
        }
    }

    /** Distilled memory rows of THIS project only, newest freshness first. */
    private DomainExperienceSourcePage memoryPage(long projectId, int cap) {
        Integer total = jdbc.queryForObject(
                "SELECT count(*) FROM tp_ai_ctx_memory"
                        + " WHERE project_id = :pid AND inject_status = 1",
                new MapSqlParameterSource("pid", projectId), Integer.class);
        if (total == null || total == 0) {
            return DomainExperienceSourcePage.EMPTY;
        }
        List<DomainExperienceLine> rows = jdbc.query(
                "SELECT content, hit_count, updated_at FROM tp_ai_ctx_memory"
                        + " WHERE project_id = :pid AND inject_status = 1"
                        + " ORDER BY updated_at DESC LIMIT :cap",
                new MapSqlParameterSource("pid", projectId).addValue("cap", cap),
                (rs, n) -> new DomainExperienceLine(DomainExperienceLine.KIND_MEMORY,
                        rs.getString("content"), rs.getLong("hit_count"),
                        toLocalDate(rs.getObject("updated_at", OffsetDateTime.class))));
        return new DomainExperienceSourcePage(rows, total);
    }

    /**
     * SKILL usage ledger: aggregate the last-30-day consumption of the SKILLs
     * this project's assembly view exposes (the bucket filter is the door's,
     * never re-implemented here), most-used first.
     */
    private DomainExperienceSourcePage skillUsagePage(long projectId, int cap) {
        List<Long> skillIds = new ArrayList<>();
        for (SkillAssemblyRemoteItem item : coreGateway.getProjectAssembly(projectId)) {
            if (item.kind() == DomainExperienceLine.KIND_SKILL) {
                skillIds.add(item.skillId());
            }
        }
        if (skillIds.isEmpty()) {
            return DomainExperienceSourcePage.EMPTY;
        }
        MapSqlParameterSource params = new MapSqlParameterSource("ids", skillIds);
        Integer total = jdbc.queryForObject(
                "SELECT count(DISTINCT s.id) FROM tp_skill s JOIN tp_skill_usage u"
                        + " ON u.skill_id = s.id"
                        + " WHERE s.id IN (:ids) AND u.created_at >= now() - INTERVAL '30 days'",
                params, Integer.class);
        if (total == null || total == 0) {
            return DomainExperienceSourcePage.EMPTY;
        }
        List<DomainExperienceLine> rows = jdbc.query(
                "SELECT s.name, COUNT(u.id) AS uses, MAX(u.created_at) AS last_used"
                        + " FROM tp_skill s JOIN tp_skill_usage u ON u.skill_id = s.id"
                        + " WHERE s.id IN (:ids) AND u.created_at >= now() - INTERVAL '30 days'"
                        + " GROUP BY s.name ORDER BY uses DESC LIMIT :cap",
                params.addValue("cap", cap),
                (rs, n) -> new DomainExperienceLine(DomainExperienceLine.KIND_SKILL,
                        rs.getString("name"), rs.getLong("uses"),
                        toLocalDate(rs.getObject("last_used", LocalDateTime.class))));
        return new DomainExperienceSourcePage(rows, total);
    }

    private static LocalDate toLocalDate(Object value) {
        if (value instanceof OffsetDateTime odt) {
            return odt.toLocalDate();
        }
        if (value instanceof LocalDateTime ldt) {
            return ldt.toLocalDate();
        }
        return value instanceof LocalDate date ? date : LocalDate.now();
    }
}
