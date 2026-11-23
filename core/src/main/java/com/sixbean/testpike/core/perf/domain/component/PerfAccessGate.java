package com.sixbean.testpike.core.perf.domain.component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.perf.api.dto.PerfGateCheckInput;
import com.sixbean.testpike.core.perf.domain.component.PerfGateDecision;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


@Component
public class PerfAccessGate {

    public static final List<String> ISOLATION_MODES = List.of("SHADOW_TABLE", "NS_ACCOUNT", "HEADER_MARK");
    private static final int ENV_TYPE_PRODUCTION = 4;

    private final NamedParameterJdbcTemplate jdbc;
    private final PerfConfigReader configReader;
    private final ObjectMapper objectMapper;

    public PerfAccessGate(NamedParameterJdbcTemplate jdbc, PerfConfigReader configReader,
                          ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.configReader = configReader;
        this.objectMapper = objectMapper;
    }

    /**
     * Launch-time precheck with the CF-8 explainable ladder: all eight doors are evaluated
     * independently, each reporting PASS/FAIL/SKIP. A rejection takes the gate key and text
     * from the first FAIL row in gate order; the remaining rows still carry what is missing,
     * who can fix it, and the entry hint. Missing input (400) is guarded by the calling
     * service layer; this component rejects incompletely without letting the launch through.
     */
    public PerfGateDecision evaluate(PerfGateCheckInput input) {
        // U-5 budget preview: horizon + hot-updatable margin computed by the same
        // PerfBudgetPolicy single source the dispatcher writes into the envelope.
        // Unknown model / missing keys yield 0 = "no budget claim" (zero-drift for
        // legacy callers that precheck without declaring the pressure).
        long runDurationSeconds = Math.round(PerfBudgetPolicy.durationSecondsOf(
                input.pressureModel(), input.pressureConfig()));
        int marginMinutes = configReader.budgetMarginMinutes();
        long budgetMs = PerfBudgetPolicy.budgetMs(runDurationSeconds, marginMinutes);
        List<PerfGateStep> steps = new ArrayList<>();
        if (input.projectId() == null || input.envId() == null || input.concurrency() == null
                || input.concurrency() <= 0) {
            steps.add(PerfGateStep.fail("OWNERSHIP", "环境归属核对",
                    "预检缺少项目/目标环境/压力档位（RPS）", "场景创建人", "/perf 向导-目标环境"));
            return rejectFromLadder("TARGET_ENV",
                    "压测预检缺少项目/目标环境/压力档位（RPS），请补全场景配置后重试", steps,
                    runDurationSeconds, marginMinutes, budgetMs);
        }
        Map<String, Object> env = loadEnv(input.envId());
        if (env == null || ((Number) env.get("project_id")).longValue() != input.projectId()) {
            steps.add(PerfGateStep.fail("OWNERSHIP", "环境归属核对",
                    "目标环境档案不存在或不属于该项目", "场景创建人", "/perf 向导-目标环境"));
            return rejectFromLadder("TARGET_ENV",
                    "目标环境档案不存在或不属于该项目，请重新选择环境", steps,
                    runDurationSeconds, marginMinutes, budgetMs);
        }
        int envType = ((Number) env.get("env_type")).intValue();
        boolean production = envType == ENV_TYPE_PRODUCTION;
        String envHost = hostOf((String) env.get("base_url"));
        String targetHost = input.targetBaseUrl() == null || input.targetBaseUrl().isBlank()
                ? envHost : hostOf(input.targetBaseUrl());
        steps.add(PerfGateStep.pass("OWNERSHIP", "环境归属核对",
                "环境档案属于该项目（env_type=" + envType + "）"));

        String hitSegment = null;
        for (String seg : configReader.blockedSegments()) {
            if (targetHost.endsWith(seg)) {
                hitSegment = seg;
                break;
            }
        }
        if (hitSegment != null) {
            steps.add(PerfGateStep.fail("BLOCKED_SEGMENT", "禁入段字典",
                    "压测目标 " + targetHost + " 命中生产禁入段「" + hitSegment + "」",
                    "系统管理员（字典键 perf.blocked-target-segments）", "/system/configs"));
        } else {
            steps.add(PerfGateStep.pass("BLOCKED_SEGMENT", "禁入段字典", "目标地址不在禁入段内"));
        }

        boolean globalSwitchOn = configReader.productionEnabled();
        if (!production) {
            steps.add(PerfGateStep.skip("GLOBAL_SWITCH", "生产全局开关", "非生产环境不需要"));
        } else if (!globalSwitchOn) {
            steps.add(PerfGateStep.fail("GLOBAL_SWITCH", "生产全局开关",
                    "生产压测全局开关未开启（默认关）", "系统管理员",
                    "/system/configs 键 perf.production-enabled"));
        } else {
            steps.add(PerfGateStep.pass("GLOBAL_SWITCH", "生产全局开关", "开关已开启"));
        }

        Map<String, Object> whitelist = loadWhitelist(input.projectId(), input.envId());
        if (whitelist == null) {
            steps.add(PerfGateStep.fail("WHITELIST", "压测白名单备案",
                    "该目标环境未列入压测白名单（环境档案×压力档位双维）",
                    "项目管理者", "/perf 向导-白名单备案"));
        } else if (input.concurrency() > ((Number) whitelist.get("max_concurrency")).intValue()) {
            steps.add(PerfGateStep.fail("WHITELIST", "压测白名单备案",
                    "压力档位（RPS） " + input.concurrency() + " 超过该环境备案上限 "
                            + whitelist.get("max_concurrency"),
                    "项目管理者", "/perf 向导-白名单备案"));
        } else {
            steps.add(PerfGateStep.pass("WHITELIST", "压测白名单备案",
                    "已备案且压力档位不超上限 " + whitelist.get("max_concurrency")));
        }

        boolean modeMissing = input.isolationMode() == null || input.isolationMode().isBlank();
        if (!production) {
            steps.add(PerfGateStep.skip("ISOLATION_DECLARATION", "隔离模式申报", "非生产环境可不申报"));
        } else if (modeMissing) {
            steps.add(PerfGateStep.fail("ISOLATION_DECLARATION", "隔离模式申报",
                    "压生产必须申报隔离模式（影子表/账号命名空间/流量标记三选一），场景未声明",
                    "场景创建人", "/perf 向导-隔离模式"));
        } else {
            steps.add(PerfGateStep.pass("ISOLATION_DECLARATION", "隔离模式申报",
                    "已申报 " + input.isolationMode()));
        }

        // ⑥ approval record (the only door for production: approved and covering the
        // requested concurrency tier + isolation mode, not expired)
        if (!production) {
            steps.add(PerfGateStep.skip("APPROVAL", "生产压测审批单", "非生产环境不需要"));
        } else {
            Map<String, Object> approval = loadApproved(input);
            int validHours = configReader.approvalValidHours();
            if (approval == null) {
                steps.add(PerfGateStep.fail("APPROVAL", "生产压测审批单",
                        "无审批通过的申请单（批准范围需覆盖本次压力档位 RPS 与隔离模式）", "项目管理者（提交申请）/审批人",
                        "/perf 向导-审批申请"));
            } else if (validHours > 0) {
                Timestamp handledAt = (Timestamp) approval.get("handled_at");
                if (handledAt != null && handledAt.toLocalDateTime()
                        .isBefore(LocalDateTime.now().minusHours(validHours))) {
                    steps.add(PerfGateStep.fail("APPROVAL", "生产压测审批单",
                            "审批已过期（有效期 " + validHours + " 小时，生效于 "
                                    + handledAt.toLocalDateTime() + "）", "审批人", "/perf 向导-审批申请"));
                } else {
                    steps.add(PerfGateStep.pass("APPROVAL", "生产压测审批单", "审批有效"));
                }
            } else {
                steps.add(PerfGateStep.pass("APPROVAL", "生产压测审批单", "审批有效"));
            }
        }

        if (!modeMissing && whitelist != null) {
            List<String> allowed = allowedModes(whitelist);
            if (!allowed.contains(input.isolationMode())) {
                steps.add(PerfGateStep.fail("ISOLATION_ALLOWED", "隔离模式许可核对",
                        "申报隔离模式 " + input.isolationMode() + " 不在该环境白名单许可集 "
                                + allowed + " 内", "项目管理者", "/perf 向导-白名单备案"));
            } else {
                steps.add(PerfGateStep.pass("ISOLATION_ALLOWED", "隔离模式许可核对",
                        "申报模式在白名单许可集内"));
            }
        } else {
            steps.add(PerfGateStep.skip("ISOLATION_ALLOWED", "隔离模式许可核对", "模式或白名单缺位，无从核对"));
        }

        if (!targetHost.isEmpty() && !envHost.isEmpty() && !targetHost.equals(envHost)) {
            steps.add(PerfGateStep.fail("TARGET_MATCH", "目标地址一致性",
                    "压测目标 " + targetHost + " 与环境档案地址 " + envHost + " 不一致",
                    "场景创建人", "/perf 向导-目标环境"));
        } else {
            steps.add(PerfGateStep.pass("TARGET_MATCH", "目标地址一致性", "与环境档案地址一致"));
        }

        return decideFromLadder(steps, runDurationSeconds, marginMinutes, budgetMs);
    }

    /** Reject with the legacy machine gate key + human reason; the ladder and budget preview ride along. */
    private static PerfGateDecision rejectFromLadder(String gate, String reason, List<PerfGateStep> steps,
                                                    long runDurationSeconds, int marginMinutes, long budgetMs) {
        return PerfGateDecision.reject(gate, reason, steps, runDurationSeconds, marginMinutes, budgetMs);
    }

    /**
     * Turn the full ladder into a decision: gate ordering is the row order, so the first
     * FAIL row decides the top-level gate key. Step code → legacy gate key mapping keeps the
     * machine contract of callers unchanged (OWNERSHIP/TARGET_MATCH→TARGET_ENV,
     * ISOLATION_DECLARATION/ISOLATION_ALLOWED→ISOLATION_PRECHECK).
     * <p>CF-8 three-part reason: what is missing + who has the authority to fix + entry,
     * so single-text consumers (the A-1 launch rejection line) stay actionable too.
     */
    private static PerfGateDecision decideFromLadder(List<PerfGateStep> steps,
                                                    long runDurationSeconds,
                                                    int marginMinutes, long budgetMs) {
        for (PerfGateStep step : steps) {
            if (step.failed()) {
                String reason = step.detail() + "；补齐权限：" + step.fixBy()
                        + "；入口：" + step.entryPath();
                return PerfGateDecision.reject(legacyGateKey(step.code()), reason, steps,
                        runDurationSeconds, marginMinutes, budgetMs);
            }
        }
        return PerfGateDecision.pass(steps, runDurationSeconds, marginMinutes, budgetMs);
    }

    private static String legacyGateKey(String stepCode) {
        return switch (stepCode) {
            case "OWNERSHIP", "TARGET_MATCH" -> "TARGET_ENV";
            case "ISOLATION_DECLARATION", "ISOLATION_ALLOWED" -> "ISOLATION_PRECHECK";
            default -> stepCode;
        };
    }

    private Map<String, Object> loadEnv(long envId) {
        return jdbc.queryForList("SELECT id, project_id, env_type, base_url FROM tp_project_env WHERE id = :id",
                new MapSqlParameterSource("id", envId)).stream().findFirst().orElse(null);
    }

    private Map<String, Object> loadWhitelist(long projectId, long envId) {
        return jdbc.queryForList("SELECT id, max_concurrency, allowed_modes::text AS allowed_modes "
                        + "FROM tp_perf_whitelist WHERE project_id = :p AND env_id = :e",
                new MapSqlParameterSource("p", projectId).addValue("e", envId))
                .stream().findFirst().orElse(null);
    }

    private Map<String, Object> loadApproved(PerfGateCheckInput input) {
        return jdbc.queryForList("SELECT id, handled_at FROM tp_perf_approval "
                        + "WHERE project_id = :p AND env_id = :e AND status = 3 "
                        + "AND isolation_mode = :m AND requested_concurrency >= :c "
                        + "ORDER BY handled_at DESC NULLS LAST, id DESC LIMIT 1",
                new MapSqlParameterSource("p", input.projectId()).addValue("e", input.envId())
                        .addValue("m", input.isolationMode()).addValue("c", input.concurrency()))
                .stream().findFirst().orElse(null);
    }

    private List<String> allowedModes(Map<String, Object> whitelist) {
        String raw = (String) whitelist.get("allowed_modes");
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<List<String>>() {
            });
        } catch (Exception dirty) {
            return List.of();
        }
    }

    private String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            String normalized = url.contains("://") ? url : "//" + url;
            String host = java.net.URI.create(normalized).getHost();
            return host == null ? "" : host.toLowerCase();
        } catch (IllegalArgumentException badUrl) {
            return url.toLowerCase();
        }
    }
}
