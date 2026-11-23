package com.sixbean.testpike.core.webelement.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.webelement.PageWriteProfileView;
import com.sixbean.testpike.core.aichat.domain.component.DraftObjectType;
import com.sixbean.testpike.core.aichat.domain.service.SystemDraftService;
import com.sixbean.testpike.core.webelement.domain.service.PageWriteFailureDraftService;
import com.sixbean.testpike.core.webelement.domain.service.PageWriteProfileQueryService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Files a pending case draft from one booked network failure .
 * <p>Every guard answers in three parts (R63): what the offending value is,
 * what range/existence the caller passed, and where to retry from. Reads go
 * through the shared query door so the project segment rule lives in exactly
 * one place; the payload speaks the shape CaseWriteDraftStrategy.landCreate
 * consumes verbatim (title / precondition / expectedResult / steps with
 * description, stepNo, isAssert). Nothing here writes a case — the card
 * lands only after a human confirms it through the generic door.
 */
@Service
public class PageWriteFailureDraftServiceImpl implements PageWriteFailureDraftService {

    /** Network-attribution dialect: 3 = environment (reset/timeout), else product defect. */
    private static final int ERROR_TYPE_ENVIRONMENT = 3;

    /** Case titles are length-checked downstream; keep the request line readable but bounded. */
    private static final int TITLE_MAX = 180;

    private final PageWriteProfileQueryService profiles;
    private final SystemDraftService draftService;
    private final ObjectMapper mapper;

    public PageWriteFailureDraftServiceImpl(PageWriteProfileQueryService profiles,
                                            SystemDraftService draftService,
                                            ObjectMapper mapper) {
        this.profiles = profiles;
        this.draftService = draftService;
        this.mapper = mapper;
    }

    @Override
    public long toCaseDraft(Long projectId, long profileId, int failureIndex) {
        if (projectId == null) {
            throw new ServiceException("转用例草案被拒绝：缺少所属项目（参数 projectId 为空）——"
                    + "请先在顶栏「选择项目」选好项目后从页面写入风险账里重试；接口对接请携带有效的 projectId 参数后重新发起",
                    BizErrorCode.VALIDATION_ERROR);
        }
        PageWriteProfileView row = profiles.findByIdInProject(projectId, profileId);
        if (row == null) {
            throw new ServiceException("未找到这条页面风险账（编号 " + profileId
                    + " 不属于当前项目，或已被保留策略清理），转用例草案被拒绝——"
                    + "请刷新「页面写入风险账」列表后从仍在的行的失败记录重新发起；列表已空时可重新发起一次整站探测生成新账",
                    BizErrorCode.VALIDATION_ERROR);
        }
        List<JsonNode> failures = row.netFailures();
        if (failures.isEmpty()) {
            throw new ServiceException("这条风险账（编号 " + profileId + "）没有记录到失败请求，没有可转成的用例草案——"
                    + "请在「页面写型风险账」展开该页面的「接口健康」清单核对失败请求；这里没有记录时，"
                    + "在站点档案行点「测绘」重新发起一次探查后再来",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (failureIndex < 0 || failureIndex >= failures.size()) {
            throw new ServiceException("要转草案的失败序号超出范围（收到 " + failureIndex
                    + "，这条记录共 " + failures.size() + " 条失败请求，有效序号 0~" + (failures.size() - 1)
                    + "），转用例草案被拒绝——请刷新「页面写型风险账」后，在该页面展开行「接口健康」里的目标失败记录上重新发起转换",
                    BizErrorCode.VALIDATION_ERROR);
        }
        JsonNode failure = failures.get(failureIndex);
        ObjectNode payload = payload(row, failure);
        return draftService.postStructuredDraft(projectId, DraftObjectType.CASE_WRITE.wireName,
                summary(failure), payload.toString(), null);
    }

    /** Proposal body in the landing strategy's own dialect (no second shape). */
    private ObjectNode payload(PageWriteProfileView row, JsonNode failure) {
        String method = text(failure, "method", "REQUEST");
        String url = text(failure, "url", "");
        boolean environment = failure.path("errorType").asInt(1) == ERROR_TYPE_ENVIRONMENT;
        String cause = causeOf(failure);
        String expectedResult = environment
                ? "重新发起该请求时目标服务连通、请求正常完成，不再出现连接中断或超时"
                : "重新发起该请求应返回成功状态（HTTP 2xx），不再复现探测记录的 " + cause;
        ObjectNode body = mapper.createObjectNode();
        body.put("title", clamp("验证接口失败处置：" + method + " " + url));
        body.put("precondition", "整站探测在页面 " + row.pageUrl() + " 记录到失败请求："
                + method + " " + url + "（" + cause + "）");
        body.put("expectedResult", expectedResult);
        ArrayNode steps = body.putArray("steps");
        steps.addObject()
                .put("description", "按探测记录重新发起请求：" + method + " " + url)
                .put("stepNo", 1)
                .put("isAssert", 0);
        steps.addObject()
                .put("description", "断言响应符合预期：" + expectedResult)
                .put("stepNo", 2)
                .put("isAssert", 1);
        return body;
    }

    /** Card headline the confirmation screen shows before the payload is opened. */
    private static String summary(JsonNode failure) {
        return "探测失败记录转用例草案：" + text(failure, "method", "REQUEST") + " " + text(failure, "url", "");
    }

    /** HTTP status when one was booked, otherwise the request-failure reason text. */
    private static String causeOf(JsonNode failure) {
        JsonNode status = failure.get("status");
        if (status != null && !status.isNull()) {
            return "HTTP " + status.asInt();
        }
        return text(failure, "failureText", "连接中断");
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? fallback : value.asText(fallback);
    }

    private static String clamp(String title) {
        return title.length() <= TITLE_MAX ? title : title.substring(0, TITLE_MAX - 3) + "...";
    }
}
