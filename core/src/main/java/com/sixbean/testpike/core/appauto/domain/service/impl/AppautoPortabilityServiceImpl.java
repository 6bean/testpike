package com.sixbean.testpike.core.appauto.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.util.UiActionContract;
import com.sixbean.testpike.core.appauto.api.dto.AppautoEngineInfo;
import com.sixbean.testpike.core.appauto.api.dto.AppautoEnvProfileInfo;
import com.sixbean.testpike.core.appauto.api.dto.AppautoPortabilityInfo;
import com.sixbean.testpike.core.appauto.domain.service.AppautoEngineService;
import com.sixbean.testpike.core.appauto.domain.service.AppautoEnvProfileService;
import com.sixbean.testpike.core.appauto.domain.service.AppautoPortabilityService;
import com.sixbean.testpike.core.executor.domain.component.AppAutoDispatchSupport;
import com.sixbean.testpike.core.script.api.dto.ScriptInfo;
import com.sixbean.testpike.core.script.domain.service.ScriptService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;


@Service
public class AppautoPortabilityServiceImpl implements AppautoPortabilityService {

    
    private static final Map<String, String> FRAMEWORK_FLAG_KEYS = Map.of("FLUTTER", "flutter");

    private final JdbcTemplate jdbcTemplate;
    private final ScriptService scriptService;
    private final AppAutoDispatchSupport dispatchSupport;
    private final AppautoEngineService engineService;
    private final AppautoEnvProfileService envProfileService;

    public AppautoPortabilityServiceImpl(JdbcTemplate jdbcTemplate,
                                         ScriptService scriptService,
                                         AppAutoDispatchSupport dispatchSupport,
                                         AppautoEngineService engineService,
                                         AppautoEnvProfileService envProfileService) {
        this.jdbcTemplate = jdbcTemplate;
        this.scriptService = scriptService;
        this.dispatchSupport = dispatchSupport;
        this.engineService = engineService;
        this.envProfileService = envProfileService;
    }

    @Override
    public AppautoPortabilityInfo precheck(long projectId, long caseId, String engineCode) {
        Map<String, Object> caseRow = jdbcTemplate.query(
                "SELECT project_id, title, automation_target, appauto_engine, appauto_env_profile_id"
                        + " FROM tp_test_case WHERE id = ? AND deleted = 0",
                rs -> rs.next() ? Map.of(
                        "projectId", rs.getLong("project_id"),
                        "title", String.valueOf(rs.getString("title")),
                        "automationTarget", String.valueOf(rs.getString("automation_target")),
                        "engine", String.valueOf(rs.getString("appauto_engine")),
                        "profileId", rs.getObject("appauto_env_profile_id") == null
                                ? "" : String.valueOf(rs.getLong("appauto_env_profile_id")))
                        : null,
                caseId);
        if (caseRow == null) {
            throw new ServiceException("用例不存在或已删除（id=" + caseId + "）——"
                    + "迁移预检需要有现行版本的用例，请先确认用例在列", BizErrorCode.VALIDATION_ERROR);
        }
        if (!Long.valueOf(projectId).equals(caseRow.get("projectId"))) {
            throw new ServiceException("该用例不属于当前项目，无法在本项目发起迁移预检——"
                    + "请切换到用例所属项目后重试", BizErrorCode.VALIDATION_ERROR);
        }
        if (engineCode == null || engineCode.isBlank()) {
            throw new ServiceException("迁移预检必须指定目标引擎（换哪个引擎跑）——"
                    + "请在引擎选择卡片中选择目标引擎后重试", BizErrorCode.VALIDATION_ERROR);
        }
        AppautoEngineInfo target = engineService.getByCode(
                engineCode.trim().toUpperCase(Locale.ROOT));
        if (target.status() != 1) {
            throw new ServiceException("目标引擎「" + target.name() + "（" + target.code()
                    + "）」已停用/下架，预检不给可跑假象——请在引擎目录启用或换引擎",
                    BizErrorCode.VALIDATION_ERROR);
        }
        ScriptInfo script = scriptService.getByCaseId(caseId);
        List<Map<String, Object>> steps = dispatchSupport.extractAppSteps(
                script == null ? null : script.actionJson());
        if (steps.isEmpty()) {
            throw new ServiceException("该用例未编排 app.* IR 步骤（或含 web 步骤混排）——"
                    + "迁移预检仅适用于纯 App 步骤用例，请先在用例的 App 步骤区编排动作（"
                    + String.join("/", UiActionContract.IR_VERBS.stream()
                            .map(v -> "app." + v).toList()) + "）",
                    BizErrorCode.VALIDATION_ERROR);
        }

        List<String> rewrites = collectVerbAndFrameworkGaps(target, steps,
                caseRow.get("profileId").toString());
        rewrites.addAll(collectWebviewLandingGaps(target, steps));
        List<String> degradations = List.of();
        String sourceEngine = caseRow.get("engine").toString();
        if (!sourceEngine.isBlank() && !"null".equals(sourceEngine)) {
            degradations = collectFlagShrink(sourceEngine, target);
        }
        String verdict = !rewrites.isEmpty() ? AppautoPortabilityInfo.REWRITE
                : !degradations.isEmpty() ? AppautoPortabilityInfo.DEGRADED
                        : AppautoPortabilityInfo.RUNNABLE;
        List<String> items = new ArrayList<>(rewrites.size() + degradations.size());
        items.addAll(rewrites);
        items.addAll(degradations);
        return new AppautoPortabilityInfo(target.code(), target.name(), caseId,
                caseRow.get("title").toString(), verdict, List.copyOf(items), steps.size());
    }

 
    private List<String> collectVerbAndFrameworkGaps(AppautoEngineInfo target,
                                                     List<Map<String, Object>> steps,
                                                     String profileIdRef) {
        List<String> gaps = new ArrayList<>();
        Map<String, Boolean> caps = target.capabilities() == null ? Map.of() : target.capabilities();
        for (Map<String, Object> step : steps) {
            String action = String.valueOf(step.get("action"));
            if (!UiActionContract.isAppAction(action)) {
                continue;
            }
            String verb = action.substring(UiActionContract.APP_NAMESPACE.length());
            if (!Boolean.TRUE.equals(caps.get(verb))) {
                gaps.add("第 " + step.getOrDefault("stepNo", "?") + " 步「" + action
                        + "」——目标引擎「" + target.name() + "」描述符能力位图缺该动词，"
                        + "需改写：换用该引擎已具备的动作组合实现同等意图，或改选支持该动词的引擎");
            }
        }
        for (String note : collectFrameworkGaps(profileIdRef, target)) {
            gaps.add(note);
        }
        return gaps;
    }

    
    private List<String> collectWebviewLandingGaps(AppautoEngineInfo target,
                                                   List<Map<String, Object>> steps) {
        boolean targetCapable = target.capabilities() != null
                && Boolean.TRUE.equals(target.capabilities().get("webview-hybrid"));
        if (targetCapable) {
            return List.of();
        }
        List<String> gaps = new ArrayList<>();
        for (Map<String, Object> step : steps) {
            if (!UiActionContract.stepRequestsWebview(step)) {
                continue;
            }
            gaps.add("第 " + step.getOrDefault("stepNo", "?") + " 步「" + step.get("action")
                    + "」标记落 WebView 区域（webview 旗标）——目标引擎「" + target.name()
                    + "」不支持混合 WebView 定位，需改写：换 Appium(WEBVIEW context)/automator"
                    + "(web 节点)/Mobilewright(无障碍树穿透) 任一支持混合载体的引擎，"
                    + "或改写该步为原生控件定位（禁静默回落原生层冒充命中）");
        }
        return gaps;
    }

    
    private List<String> collectFrameworkGaps(String profileIdRef, AppautoEngineInfo target) {
        if (profileIdRef == null || profileIdRef.isBlank()) {
            return List.of();
        }
        AppautoEnvProfileInfo profile = envProfileService.get(Long.parseLong(profileIdRef));
        Object framework = profile.payload() == null ? null : profile.payload().get("appFramework");
        String fw = framework == null ? "" : String.valueOf(framework).trim().toUpperCase(Locale.ROOT);
        String flagKey = FRAMEWORK_FLAG_KEYS.get(fw);
        if (flagKey == null) {
            return List.of();
        }
        boolean supported = target.capabilities() != null
                && Boolean.TRUE.equals(target.capabilities().get(flagKey));
        if (supported) {
            return List.of();
        }
        return List.of("被测应用框架为 " + fw + "（载体档案「" + profile.name()
                + "」声明）——目标引擎「" + target.name() + "」不具备 " + flagKey
                + " 能力，该组合无法稳定驱动控件定位，需改写：为该应用改选支持 " + fw
                + " 的引擎（如 Maestro/Appium），或调整被测应用形态");
    }

    
    private List<String> collectFlagShrink(String sourceEngineCode, AppautoEngineInfo target) {
        AppautoEngineInfo source;
        try {
            source = engineService.getByCode(sourceEngineCode.toUpperCase(Locale.ROOT));
        } catch (ServiceException e) {
            return List.of();
        }
        if (source.capabilities() == null) {
            return List.of();
        }
        Set<String> notes = new LinkedHashSet<>();
        for (String flag : UiActionContract.CAPABILITY_FLAGS) {
            boolean src = Boolean.TRUE.equals(source.capabilities().get(flag));
            boolean dst = target.capabilities() != null
                    && Boolean.TRUE.equals(target.capabilities().get(flag));
            if (src && !dst) {
                notes.add("能力收窄提示：原引擎「" + source.name() + "」具备 " + flag
                        + " 能力，目标引擎「" + target.name() + "」不具备——相关步骤仍能执行，"
                        + "但该形态能力（如混合页内定位）将回落为原生控件视角，结果需人工复核");
            }
        }
        return List.copyOf(notes);
    }
}
