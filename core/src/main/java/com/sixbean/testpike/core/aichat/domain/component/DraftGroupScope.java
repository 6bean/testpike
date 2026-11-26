package com.sixbean.testpike.core.aichat.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Group key of the group-confirmation door, generalized beyond one dialog
 * answer . Two canonical
 * wire forms:
 * <ul>
 * <li>{@code message:{id}} — all pending cards minted on one assistant
 * answer (the shape, semantics unchanged);</li>
 * <li>{@code system:{projectId}:{objectType}} — all pending system-origin
 * cards (session/message NULL per V363) of one project for one object
 * kind. The linkage bus mints its grouped_draft impacts this way: the
 * draft ledger columns already pin the group (project + kind + system
 * origin), so no new column and no second grouping table is needed —
 * either would only drift the ledger that is already the single source.</li>
 * </ul>
 * The wire form doubles as the group ticket's bound value, so a ticket
 * minted for one group can never open another. Parse is strict and refuses
 * loudly, mirroring the door's posture.
 */
public record DraftGroupScope(boolean dialogScoped, long messageId,
                              long projectId, String objectType) {

    private static final Pattern MESSAGE_FORM = Pattern.compile("^message:\\d{1,19}$");
    private static final Pattern SYSTEM_FORM =
            Pattern.compile("^system:(\\d{1,19}):([A-Za-z][A-Za-z0-9_]{0,31})$");
    /** Read-all enumeration (D4d): the segment alone names the group family. */
    private static final Pattern SYSTEM_ENUM_FORM = Pattern.compile("^system:\\d{1,19}$");

    public static DraftGroupScope forMessage(long messageId) {
        if (messageId <= 0) {
            throw refusal();
        }
        return new DraftGroupScope(true, messageId, 0, null);
    }

    public static DraftGroupScope forSystem(long projectId, String objectType) {
        if (projectId <= 0 || objectType == null
                || !SYSTEM_FORM.matcher("system:" + projectId + ":" + objectType).matches()) {
            throw refusal();
        }
        return new DraftGroupScope(false, 0, projectId, objectType);
    }

    /** Strict wire parse — anything else is a shape error, never a guess. */
    public static DraftGroupScope parse(String wire) {
        if (wire == null) {
            throw refusal();
        }
        String trimmed = wire.trim();
        Matcher message = MESSAGE_FORM.matcher(trimmed);
        if (message.matches()) {
            return forMessage(Long.parseLong(trimmed.substring("message:".length())));
        }
        Matcher system = SYSTEM_FORM.matcher(trimmed);
        if (system.matches()) {
            return new DraftGroupScope(false, 0,
                    Long.parseLong(system.group(1)), system.group(2));
        }
        Matcher systemEnum = SYSTEM_ENUM_FORM.matcher(trimmed);
        if (systemEnum.matches()) {
            return new DraftGroupScope(false, 0,
                    Long.parseLong(trimmed.substring("system:".length())), null);
        }
        throw refusal();
    }

    /**
 * True for the read-all enumeration wire {@code system:{projectId}} —
 * every non-empty system group of the segment. Legal for the read door
 * only: a confirmation ticket always binds ONE explicit group, so the
 * issue door refuses this shape rather than widening the ticket.
 */
    public boolean systemEnumerated() {
        return !dialogScoped && objectType == null;
    }

    /** Canonical bound value of this group (ticket binding + audit target). */
    public String wire() {
        if (dialogScoped) {
            return "message:" + messageId;
        }
        return objectType == null
                ? "system:" + projectId
                : "system:" + projectId + ":" + objectType;
    }

    private static ServiceException refusal() {
        return new ServiceException("草案分组标识无法识别（合法形态：message:123 或 "
                + "system:7:SCRIPT_REPAIR），本次确认未生效，确认票未消耗——请在「草案确认卡」重新勾选要落地的分组后再确认",
                BizErrorCode.VALIDATION_ERROR);
    }
}
