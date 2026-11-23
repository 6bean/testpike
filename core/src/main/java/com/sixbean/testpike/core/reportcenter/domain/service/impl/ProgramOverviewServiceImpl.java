package com.sixbean.testpike.core.reportcenter.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.report.CaliberService;
import com.sixbean.testpike.core.defects.api.dto.DefectBurnWindow;
import com.sixbean.testpike.core.defects.domain.component.DefectBurnWindowQuery;
import com.sixbean.testpike.core.journey.api.dto.JourneyTrafficLight;
import com.sixbean.testpike.core.journey.domain.component.JourneyTrafficLightQuery;
import com.sixbean.testpike.core.perf.api.dto.PerfOverview;
import com.sixbean.testpike.core.perf.domain.component.PerfOverviewQuery;
import com.sixbean.testpike.core.program.api.dto.ProgramInfo;
import com.sixbean.testpike.core.program.domain.component.ProgramLensScope;
import com.sixbean.testpike.core.program.domain.service.ProgramService;
import com.sixbean.testpike.core.reportcenter.api.dto.CaliberFootnote;
import com.sixbean.testpike.core.reportcenter.api.dto.ProgramOverview;
import com.sixbean.testpike.core.reportcenter.api.dto.ProjectPassRate;
import com.sixbean.testpike.core.reportcenter.domain.component.CaliberBlockRunner;
import com.sixbean.testpike.core.reportcenter.domain.service.ProgramOverviewService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Program overview aggregation: per-project live pass-rate readings through the
 * lens visibility single source (ProgramLensScope) plus registry-generated
 * footnotes. Caliber execution itself is delegated to CaliberBlockRunner so the
 * snapshot builder and this dashboard can never diverge on block shape.
 */
@Service
public class ProgramOverviewServiceImpl implements ProgramOverviewService {

    /** Default dashboard window when the caller sends no explicit period. */
    static final int DEFAULT_WINDOW_DAYS = 7;

    private final ProgramService programService;
    private final ProgramLensScope programLensScope;
    private final CaliberBlockRunner caliberBlockRunner;
    private final DefectBurnWindowQuery defectBurnWindowQuery;
    private final JourneyTrafficLightQuery journeyTrafficLightQuery;
    private final PerfOverviewQuery perfOverviewQuery;

    public ProgramOverviewServiceImpl(ProgramService programService,
                                      ProgramLensScope programLensScope,
                                      CaliberBlockRunner caliberBlockRunner,
                                      DefectBurnWindowQuery defectBurnWindowQuery,
                                      JourneyTrafficLightQuery journeyTrafficLightQuery,
                                      PerfOverviewQuery perfOverviewQuery) {
        this.programService = programService;
        this.programLensScope = programLensScope;
        this.caliberBlockRunner = caliberBlockRunner;
        this.defectBurnWindowQuery = defectBurnWindowQuery;
        this.journeyTrafficLightQuery = journeyTrafficLightQuery;
        this.perfOverviewQuery = perfOverviewQuery;
    }

    @Override
    public ProgramOverview overview(long programId, Long operatorId,
                                    String periodFrom, String periodTo) {
        if (programId <= 0) {
            throw new ServiceException("项目集 id 必须为正整数（收到 " + programId + "）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        // Existence + meta come from ProgramService.get (single source — an unknown id
        // surfaces as its 40401; this endpoint never runs a second existence query).
        ProgramInfo program = programService.get(programId);
        LocalDateTime to = parseOrDefault(periodTo, LocalDateTime.now(), "期间止");
        LocalDateTime from = parseOrDefault(periodFrom, to.minusDays(DEFAULT_WINDOW_DAYS), "期间起");
        if (!from.isBefore(to)) {
            throw new ServiceException("期间起必须早于期间止（当前 " + from + " ~ " + to + "）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        // The project segment uses the same lens decision component as the list
        // endpoints: lens projects ∩ caller visibility; an empty segment is an honest
        // empty feed (no error, no leakage of projects outside the visible domain).
        Set<Long> visible = programLensScope.visibleProjectIds(programId, operatorId);
        List<Long> ordered = (visible == null ? Set.<Long>of() : visible).stream().sorted().toList();
        List<ProjectPassRate> projects = ordered.stream()
                .map(pid -> new ProjectPassRate(pid, passRateOf(pid, from, to)))
                .toList();
        // Burndown blocks share the very same visible segment in the very same order
        // as the pass-rate rows (one grouped query through the defect-domain single
        // source; a defect-free project rides an honest all-zero block).
        Map<Long, DefectBurnWindow> burnRows = defectBurnWindowQuery.burnWindows(ordered, from, to);
        List<DefectBurnWindow> defectBurn = ordered.stream()
                .map(pid -> burnRows.getOrDefault(pid, DefectBurnWindow.zeros(pid)))
                .toList();
        // Journey lights share the very same segment and order: latest in-window run
        // per project decides the colour (journey-domain single query source; a
        // journey-free project rides an honest grey block).
        Map<Long, JourneyTrafficLight> lightRows =
                journeyTrafficLightQuery.trafficLights(programId, ordered, from, to);
        List<JourneyTrafficLight> journeyTrafficLight = ordered.stream()
                .map(pid -> lightRows.getOrDefault(pid, JourneyTrafficLight.grey(pid)))
                .toList();
        // Load-test blocks share the very same segment and order: the latest in-window run
        // colours the block and the worst in-window frame carries its readings (perf-domain
        // single query source; a run-free project rides an honest quiet grey block).
        Map<Long, PerfOverview> perfRows = perfOverviewQuery.overviewBlocks(ordered, from, to);
        List<PerfOverview> perfOverview = ordered.stream()
                .map(pid -> perfRows.getOrDefault(pid, PerfOverview.quiet(pid)))
                .toList();
        // Footnotes are generated from the registry itself so the dashboard can never
        // quote a stale display name or version against what the server evaluates.
        List<CaliberFootnote> registry = CaliberService.listAll().stream()
                .map(def -> new CaliberFootnote(def.code(), def.displayName(), def.version()))
                .toList();
        return new ProgramOverview(programId, program.name(), from, to, projects, defectBurn,
                journeyTrafficLight, perfOverview, registry);
    }

    /** Live pass-rate reading for one project (null = no executions inside the window). */
    private BigDecimal passRateOf(long projectId, LocalDateTime from, LocalDateTime to) {
        JsonNode value = caliberBlockRunner
                .scalarBlock("pass-rate", CaliberService.scopeParams(projectId, from, to))
                .get("value");
        return value == null || value.isNull() ? null : value.decimalValue();
    }

    /** Optional ISO period text; blank = caller default, malformed = 40001 with the field named. */
    private static LocalDateTime parseOrDefault(String raw, LocalDateTime fallback, String label) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return LocalDateTime.parse(raw.trim());
        } catch (Exception e) {
            throw new ServiceException(label + "格式不合法: " + raw
                    + "（应为 ISO 日期时间，如 2026-10-01T00:00）", BizErrorCode.VALIDATION_ERROR);
        }
    }
}
