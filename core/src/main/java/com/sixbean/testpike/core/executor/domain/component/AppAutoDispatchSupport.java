package com.sixbean.testpike.core.executor.domain.component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.util.ExploreEnvelopeContract;
import com.sixbean.testpike.core.appauto.api.dto.AppautoEngineInfo;
import com.sixbean.testpike.core.appauto.api.dto.AppautoEnvProfileInfo;
import com.sixbean.testpike.core.appauto.api.dto.AppautoPopupRuleView;
import com.sixbean.testpike.core.appauto.domain.component.AppautoConfigReader;
import com.sixbean.testpike.core.appauto.domain.service.AppautoEngineService;
import com.sixbean.testpike.core.appauto.domain.service.AppautoEnvProfileService;
import com.sixbean.testpike.core.appauto.domain.service.AppautoPopupRuleService;
import com.sixbean.testpike.core.credential.domain.component.CredPlaceholderInjector;
import com.sixbean.testpike.core.executor.domain.service.ExecutorService;
import com.sixbean.testpike.core.plugin.api.dto.AppliedPluginView;
import com.sixbean.testpike.core.plugin.domain.service.PluginService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;


@Component
public class AppAutoDispatchSupport {

    private static final Logger log = LoggerFactory.getLogger(AppAutoDispatchSupport.class);

 
    public static final String ENVELOPE_KIND = "app-auto-run";

    private final JdbcTemplate jdbcTemplate;
    private final ExecutorService executorService;
    private final ObjectMapper objectMapper;
    private final AppautoConfigReader appautoConfigReader;
    private final AppautoEngineService engineService;
    private final AppautoEnvProfileService envProfileService;
    private final CredPlaceholderInjector credPlaceholderInjector;
    private final AppautoPopupRuleService popupRuleService;
    private final PluginService pluginService;

    /** Approved popup-rule row status in tp_appauto_popup_rule (human-approved set). */
    private static final int POPUP_RULE_STATUS_APPROVED = 2;

    /** Jackson read target for a rule payload parsed into the consumer object shape. */
    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<Map<String, Object>>() { };

    public AppAutoDispatchSupport(JdbcTemplate jdbcTemplate,
                                  ExecutorService executorService,
                                  ObjectMapper objectMapper,
                                  AppautoConfigReader appautoConfigReader,
                                  AppautoEngineService engineService,
                                  AppautoEnvProfileService envProfileService,
                                  CredPlaceholderInjector credPlaceholderInjector,
                                  @Lazy AppautoPopupRuleService popupRuleService,
                                  PluginService pluginService) {
        this.jdbcTemplate = jdbcTemplate;
        this.executorService = executorService;
        this.objectMapper = objectMapper;
        this.appautoConfigReader = appautoConfigReader;
        this.engineService = engineService;
        this.envProfileService = envProfileService;
        this.credPlaceholderInjector = credPlaceholderInjector;
        this.popupRuleService = popupRuleService;
        this.pluginService = pluginService;
    }


    /**
 * deviceKindCodes/runtimeType/runtimeVersionReq/capabilities/driverRequirements/
 */
    public Map<String, Object> buildEnvelope(long projectId, String engineCode,
                                             Long envProfileId,
                                             List<Map<String, Object>> steps) {
        return buildEnvelope(projectId, engineCode, null, envProfileId, steps);
    }

    
    public Map<String, Object> buildEnvelope(long projectId, String engineCode,
                                             String automationTarget, Long envProfileId,
                                             List<Map<String, Object>> steps) {
        if (steps == null || steps.isEmpty()) {
            throw new ServiceException("APP 自动化任务步骤为空——请先在用例的 App 步骤区"
                    + "编排动作（app.launch/app.tap 等）再建单；入口：「用例管理」打开该用例的 App 步骤编排区", BizErrorCode.VALIDATION_ERROR);
        }
        String code = resolveEngineCodeForDispatch(engineCode, automationTarget);
        AppautoEngineInfo engine = engineService.getByCode(code);
        assertEngineDispatchable(engine);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("kind", ENVELOPE_KIND);
        envelope.put("engine", engineProjection(engine));
        AppautoEnvProfileInfo selectedProfile = null;
        if (envProfileId != null) {
            selectedProfile = requireProfileInProject(projectId, envProfileId);
            Map<String, Object> profileView = new LinkedHashMap<>();
            if (selectedProfile.payload() != null) {
                profileView.putAll(selectedProfile.payload());
            }
            profileView.put("id", selectedProfile.id());
            profileView.put("name", selectedProfile.name());
            profileView.put("profileKind", selectedProfile.profileKind());
            if (selectedProfile.loginRecipeAssetId() != null) {
                profileView.put("loginRecipeAssetId", selectedProfile.loginRecipeAssetId());
            }
            envelope.put("envProfile", profileView);
            envelope.put("envProfileKind", selectedProfile.profileKind());
        }
        // Login-recipe prepend (segment B1b): the bound recipe's current-head steps run
        // before the case steps, so recipe and case share the same injection point.
        List<Map<String, Object>> orderedSteps = new ArrayList<>(steps);
        if (selectedProfile != null && selectedProfile.loginRecipeAssetId() != null) {
            orderedSteps.addAll(0, envProfileService.resolveLoginRecipeSteps(
                    selectedProfile.loginRecipeAssetId()));
        }
        List<Map<String, Object>> injectedSteps = credPlaceholderInjector.inject(projectId, orderedSteps);
        credPlaceholderInjector.assertNoLeftover(injectedSteps);
        envelope.put("steps", injectedSteps);
        envelope.put("visualFallback", appautoConfigReader.visualFallbackKeys());
        envelope.put("perfKeys", appautoConfigReader.perfKeys());
        // Runtime stamping of the approved popup-rule set .
        envelope.put("popupRules", stampApprovedPopupRules(projectId));
        // Industry-package linkage producer face: merged param snapshots of the project's
        // enabled applied plugins. Empty set keeps the key absent (no-key discipline shared
        // with the other optional stamps) so the executor never guesses an empty contract.
        Map<String, Object> industryParams = stampIndustryParams(projectId);
        if (!industryParams.isEmpty()) {
            envelope.put("industryParams", industryParams);
        }
        return envelope;
    }

    /**
     * Merge the param snapshots of the project's enabled applied plugins into one flat map.
     * <p>Read channel is the single plugin service ({@code listApplied}) — no second SQL
     * path. Order = listApplied order (applied_at ascending): the first applied plugin owns
     * a shared key, a later duplicate is skipped with a log line naming the key (a silent
     * override would make the effective value depend on merge order without a trace).
     * A disabled plugin contributes nothing (disabled = no new execution effect).
     */
    private Map<String, Object> stampIndustryParams(long projectId) {
        Map<String, Object> merged = new LinkedHashMap<>();
        for (AppliedPluginView row : pluginService.listApplied(projectId)) {
            if (!row.enabled() || row.paramSnapshot() == null) {
                continue;
            }
            for (Map.Entry<String, Object> entry : row.paramSnapshot().entrySet()) {
                if (merged.containsKey(entry.getKey())) {
                    log.info("Industry param '{}' kept from the earlier applied plugin "
                            + "(project {}, plugin {} does not override it)",
                            entry.getKey(), projectId, row.pluginName());
                    continue;
                }
                merged.put(entry.getKey(), entry.getValue());
            }
        }
        return merged;
    }

    /**
     * Stamp the approved popup rules of one project into the dispatch envelope.
     * <p>Row shape is the executor consumer contract ({@code app_worker._popup_rules_of}
     * reads {@code rule} as a parsed object, never as raw JSON text). The rule library
     * is a pure bonus path: a row whose payload cannot be parsed as a JSON object is
     * dropped with an honest log line instead of failing the whole dispatch, and the
     * key is always present (empty list = no rules shipped) so the executor never guesses.
     */
    private List<Map<String, Object>> stampApprovedPopupRules(long projectId) {
        List<Map<String, Object>> stamped = new ArrayList<>();
        for (AppautoPopupRuleView row : popupRuleService.list(
                projectId, POPUP_RULE_STATUS_APPROVED, appautoConfigReader.popupRuleEnvelopeLimit())) {
            Map<String, Object> body = parseRuleBody(row);
            if (body == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.id());
            item.put("title", row.title());
            item.put("rule", body);
            stamped.add(item);
        }
        return stamped;
    }

    /** Parse one rule row's JSON payload into the consumer object shape, null when unusable. */
    private Map<String, Object> parseRuleBody(AppautoPopupRuleView row) {
        try {
            return objectMapper.readValue(row.rule(), MAP_TYPE);
        } catch (Exception e) {
            log.warn("Popup rule row {} carries a non-object payload — dropped from the "
                    + "dispatch envelope (rule library stays a silent bonus path)", row.id());
            return null;
        }
    }

    
    public Map<String, Object> engineProjection(AppautoEngineInfo engine) {
        Map<String, Object> engineView = new LinkedHashMap<>();
        engineView.put("code", engine.code());
        engineView.put("name", engine.name());
        engineView.put("status", engine.status());
        engineView.put("platformCodes", engine.platformCodes());
        engineView.put("deviceKindCodes", engine.deviceKindCodes());
        engineView.put("runtimeType", engine.runtimeType());
        engineView.put("runtimeVersionReq", engine.runtimeVersionReq());
        engineView.put("capabilities", engine.capabilities());
        engineView.put("driverRequirements", engine.driverRequirements());
        engineView.put("envCheckSpec", engine.envCheckSpec());
        return engineView;
    }

    
    public void assertEngineDispatchable(AppautoEngineInfo engine) {
        if (engine.status() != 1) {
            throw new ServiceException("引擎「" + engine.name() + "（" + engine.code()
                    + "）」已停用/下架，不能作为建单引擎——请在引擎目录选择启用中的引擎，"
                    + "或到「APP 自动化 → 引擎中心」查看安装与启用指引",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    
    public AppautoEngineInfo resolveDefaultEngine() {
        AppautoEngineInfo engine = engineService.getByCode(appautoConfigReader.defaultEngine());
        assertEngineDispatchable(engine);
        return engine;
    }

    
    public Map<String, Object> parseEnvelopeTolerant(String actionJson) {
        return parseEnvelope(actionJson);
    }

    
    public void validateEnvelope(long projectId, String actionJson) {
        Map<String, Object> envelope = parseEnvelopeStrict(actionJson);
        if (!ENVELOPE_KIND.equals(str(envelope.get("kind")))) {
            throw new ServiceException("APP 自动化任务信封 kind 必须为 " + ENVELOPE_KIND
                    + "（当前=" + orMissing(envelope.get("kind")) + "）；可在「用例管理」把用例自动化目标设为 app 后重新建单",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (!(envelope.get("engine") instanceof Map<?, ?> engineMap)
                || str(engineMap.get("code")) == null
                || str(engineMap.get("code")).isBlank()) {
            throw new ServiceException("APP 自动化任务信封缺少引擎描述（engine.code）——"
                    + "请经建单装配面下发，或选择有效引擎后重试；可在「用例管理」编辑用例改选引擎（engine.code 格式：已启用引擎的编码）", BizErrorCode.VALIDATION_ERROR);
        }
        AppautoEngineInfo engine = engineService.getByCode(
                str(engineMap.get("code")).trim().toUpperCase(Locale.ROOT));
        if (engine.status() != 1) {
            throw new ServiceException("任务引擎「" + engine.name() + "（" + engine.code()
                    + "）」已停用/下架，无法派发——请在引擎目录启用或换引擎后重试",
                    BizErrorCode.VALIDATION_ERROR);
        }
        checkDimWithinFace(engineMap, "platform", engine.platformCodes(), "平台");
        checkDimWithinFace(engineMap, "deviceKind", engine.deviceKindCodes(), "设备型");
        if (envelope.get("envProfile") instanceof Map<?, ?> profileMap
                && profileMap.get("id") instanceof Number profileIdNum
                && projectId > 0) {
            requireProfileInProject(projectId, profileIdNum.longValue());
        }
    }

    
    private AppautoEnvProfileInfo requireProfileInProject(long projectId, long envProfileId) {
        AppautoEnvProfileInfo profile = envProfileService.get(envProfileId);
        if (profile.projectId() == null || profile.projectId() != projectId) {
            throw new ServiceException("环境档案「" + profile.name() + "」不属于当前项目，"
                    + "无法用于本次建单——请在用例所属项目的「环境档案库」选择档案",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return profile;
    }

    
    private String resolveEngineCodeForDispatch(String engineCode, String automationTarget) {
        if (engineCode != null && !engineCode.isBlank()) {
            return engineCode.trim().toUpperCase(Locale.ROOT);
        }
        String target = normalizeTarget(automationTarget);
        if (target != null && !"web".equals(target)) {
            String routed = routeEngineByCapability(target);
            if (routed != null) {
                return routed;
            }
            log.info("自动化目标 {} 无能力对账绿的在线引擎，回退 K8 默认引擎（兜底不拒单，"
                    + "可执行性由建单健康闸/排队位说明呈现）", target);
        }
        return appautoConfigReader.defaultEngine();
    }

    
    private String routeEngineByCapability(String target) {
        List<String> wanted = wantedPlatformsForTarget(target);
        List<Map<String, Object>> online = executorService.onlineCapabilities();
        if (online.isEmpty()) {
            return null;
        }
        return engineService.listEngines(false).stream()
                .filter(e -> e.platformCodes() != null
                        && e.platformCodes().stream().anyMatch(wanted::contains))
                .filter(e -> online.stream().anyMatch(caps ->
                        covers(caps, "engines", e.code())
                                && wanted.stream().anyMatch(p -> covers(caps, "platforms", p))))
                .min(java.util.Comparator.comparingInt(AppautoEngineInfo::defaultPriority))
                .map(AppautoEngineInfo::code)
                .orElse(null);
    }

    
    private List<String> wantedPlatformsForTarget(String target) {
        return switch (target) {
            case "mp" -> List.of("MP");
            case "harmony" -> List.of("HARMONY_NEXT");
            default -> List.of("ANDROID", "IOS");
        };
    }

    
    public AppAutoCaseTarget validateCaseTargetFields(long projectId, String rawTarget,
                                                      String rawEngine, Long rawProfileId) {
        String target = normalizeTarget(rawTarget);
        if (rawTarget != null && !rawTarget.isBlank() && target == null) {
            throw new ServiceException("用例自动化目标仅支持 web/app/mp/harmony/api（当前=" + rawTarget.trim()
                    + "）——APP/小程序/鸿蒙用例请选 app/mp/harmony，接口用例选 api，网页用例保持 web 或不挂（入口：「用例管理」编辑用例的自动化目标）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String engine = rawEngine == null || rawEngine.isBlank() ? null
                : rawEngine.trim().toUpperCase(Locale.ROOT);
        Long profileId = rawProfileId == null || rawProfileId == 0L ? null : rawProfileId;
        if (target == null || "web".equals(target) || "api".equals(target)) {
            if (engine != null || profileId != null) {
                throw new ServiceException("网页（web）、接口（api）或未挂自动化目标的用例不能指定引擎/环境档案"
                        + "——APP 自动化用例请先把自动化目标设为 app（移动 App）、mp（小程序）或 harmony（鸿蒙）（入口：「用例管理」编辑用例）",
                        BizErrorCode.VALIDATION_ERROR);
            }
            return new AppAutoCaseTarget(target, null, null);
        }
        if (engine != null) {
            AppautoEngineInfo info = engineService.getByCode(engine);
            if (info.status() != 1) {
                throw new ServiceException("引擎「" + info.name() + "（" + info.code()
                        + "）」已停用/下架，不能挂为用例目标——请在引擎目录启用或换引擎",
                        BizErrorCode.VALIDATION_ERROR);
            }
            List<String> wanted = wantedPlatformsForTarget(target);
            if (info.platformCodes() == null
                    || info.platformCodes().stream().noneMatch(wanted::contains)) {
                throw new ServiceException("引擎「" + info.name() + "（" + info.code()
                        + "）」不支持该自动化目标——目标 " + target + " 需要平台 "
                        + String.join("/", wanted) + "，该引擎描述符声明面="
                        + info.platformCodes() + "，请换引擎、改为自动或修正目标", BizErrorCode.VALIDATION_ERROR);
            }
        }
        if ("mp".equals(target)) {
            if (profileId == null) {
                throw new ServiceException("小程序（MP）目标用例必须选择环境档案——档案含"
                        + "微信开发者工具路径与小程序工程路径（projectPath），请在"
                        + "「APP 自动化 → 环境档案库」建档后选用", BizErrorCode.VALIDATION_ERROR);
            }
            AppautoEnvProfileInfo profile = requireProfileInProject(projectId, profileId);
            Object projectPath = profile.payload() == null ? null : profile.payload().get("projectPath");
            if (projectPath == null || String.valueOf(projectPath).isBlank()) {
                throw new ServiceException("环境档案「" + profile.name() + "」缺少工程路径"
                        + "（projectPath）——小程序引擎启动需要该键，请到「APP 自动化 → "
                        + "环境档案库」编辑档案补全后再挂", BizErrorCode.VALIDATION_ERROR);
            }
        }
        return new AppAutoCaseTarget(target, engine, profileId);
    }

    
    public AppAutoCaseTarget validateCaseTargetUpdate(long projectId,
                                                      String currentTarget, String currentEngine,
                                                      Long currentProfileId,
                                                      String rawTarget, String rawEngine,
                                                      Long rawProfileId) {
        String effTarget = rawTarget == null ? currentTarget
                : normalizeTarget(rawTarget);
        if (rawTarget != null && !rawTarget.isBlank() && effTarget == null) {
            throw new ServiceException("用例自动化目标仅支持 web/app/mp/harmony/api（当前=" + rawTarget.trim()
                    + "）——清空挂位请传 web；可在「用例管理」编辑用例调整", BizErrorCode.VALIDATION_ERROR);
        }
        // Linkage: explicitly switching the target back to web/api/unmounted clears engine
        // and profile automatically (one operation intent stays self-consistent — do not
        // ask callers to send empty strings a second time). If the caller still supplies a
        // non-blank engine, validateCaseTargetFields' must-be-empty gate rejects in plain words.
        boolean demotedToWeb = rawTarget != null
                && (effTarget == null || "web".equals(effTarget) || "api".equals(effTarget));
        String effEngine = demotedToWeb && rawEngine == null ? null
                : (rawEngine == null ? currentEngine
                        : (rawEngine.isBlank() ? null : rawEngine.trim().toUpperCase(Locale.ROOT)));
        Long effProfile = demotedToWeb && rawProfileId == null ? null
                : (rawProfileId == null ? currentProfileId
                        : (rawProfileId == 0L ? null : rawProfileId));
        return validateCaseTargetFields(projectId, effTarget, effEngine, effProfile);
    }

    /** Target normalization (trim + toLowerCase); outside web/app/mp/harmony/api returns
     *  null and the caller decides between a plain-word rejection and keeping the old value. */
    private String normalizeTarget(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String t = raw.trim().toLowerCase(Locale.ROOT);
        return switch (t) {
            case "web", "app", "mp", "harmony", "api" -> t;
            default -> null;
        };
    }

    
    private void checkDimWithinFace(Map<?, ?> engineMap, String dimKey,
                                    List<String> face, String dimName) {
        Object wanted = engineMap.get(dimKey);
        String s = str(wanted);
        if (s == null || s.isBlank()) {
            return;
        }
        if (face == null || !face.contains(s)) {
            throw new ServiceException("任务引擎「" + str(engineMap.get("code")) + "」不支持"
                    + dimName + " " + s + "（描述符声明面=" + face + "）——请换引擎或修正目标"
                    + dimName + "；可在「用例管理」编辑用例换选支持该维度值的引擎", BizErrorCode.VALIDATION_ERROR);
        }
    }

    private Map<String, Object> parseEnvelopeStrict(String actionJson) {
        if (actionJson == null || actionJson.isBlank()) {
            throw new ServiceException("APP 自动化任务信封不能为空，请经「用例管理」建单装配面提交",
                    BizErrorCode.VALIDATION_ERROR);
        }
        try {
            Object parsed = objectMapper.readValue(actionJson, Map.class);
            if (!(parsed instanceof Map)) {
                throw new ServiceException("APP 自动化任务信封必须是 JSON 对象，请按「用例管理」建单装配面模板改成合法 JSON 对象后提交",
                        BizErrorCode.VALIDATION_ERROR);
            }
            return castMap(parsed);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("APP 自动化任务信封不是合法 JSON：" + e.getMessage() + "；请修正 JSON 拼写后重新提交（入口：「用例管理」建单装配面）",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    private String orMissing(Object value) {
        String s = str(value);
        return s == null || s.isBlank() ? "缺" : s;
    }

    
    public boolean isAppAutoEnvelope(String actionJson) {
        return ENVELOPE_KIND.equals(envelopeField(actionJson, "kind"));
    }

    
    public String envelopeEngineCode(String actionJson) {
        Map<String, Object> envelope = parseEnvelope(actionJson);
        if (envelope == null || !(envelope.get("engine") instanceof Map<?, ?> engineMap)) {
            return null;
        }
        String code = str(engineMap.get("code"));
        return code == null || code.isBlank() ? null : code;
    }

    
    public String envelopePlatformCode(String actionJson) {
        Map<String, Object> envelope = parseEnvelope(actionJson);
        if (envelope == null || !ENVELOPE_KIND.equals(str(envelope.get("kind")))
                || !(envelope.get("engine") instanceof Map<?, ?> engineMap)) {
            return null;
        }
        String platform = str(engineMap.get("platform"));
        return platform == null || platform.isBlank() ? null : platform;
    }

    
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> extractAppSteps(String scriptActionJson) {
        if (scriptActionJson == null || scriptActionJson.isBlank()) {
            return List.of();
        }
        try {
            Map<String, Object> root = objectMapper.readValue(scriptActionJson, Map.class);
            if (!(root.get("steps") instanceof List<?> list) || list.isEmpty()) {
                return List.of();
            }
            List<Map<String, Object>> steps = new java.util.ArrayList<>(list.size());
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> stepMap)) {
                    return List.of();
                }
                String action = str(stepMap.get("action"));
                if (action == null || !com.sixbean.testpike.common.util.UiActionContract
                        .isAppAction(action)) {
                    return List.of();
                }
                steps.add((Map<String, Object>) stepMap);
            }
            return steps;
        } catch (Exception e) {
            log.debug("脚本步骤提取失败（按无 App 步骤跳过，脏脚本不炸派发）: {}", e.getMessage());
            return List.of();
        }
    }

    
    public String leaseDeviceSerial(String actionJson) {
        Map<String, Object> envelope = parseEnvelope(actionJson);
        if (envelope == null || !ENVELOPE_KIND.equals(str(envelope.get("kind")))) {
            return null;
        }
        Object leaseRef = envelope.get("leaseRef");
        if (!(leaseRef instanceof Map<?, ?> refMap)) {
            return null;
        }
        String serial = str(refMap.get("serial"));
        return serial == null || serial.isBlank() ? null : serial;
    }

    
    public String exploreMobileLeaseSerial(String actionJson) {
        Map<String, Object> envelope = parseEnvelope(actionJson);
        if (envelope == null || !ExploreEnvelopeContract.isMobileEnvelope(
                str(envelope.get("kind")), str(envelope.get("target")))) {
            return null;
        }
        Object leaseRef = envelope.get("leaseRef");
        if (!(leaseRef instanceof Map<?, ?> refMap)) {
            return null;
        }
        String serial = str(refMap.get("serial"));
        return serial == null || serial.isBlank() ? null : serial;
    }

    
    public Long leaseDeviceId(String actionJson) {
        Map<String, Object> envelope = parseEnvelope(actionJson);
        if (envelope == null || !ENVELOPE_KIND.equals(str(envelope.get("kind")))) {
            return null;
        }
        Object leaseRef = envelope.get("leaseRef");
        if (!(leaseRef instanceof Map<?, ?> refMap)) {
            return null;
        }
        Object deviceId = refMap.get("deviceId");
        return deviceId instanceof Number n ? n.longValue() : null;
    }

    
    public boolean shouldSkipForExecutor(String actionJson, Map<String, Object> capabilities) {
        Map<String, Object> envelope = parseEnvelope(actionJson);
        if (envelope == null || !ENVELOPE_KIND.equals(str(envelope.get("kind")))) {
            return false;
        }
        Map<String, Object> dims = engineDims(envelope);
        if (dims.isEmpty()) {
            return false;
        }
        return !matches(capabilities, dims);
    }

    
    public boolean hasCapableExecutor(long taskId) {
        return hasCapableExecutorForEnvelope(taskActionJson(taskId));
    }

    
    public boolean hasCapableExecutorForEnvelope(String actionJson) {
        Map<String, Object> envelope = parseEnvelope(actionJson);
        if (envelope == null || !ENVELOPE_KIND.equals(str(envelope.get("kind")))) {
            return true;
        }
        Map<String, Object> dims = engineDims(envelope);
        if (dims.isEmpty()) {
            return true;
        }
        List<Map<String, Object>> online = executorService.onlineCapabilities();
        if (dims.get("code") == null) {
            return online.stream().anyMatch(caps ->
                    covers(caps, "platforms", dims.get("platform"))
                            && covers(caps, "deviceKinds", dims.get("deviceKind")));
        }
        return online.stream().anyMatch(caps -> matches(caps, dims));
    }

    
    public String explainNoCapableExecutor(long taskId) {
        return explainNoCapableExecutorForEnvelope(taskActionJson(taskId));
    }

 
    public String explainNoCapableExecutorForEnvelope(String actionJson) {
        if (hasCapableExecutorForEnvelope(actionJson)) {
            return null;
        }
        Map<String, Object> dims = engineDims(parseEnvelope(actionJson));
        int onlineCount = executorService.onlineCapabilities().size();
        String need = dims.get("code") == null ? "（未指明引擎，按平台/设备型匹配）"
                : "引擎 " + dims.get("code");
        return "该 APP 自动化任务需要 " + need
                + "（平台 " + orUnknown(dims.get("platform"))
                + " / 设备型 " + orUnknown(dims.get("deviceKind")) + "），"
                + "当前在线执行器 " + onlineCount + " 台，具备该能力的 0 台——任务无法派发执行。"
                + "请在执行器机安装对应引擎运行时并重新注册（能力=执行器自探测如实上报，缺件不申报）。";
    }


    
    private boolean matches(Map<String, Object> caps, Map<String, Object> dims) {
        if (caps == null || caps.isEmpty()) {
            return false;
        }
        if (dims.get("code") != null && !covers(caps, "engines", dims.get("code"))) {
            return false;
        }
        if (dims.get("platform") != null && !covers(caps, "platforms", dims.get("platform"))) {
            return false;
        }
        return dims.get("deviceKind") == null
                || covers(caps, "deviceKinds", dims.get("deviceKind"));
    }

    
    public boolean covers(Map<String, Object> caps, String faceKey, Object wanted) {
        if (wanted == null) {
            return false;
        }
        Object face = caps.get(faceKey);
        return face instanceof List<?> list && list.contains(str(wanted));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> engineDims(Map<String, Object> envelope) {
        if (envelope == null) {
            return Map.of();
        }
        Object engine = envelope.get("engine");
        if (!(engine instanceof Map)) {
            return Map.of();
        }
        Map<String, Object> m = (Map<String, Object>) engine;
        Map<String, Object> dims = new java.util.LinkedHashMap<>();
        putIfPresent(dims, "code", m.get("code"));
        putIfPresent(dims, "platform", m.get("platform"));
        putIfPresent(dims, "deviceKind", m.get("deviceKind"));
        return dims;
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        String s = str(value);
        if (s != null && !s.isBlank()) {
            target.put(key, s);
        }
    }

    private Map<String, Object> parseEnvelope(String actionJson) {
        if (actionJson == null || actionJson.isBlank()) {
            return null;
        }
        try {
            Object parsed = objectMapper.readValue(actionJson, Map.class);
            return parsed instanceof Map<?, ?> ? castMap(parsed) : null;
        } catch (Exception e) {
            log.debug("信封解析失败（非 app 判定，脏单交消费侧）: {}", e.getMessage());
            return null;
        }
    }

    private String envelopeField(String actionJson, String field) {
        Map<String, Object> envelope = parseEnvelope(actionJson);
        return envelope == null ? null : str(envelope.get(field));
    }

    private String taskActionJson(long taskId) {
        return jdbcTemplate.query(
                "SELECT action_json FROM tp_exec_task WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null, taskId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object m) {
        return (Map<String, Object>) m;
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String orUnknown(Object value) {
        String s = str(value);
        return s == null || s.isBlank() ? "未指明" : s;
    }
}
