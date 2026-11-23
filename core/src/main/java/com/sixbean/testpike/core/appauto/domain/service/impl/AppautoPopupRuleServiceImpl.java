package com.sixbean.testpike.core.appauto.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.pipeline.DenialGuidanceFrame;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.util.UiActionContract;
import com.sixbean.testpike.core.appauto.api.dto.AppautoPopupLearnRequest;
import com.sixbean.testpike.core.appauto.api.dto.AppautoPopupRuleView;
import com.sixbean.testpike.core.appauto.domain.component.AppautoConfigReader;
import com.sixbean.testpike.core.appauto.domain.component.MonitorFrameBuffer;
import com.sixbean.testpike.core.appauto.domain.service.AppautoPopupRuleService;
import com.sixbean.testpike.core.appauto.infrastructure.remote.client.AiServicePopupLearnClient;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskInfo;
import com.sixbean.testpike.core.executor.domain.service.ExecTaskService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;


@Service
public class AppautoPopupRuleServiceImpl implements AppautoPopupRuleService {

 
    private static final int TITLE_MAX_LEN = 200;

    private static final String SELECT_COLUMNS = """
            id, project_id, source_task_id, title, rule::text AS rule,
            status, source, created_at
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ExecTaskService execTaskService;
    private final MonitorFrameBuffer frameBuffer;
    private final AppautoConfigReader configReader;
    private final AiServicePopupLearnClient aiClient;
    private final ObjectMapper objectMapper;

    public AppautoPopupRuleServiceImpl(NamedParameterJdbcTemplate jdbc,
                                       ExecTaskService execTaskService,
                                       MonitorFrameBuffer frameBuffer,
                                       AppautoConfigReader configReader,
                                       AiServicePopupLearnClient aiClient,
                                       ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.execTaskService = execTaskService;
        this.frameBuffer = frameBuffer;
        this.configReader = configReader;
        this.aiClient = aiClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public long learn(long taskId) {
        requireGateOpen();
        List<String> images = collectNonSensitiveImages(taskId);
        ExecTaskInfo task = execTaskService.get(taskId);
        Map<String, Object> response = aiClient.recognize(
                new AppautoPopupLearnRequest(taskId, task.projectId(), images));
        String ruleJson = validateExchangeShape(response);
        String title = normalizeTitle(response.get("title"));
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("pid", task.projectId())
                .addValue("srcTask", taskId)
                .addValue("title", title)
                .addValue("rule", ruleJson);
        Long id = jdbc.queryForObject("""
                INSERT INTO tp_appauto_popup_rule
                    (project_id, source_task_id, title, rule, status, source)
                VALUES (:pid, :srcTask, :title, :rule::jsonb, 1, 'AI')
                RETURNING id
                """, params, Long.class);
        return id == null ? 0L : id;
    }

    @Override
    public List<AppautoPopupRuleView> list(long projectId, Integer status, int limit) {
        int cap = Math.min(Math.max(limit, 1), 500);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("pid", projectId)
                .addValue("cap", cap);
        String statusClause = "";
        if (status != null) {
            statusClause = " AND status = :status ";
            params.addValue("status", status);
        }
        return jdbc.queryForList("SELECT " + SELECT_COLUMNS + """
                FROM tp_appauto_popup_rule
                WHERE project_id = :pid
                """ + statusClause + """
                ORDER BY created_at DESC, id DESC
                LIMIT :cap
                """, params).stream().map(AppautoPopupRuleServiceImpl::toView).toList();
    }

    @Override
    public AppautoPopupRuleView find(long ruleId) {
        return require(ruleId);
    }

    @Override
    @Audit(objectType = "appauto_popup_rule", action = "confirm", objectId = "#ruleId")
    public AppautoPopupRuleView confirm(Long operatorId, long ruleId) {
        requirePending(require(ruleId).status(), "批准入库");
        int flipped = jdbc.update("""
                UPDATE tp_appauto_popup_rule SET status = 2, updated_at = now()
                WHERE id = :id AND status = 1
                """, new MapSqlParameterSource("id", ruleId));
        if (flipped == 0) {
            throw new ServiceException("该规则草稿已被处理，本次批准未生效（请到「弹窗规则审批台」刷新查看最新状态）",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
        return require(ruleId);
    }

    @Override
    @Audit(objectType = "appauto_popup_rule", action = "reject", objectId = "#ruleId")
    public void reject(Long operatorId, long ruleId) {
        requirePending(require(ruleId).status(), "驳回");
        int flipped = jdbc.update("""
                UPDATE tp_appauto_popup_rule SET status = 3, updated_at = now()
                WHERE id = :id AND status = 1
                """, new MapSqlParameterSource("id", ruleId));
        if (flipped == 0) {
            throw new ServiceException("该规则草稿已被处理，本次驳回未生效（请到「弹窗规则审批台」刷新查看最新状态）",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
    }


 
    private void requireGateOpen() {
        if (!configReader.popupRuleLearnEnabled()) {
            throw new ServiceException(DenialGuidanceFrame.switchClosed("弹窗规则自学",
                    AppautoConfigReader.KEY_POPUP_RULE_LEARN), BizErrorCode.FORBIDDEN);
        }
    }

    
    private List<String> collectNonSensitiveImages(long taskId) {
        Object raw = frameBuffer.readFrames(taskId).get("frames");
        List<String> images = new ArrayList<>();
        if (raw instanceof List<?> frames) {
            for (Object f : frames) {
                if (f instanceof Map<?, ?> frame && !isSensitive(frame)) {
                    Object image = frame.get("image");
                    if (image instanceof String s && !s.isBlank()) {
                        images.add(s);
                    }
                }
            }
        }
        if (images.isEmpty()) {
            throw new ServiceException("该任务无可学习的监看帧（帧环为空/已过期断流，或全部命中敏感步"
                    + "不打码不入模型）——请在「系统设置 → APP 自动化 → 监看帧」查看帧保留窗参数，"
                    + "或稍后重试「从失败任务自学」",
                    BizErrorCode.NOT_FOUND);
        }
        return images;
    }

    
    private static boolean isSensitive(Map<?, ?> frame) {
        Object v = frame.get("sensitive");
        return Boolean.TRUE.equals(v) || "1".equals(String.valueOf(v));
    }

    
    private String validateExchangeShape(Map<String, Object> response) {
        Object rule = response == null ? null : response.get("rule");
        if (!(rule instanceof Map<?, ?> ruleMap)) {
            throw new ServiceException("弹窗识别应答缺少规则段（rule）——本次自学零落草稿，"
                    + "请在「弹窗规则审批台」点击「从失败任务自学」对同任务重试", BizErrorCode.VALIDATION_ERROR);
        }
        Object detect = ruleMap.get("detect");
        if (!(detect instanceof Map<?, ?> detectMap) || detectMap.isEmpty()) {
            throw new ServiceException("弹窗识别应答的命中特征（detect）为空或不成对象——"
                    + "无定位特征的规则不可入库，本次自学零落草稿——请稍后重试「从失败任务自学」",
                    BizErrorCode.VALIDATION_ERROR);
        }
        Object dismiss = ruleMap.get("dismiss");
        if (!(dismiss instanceof Map<?, ?> dismissMap)) {
            throw new ServiceException("弹窗识别应答缺少处置段（dismiss）——只知弹窗不知如何关闭的"
                    + "规则不可入库，本次自学零落草稿——请稍后重试「从失败任务自学」",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String violation = UiActionContract.appActionViolation(String.valueOf(dismissMap.get("action")));
        if (violation != null) {
            throw new ServiceException("弹窗处置动作不合基线：" + violation
                    + "——请在「弹窗规则审批台」重试「从失败任务自学」", BizErrorCode.VALIDATION_ERROR);
        }
        if (!(dismissMap.get("target") instanceof Map<?, ?>) || ((Map<?, ?>) dismissMap.get("target")).isEmpty()) {
            throw new ServiceException("弹窗处置动作缺少定位对象（dismiss.target 须为对象形）——"
                    + "本次自学零落草稿——请稍后重试「从失败任务自学」", BizErrorCode.VALIDATION_ERROR);
        }
        try {
            return objectMapper.writeValueAsString(ruleMap);
        } catch (Exception e) {
            throw new ServiceException("弹窗规则序列化失败：" + e.getMessage()
                    + "——请稍后重试「从失败任务自学」；持续出现请联系管理员",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    private String normalizeTitle(Object raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = String.valueOf(raw).trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > TITLE_MAX_LEN ? trimmed.substring(0, TITLE_MAX_LEN) : trimmed;
    }

    private AppautoPopupRuleView require(long ruleId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT " + SELECT_COLUMNS + """
                FROM tp_appauto_popup_rule WHERE id = :id
                """, new MapSqlParameterSource("id", ruleId));
        if (rows.isEmpty()) {
            throw new ServiceException("弹窗规则草稿 " + ruleId + " 不存在（可能已被清理）"
                    + "——请刷新「弹窗规则审批台」列表查看最新草稿",
                    BizErrorCode.NOT_FOUND);
        }
        return toView(rows.get(0));
    }

    private static AppautoPopupRuleView toView(Map<String, Object> row) {
        return new AppautoPopupRuleView(
                ((Number) row.get("id")).longValue(),
                row.get("project_id") == null ? null : ((Number) row.get("project_id")).longValue(),
                row.get("source_task_id") == null ? null
                        : ((Number) row.get("source_task_id")).longValue(),
                (String) row.get("title"),
                (String) row.get("rule"),
                ((Number) row.get("status")).intValue(),
                (String) row.get("source"),
                row.get("created_at") == null ? null : String.valueOf(row.get("created_at")));
    }

    
    private void requirePending(int status, String words) {
        if (status != STATUS_PENDING) {
            String state = status == STATUS_CONFIRMED ? "已入库" : "已驳回";
            throw new ServiceException("该规则草稿" + state + "，不可重复" + words
                    + "（请到「弹窗规则审批台」刷新查看最新状态）", BizErrorCode.RESOURCE_CONFLICT);
        }
    }
}
