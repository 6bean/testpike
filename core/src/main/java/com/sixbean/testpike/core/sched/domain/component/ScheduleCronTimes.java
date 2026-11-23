package com.sixbean.testpike.core.sched.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.scheduling.support.CronExpression;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;


public final class ScheduleCronTimes {

    private ScheduleCronTimes() {
    }

    
    public static void validate(String cronExpr) {
        if (cronExpr == null || cronExpr.isBlank()) {
            return;
        }
        if (!CronExpression.isValidExpression(cronExpr.trim())) {
            throw new ServiceException("cron 表达式不合法（须为 6 段格式：秒 分 时 日 月 周，"
                    + "例如 0 30 2 * * * 表示每日 02:30）: " + cronExpr, BizErrorCode.VALIDATION_ERROR);
        }
    }

    
    public static Optional<Instant> previousFire(String cronExpr, Instant now) {
        return previousFire(cronExpr, now, Duration.ofDays(400));
    }

    
    public static Optional<Instant> previousFire(String cronExpr, Instant now, Duration window) {
        if (cronExpr == null || cronExpr.isBlank() || !CronExpression.isValidExpression(cronExpr.trim())) {
            return Optional.empty();
        }
        CronExpression cron = CronExpression.parse(cronExpr.trim());
        ZoneId zone = ZoneId.systemDefault();
        LocalDateTime nowLocal = LocalDateTime.ofInstant(now, zone);
        LocalDateTime cursor = cron.next(nowLocal.minus(window).minusSeconds(1));
        LocalDateTime previous = null;
        while (cursor != null && cursor.isBefore(nowLocal)) {
            previous = cursor;
            cursor = cron.next(cursor);
        }
        if (previous == null) {
            return Optional.empty();
        }
        Instant candidate = previous.atZone(zone).toInstant();
        if (candidate.isBefore(now.minus(window))) {
            return Optional.empty();
        }
        return Optional.of(candidate);
    }

    /**
     * The next {@code count} theoretical fire times strictly after now, ascending —
     * the single source for the wizard's "next fire" preview (B-8). Shares this
     * component and the system-timezone convention with the scanner's previousFire:
     * what the operator previews is what the scanner will actually do.
     * A blank expression is the legal "not scheduled" state and previews empty;
     * an invalid expression is refused fail-closed with the same human-readable
     * wording as {@link #validate}.
     */
    public static List<Instant> nextFires(String cronExpr, Instant now, int count) {
        if (cronExpr == null || cronExpr.isBlank()) {
            return List.of();
        }
        if (!CronExpression.isValidExpression(cronExpr.trim())) {
            throw new ServiceException("cron 表达式不合法（须为 6 段格式：秒 分 时 日 月 周，"
                    + "例如 0 30 2 * * * 表示每日 02:30）: " + cronExpr, BizErrorCode.VALIDATION_ERROR);
        }
        CronExpression cron = CronExpression.parse(cronExpr.trim());
        ZoneId zone = ZoneId.systemDefault();
        LocalDateTime cursor = LocalDateTime.ofInstant(now, zone);
        List<Instant> fires = new ArrayList<>();
        int limit = Math.max(1, count);
        for (int i = 0; i < limit; i++) {
            cursor = cron.next(cursor);
            if (cursor == null) {
                break; // unreachable for an open-ended cron; guards against a dead loop
            }
            fires.add(cursor.atZone(zone).toInstant());
        }
        return fires;
    }
}
