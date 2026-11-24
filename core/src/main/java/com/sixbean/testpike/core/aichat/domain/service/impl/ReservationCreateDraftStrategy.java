package com.sixbean.testpike.core.aichat.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.aichat.domain.component.DraftObjectType;
import com.sixbean.testpike.core.aichat.domain.component.DraftPayloadReads;
import com.sixbean.testpike.core.aichat.domain.service.ChatSessionService;
import com.sixbean.testpike.core.aichat.domain.service.DraftWriteStrategy;
import com.sixbean.testpike.core.appauto.api.dto.AppautoReservationInput;
import com.sixbean.testpike.core.appauto.domain.service.AppautoReservationService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * Device-reservation create strategy behind the generic draft door (tool-
 * surface clearing batch stage 6a, eleventh cleared domain): one
 * confirmed dialog proposal books a device through the SAME
 * {@code AppautoReservationService.create} chain the reservation calendar's
 * dialog uses — the window validation (both ends required, end after start),
 * the device-existence check, the conflict refusal (it names the current
 * holder and the expected release time) and the approval-switch routing
 * (approval switch off = active immediately, on = stays pending approval)
 * all re-run inside that transaction at landing time,
 * so the dialog is structurally unable to bypass one gate (red line: the
 * create chain owns the decision, the draft card only states it).
 * <p>Attribution guard (H1): the owning project is read from the chat
 * session, never from the payload. A model-forged device id therefore cannot
 * reserve another project's lane: the create chain re-checks the device
 * exists and the conflict window is shared platform-wide, fail-closed with
 * its own reason. The confirming human becomes the reservation holder —
 * the conflict wording needs a named person and the holder anchor is the
 * operator identity the door already trusts, never a payload field.
 * <p>A created reservation row has no version ledger for this kind: the
 * applied version stays null (mirroring the report and perf kinds' shape),
 * while target_id keeps the device anchor the card was emitted with.
 * Releasing, cancelling or binding a reservation to a batch stays a
 * deliberate human click on /appauto/reservations.
 */
@Service
public class ReservationCreateDraftStrategy implements DraftWriteStrategy {

    private final AppautoReservationService reservationService;
    private final ChatSessionService sessionService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReservationCreateDraftStrategy(AppautoReservationService reservationService,
                                          ChatSessionService sessionService) {
        this.reservationService = reservationService;
        this.sessionService = sessionService;
    }

    @Override
    public String objectType() {
        return DraftObjectType.RESERVATION_WRITE.wireName;
    }

    @Override
    public Integer land(Map<String, Object> draftRow, long operatorId) {
        long sessionId = ((Number) draftRow.get("session_id")).longValue();
        // H1: the project segment comes from the session (null projectId is
        // the shared-lane shape the create chain already understands); the
        // payload only names the device, window and reason.
        long projectId = sessionService.findById(sessionId).projectId();
        JsonNode body = parseBody(String.valueOf(draftRow.get("payload_json")));
        long deviceId = body.path("deviceId").asLong(0L);
        if (deviceId <= 0) {
            throw new ServiceException(
                    "AI 设备预约草案缺少「设备编号」（deviceId 为空），无法预约——"
                            + "请在右侧对话坞重新要求预约并说清设备与时间段（示例「预约3号机明天9点到12点」），"
                            + "系统会重新出草案卡（入口：右侧对话坞 → 草案卡）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        LocalDateTime startsAt = readTime(body, "startsAt");
        LocalDateTime endsAt = readTime(body, "endsAt");
        // Every legality decision (window shape, device existence, conflict
        // window, approval routing) is re-taken by the create chain itself;
        // this strategy only refuses a body that cannot reach it.
        reservationService.create(new AppautoReservationInput(
                projectId, deviceId, null, operatorId,
                startsAt, endsAt,
                body.path("reason").asText("").trim()));
        // A reservation row has no version ledger for this kind — honest null.
        return null;
    }

    /**
     * Window ends must be readable timestamps: the create chain refuses a
     * missing or inverted window with its own wording, but an unparseable
     * string never reaches it — refuse here with the same honest outcome so
     * the card stays pending and the operator gets a named reason.
     */
    private LocalDateTime readTime(JsonNode body, String field) {
        String raw = body.path(field).asText("").trim();
        if (raw.isEmpty()) {
            throw new ServiceException(
                    "AI 设备预约草案缺少" + ("startsAt".equals(field) ? "「开始时间」（startsAt" : "「结束时间」（endsAt")
                            + " 为空），无法预约——请在对话里给出时间段（格式：YYYY-MM-DDTHH:mm:ss，示例 2026-11-25T09:00:00），"
                            + "系统会重新出草案卡（入口：右侧对话坞 → 草案卡）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        try {
            // The page's value-format is "YYYY-MM-DDTHH:mm:ss"; accept the
            // ISO local form (seconds optional both parse via trim to second).
            String normalized = raw.replace(' ', 'T');
            return LocalDateTime.parse(normalized.length() > 19
                    ? normalized.substring(0, 19) : normalized);
        } catch (DateTimeParseException e) {
            throw new ServiceException(
                    "AI 设备预约草案的时间「" + raw
                            + "」格式无法识别，确认预约已拒绝——时间格式：YYYY-MM-DDTHH:mm:ss"
                            + "（示例 2026-11-25T09:00:00），请在右侧对话坞重新要求预约（入口：对话坞 → 草案卡）",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    private JsonNode parseBody(String payloadJson) {
        return DraftPayloadReads.parseOrRefuse(objectMapper, payloadJson, "AI 设备预约草案内容无法解析，确认预约已拒绝（请重新让 AI 生成提案）");
    }
}
