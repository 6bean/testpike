package com.sixbean.testpike.core.webelement.domain.service.impl;

import com.sixbean.testpike.core.common.domain.component.ProjectOwners;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.events.domain.component.TaskEventWriter;
import com.sixbean.testpike.core.notification.api.dto.NotificationCreateInput;
import com.sixbean.testpike.core.notification.domain.service.NotificationService;
import com.sixbean.testpike.core.webelement.api.dto.WebElementHealthView;
import com.sixbean.testpike.core.webelement.domain.service.WebElementHealthService;
import com.sixbean.testpike.core.webelement.domain.service.impl.WebElementServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Step-health account over the daily rollup (WE S7 8.3, V609). Everything
 * the ledger's health column and the "about to break" badge read is one
 * grouped window query — pure SQL, zero model calls, no second source to
 * drift. Viewport buckets merge per element on this plane by design §8.3
 * (the columnar split is a confidence/statement rule, not a health rule).
 * <p>Window model: W = element.health.window-days (seed 7). Window 1 = last
 * W days, window 2 = the W days before it, window 3 = one further step back.
 * The drift ladder needs all three windows reached, two strict climbs
 * (r1 &gt; r2 &gt; r3) and a current window that is NOT a total failure —
 * a 100% miss element is already broken (red badge), not "about to break".
 * <p>Alert posture: evaluateDrift is the only write this service performs.
 * It files one station notice per project owner per calendar day
 * (dedup_key = WEB_ELEMENT_DRIFT:{elementId}:{userId}:{day}, same family as
 * CERT_EXPIRING) and appends exactly one element-ledger audit line when the
 * day's first notice landed, so a busy relay never re-rings the bell.
 * Unreached elements alert nothing — silent pages must not look sick.
 * <p>Thresholds are hot keys read live with clamp + fallback:
 * element.health.miss-warn (seed 20), element.health.miss-bad (seed 50,
 * effective value never below the warn tier), window 1~30 days.
 */
@Service
public class WebElementHealthServiceImpl implements WebElementHealthService {

    private static final Logger log = LoggerFactory.getLogger(WebElementHealthServiceImpl.class);

    static final String KEY_MISS_WARN = "element.health.miss-warn";
    static final String KEY_MISS_BAD = "element.health.miss-bad";
    static final String KEY_WINDOW_DAYS = "element.health.window-days";
    static final int MISS_WARN_DEFAULT = 20;
    static final int MISS_BAD_DEFAULT = 50;
    static final int WINDOW_DAYS_DEFAULT = 7;
    /** Notification/audit event type registered in NotificationEventDict. */
    static final String DRIFT_EVENT_TYPE = "WEB_ELEMENT_DRIFT";

    /** One grouped row: hits/misses summed per window (w1 = most recent). */
    private record WindowRow(long elementId, long h1, long m1, long h2, long m2,
                             long h3, long m3) {
    }

    private static final String WINDOW_SQL = """
            SELECT element_id,
                   COALESCE(SUM(hits)   FILTER (WHERE bucket_date >  CURRENT_DATE - :w1), 0) AS h1,
                   COALESCE(SUM(misses) FILTER (WHERE bucket_date >  CURRENT_DATE - :w1), 0) AS m1,
                   COALESCE(SUM(hits)   FILTER (WHERE bucket_date >  CURRENT_DATE - :w2
                                             AND bucket_date <= CURRENT_DATE - :w1), 0) AS h2,
                   COALESCE(SUM(misses) FILTER (WHERE bucket_date >  CURRENT_DATE - :w2
                                             AND bucket_date <= CURRENT_DATE - :w1), 0) AS m2,
                   COALESCE(SUM(hits)   FILTER (WHERE bucket_date >  CURRENT_DATE - :w3
                                             AND bucket_date <= CURRENT_DATE - :w2), 0) AS h3,
                   COALESCE(SUM(misses) FILTER (WHERE bucket_date >  CURRENT_DATE - :w3
                                             AND bucket_date <= CURRENT_DATE - :w2), 0) AS m3
              FROM tp_web_element_health_daily
             WHERE element_id IN (:ids)
               AND bucket_date > CURRENT_DATE - :w3
             GROUP BY element_id
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ConfigService configService;
    private final NotificationService notificationService;
    private final ProjectOwners projectOwners;
    private final TaskEventWriter events;

    public WebElementHealthServiceImpl(NamedParameterJdbcTemplate jdbc,
                                       ConfigService configService,
                                       NotificationService notificationService,
                                       ProjectOwners projectOwners,
                                       TaskEventWriter events) {
        this.jdbc = jdbc;
        this.configService = configService;
        this.notificationService = notificationService;
        this.projectOwners = projectOwners;
        this.events = events;
    }

    @Override
    public Map<Long, WebElementHealthView> healthFor(Collection<Long> elementIds) {
        Map<Long, WebElementHealthView> views = new LinkedHashMap<>();
        if (elementIds == null || elementIds.isEmpty()) {
            return views;
        }
        int window = intConfig(KEY_WINDOW_DAYS, WINDOW_DAYS_DEFAULT, 1, 30);
        elementIds.forEach(id -> views.put(id, WebElementHealthView.unreached(id)));
        int warn = intConfig(KEY_MISS_WARN, MISS_WARN_DEFAULT, 0, 100);
        int bad = Math.max(intConfig(KEY_MISS_BAD, MISS_BAD_DEFAULT, 0, 100), warn);
        for (WindowRow row : windowRows(elementIds, window)) {
            views.put(row.elementId(), toView(row, warn, bad));
        }
        return views;
    }

    @Override
    public int retentionDays() {
        return intConfig(KEY_WINDOW_DAYS, WINDOW_DAYS_DEFAULT, 1, 30) * 4;
    }

    @Override
    public int evaluateDrift(long elementId) {
        int window = intConfig(KEY_WINDOW_DAYS, WINDOW_DAYS_DEFAULT, 1, 30);
        WindowRow row = windowRows(List.of(elementId), window).stream()
                .findFirst().orElse(null);
        if (row == null || !climbingLadder(row)) {
            return 0;
        }
        Map<String, Object> element = queryElementHead(elementId);
        if (element == null) {
            return 0; // retired mid-flight: nothing to alert about
        }
        long projectId = ((Number) element.get("project_id")).longValue();
        String name = (String) element.get("name");
        int r2 = rate(row.h2(), row.m2());
        int r1 = rate(row.h1(), row.m1());
        String day = LocalDate.now().toString();
        int created = 0;
        for (Long ownerId : projectOwners.resolve(projectId)) {
            String dedupKey = DRIFT_EVENT_TYPE + ":" + elementId + ":" + ownerId + ":" + day;
            if (notificationService.existsByDedupKey(dedupKey)) {
                continue; // same-day re-trigger stays silent (cooldown)
            }
            try {
                long id = notificationService.create(new NotificationCreateInput(
                        ownerId, projectId, DRIFT_EVENT_TYPE,
                        "页面元素将坏预警：" + name,
                        "项目内元素「" + name + "」最近两个统计窗口的未命中比例连续上升（"
                                + r2 + "% → " + r1 + "%），当前尚未完全失效，页面结构很可能已经变化。"
                                + "请到「页面元素库」页核对该元素所在行的健康列并更新锚点；"
                                + "若后续完全失配，该行会转为红色并进入自愈与修复建议流程。",
                        dedupKey));
                if (id > 0) {
                    created++;
                }
            } catch (RuntimeException noticeFailure) {
                log.warn("drift notice skipped for element {} owner {}: {}",
                        elementId, ownerId, noticeFailure.getMessage());
            }
        }
        if (created > 0) {
            events.step(WebElementServiceImpl.JOB_KIND, String.valueOf(elementId),
                    events.newRunToken(),
                    "元素「" + name + "」未命中率连续两窗上升（" + r2 + "%→" + r1 + "%），标记将坏",
                    null);
        }
        return created;
    }

    private List<WindowRow> windowRows(Collection<Long> elementIds, int window) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("ids", elementIds)
                .addValue("w1", window)
                .addValue("w2", window * 2)
                .addValue("w3", window * 3);
        return jdbc.query(WINDOW_SQL, params, (rs, n) -> new WindowRow(
                rs.getLong("element_id"),
                rs.getLong("h1"), rs.getLong("m1"),
                rs.getLong("h2"), rs.getLong("m2"),
                rs.getLong("h3"), rs.getLong("m3")));
    }

    private Map<String, Object> queryElementHead(long elementId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT name, project_id FROM tp_web_element WHERE id = :id",
                new MapSqlParameterSource("id", elementId));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static WebElementHealthView toView(WindowRow row, int warn, int bad) {
        int r1 = rate(row.h1(), row.m1());
        String badge = r1 < 0 ? WebElementHealthView.BADGE_NONE
                : r1 >= bad ? WebElementHealthView.BADGE_RED
                : r1 >= warn ? WebElementHealthView.BADGE_YELLOW
                : WebElementHealthView.BADGE_GREEN;
        return new WebElementHealthView(row.elementId(), r1 >= 0, r1,
                badge, climbingLadder(row));
    }

    /** Two strict climbs with every window reached, current one not total. */
    private static boolean climbingLadder(WindowRow row) {
        int r1 = rate(row.h1(), row.m1());
        int r2 = rate(row.h2(), row.m2());
        int r3 = rate(row.h3(), row.m3());
        return r1 >= 0 && r2 >= 0 && r3 >= 0 && r1 < 100 && r1 > r2 && r2 > r3;
    }

    /** Rounded miss percent; -1 marks an unreached window (never counted). */
    static int rate(long hits, long misses) {
        long total = hits + misses;
        return total <= 0 ? -1 : (int) Math.round(100.0 * misses / total);
    }

    /** Hot-key read with clamp + non-numeric fallback (prior-weight precedent). */
    private int intConfig(String key, int fallback, int min, int max) {
        try {
            int value = Integer.parseInt(
                    configService.getValue(key, String.valueOf(fallback)).trim());
            return Math.min(max, Math.max(min, value));
        } catch (RuntimeException unusable) {
            return fallback;
        }
    }
}
