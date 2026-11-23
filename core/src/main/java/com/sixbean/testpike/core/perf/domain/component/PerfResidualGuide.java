package com.sixbean.testpike.core.perf.domain.component;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Residual-cleanup guide generator (GB-4, ): turns the launch-pinned isolation
 * declaration of a perf run into a per-mode cleanup instruction sheet on the report
 * face. The platform holds no probe right over the system-under-test's data plane,
 * so every guide is an action list for the SUT side (count / sample-verify /
 * recycle), never a fabricated scan result — the boundary note states this once
 * per guide so readers never mistake the sheet for executed evidence.
 * <p>Pure function over the pinned snapshot values plus the dataset ledger row:
 * no DB access here (the report assembly supplies the row), no new judgment
 * spectrum (the mode string is consumed exactly as pinned, the roster comes from
 * {@link PerfAccessGate#ISOLATION_MODES} — the single source the launch gate used).
 */
@Component
public final class PerfResidualGuide {

    /**
     * Column-name facets that mark an account pool: the recycle list names the
     * identity-shaped columns so the operator knows which credentials to retire.
     * A display heuristic owned by this guide only — not an authorization or
     * masking spectrum (those stay on DesensitizeUtils, single source).
     */
    private static final List<String> ACCOUNT_NAME_MARKERS =
            List.of("account", "username", "user", "login", "mobile", "phone", "email");

    /** The station-wide load-test mark header (both engines emit the same name). */
    private static final String MARK_HEADER = "X-Load-Test: 1";

    /**
     * Build the guide for one run. Returns null (honest absence) when the
     * isolation mode was never declared, is blank, or falls outside the roster —
     * the guide is never invented for an undeclared run.
     *
     * @param isolationMode pinned snapshot value (null = not declared)
     * @param datasetRow    the pinned dataset's ledger row
     *                      (name / columns_json / row_count), null when no pool
     *                      was bound or the ledger row is gone
     */
    public Map<String, Object> build(String isolationMode, Map<String, Object> datasetRow) {
        if (isolationMode == null || isolationMode.isBlank()
                || !PerfAccessGate.ISOLATION_MODES.contains(isolationMode)) {
            return null;
        }
        Map<String, Object> out = new HashMap<>();
        out.put("mode", isolationMode);
        switch (isolationMode) {
            case "HEADER_MARK" -> {
                out.put("sections", List.of(section("标记数据抽样核验引导", List.of(
                        "在被测侧的访问日志/入库记录中按标记头「" + MARK_HEADER
                                + "」筛选本次压测产生的全部请求。",
                        "对命中的写操作抽样核验落库位置与数量，确认标记流量未混入正式业务数据。",
                        "按核验结果清理标记流量留下的残留数据（日志、消息、临时单据）。"))));
                out.put("boundaryNote", "标记隔离依赖被测侧对 " + MARK_HEADER
                        + " 头的识别与处理；平台不探测被测库，以上为清理引导而非已执行的核验结果。");
            }
            case "NS_ACCOUNT" -> {
                List<Map<String, Object>> sections = new ArrayList<>();
                if (datasetRow == null) {
                    sections.add(section("账号回收清单", List.of(
                            "本次运行未绑定压测数据集（或数据集台账行已删除），无法自动生成回收清单。",
                            "请按实际使用的压测账号来源逐一回收：注销注册、回滚密码/资料变更、清理账号名下业务数据。")));
                } else {
                    String name = String.valueOf(datasetRow.get("name"));
                    List<String> accountColumns = accountColumnsOf(datasetRow.get("columns_json"));
                    Object rowCount = datasetRow.get("row_count");
                    List<String> items = new ArrayList<>();
                    items.add("本运行绑定数据集「" + name + "」共 " + rowCount
                            + " 行，账号型列：" + (accountColumns.isEmpty()
                                    ? "列名未呈现账号形态，请按整池账号回收"
                                    : String.join("、", accountColumns)) + "。");
                    items.add("按数据集行序逐行回收压测账号：注销注册关系、回滚密码与资料变更、清理该账号名下的业务单据。");
                    items.add("回收完成后核对账号总数应回到压测前基线。");
                    sections.add(section("账号回收清单", items));
                }
                out.put("sections", sections);
                out.put("boundaryNote", "命名空间账号的数据面隔离需被测侧就位；平台不登录被测系统执行回收，"
                        + "以上为按数据集台账生成的回收引导与计数依据。");
            }
            default -> {
                out.put("sections", List.of(section("影子表残留计数引导", List.of(
                        "请被测侧（或 DBA）统计本次压测时间窗内影子表的写入行数与残留批次数。",
                        "按保留策略清理或归档影子表数据，并核对正式表行数在压测前后保持不变。",
                        "将计数结果回填本运行报告的处置记录，作为数据面隔离生效的证据。"))));
                out.put("boundaryNote", "影子表的数据面隔离需被测侧就位；平台不探测被测库，"
                        + "以上为清理与计数引导而非已执行的扫描结果。");
            }
        }
        return out;
    }

    /** Account-shaped column names from the ledger's columns_json text (best-effort
     *  parse: unreadable payload simply yields no names, never blocks the guide). */
    private List<String> accountColumnsOf(Object rawColumns) {
        List<String> names = new ArrayList<>();
        if (rawColumns == null) {
            return names;
        }
        String text = String.valueOf(rawColumns);
        for (String token : text.split("[\\[\\]\",]")) {
            String col = token.trim();
            if (col.isEmpty()) {
                continue;
            }
            String folded = col.toLowerCase(Locale.ROOT).replace('_', '-');
            if (ACCOUNT_NAME_MARKERS.stream().anyMatch(folded::contains)) {
                names.add(col);
            }
        }
        return names;
    }

    private Map<String, Object> section(String title, List<String> items) {
        Map<String, Object> one = new HashMap<>();
        one.put("title", title);
        one.put("items", items);
        return one;
    }
}
