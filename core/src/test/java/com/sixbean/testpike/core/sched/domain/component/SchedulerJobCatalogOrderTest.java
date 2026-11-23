package com.sixbean.testpike.core.sched.domain.component;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;


class SchedulerJobCatalogOrderTest {

    @Test
    void descriptionsPreserveRegistrationOrder() {
        SchedulerJobCatalog catalog = new SchedulerJobCatalog();
        Map<String, String> view = catalog.descriptions();

        List<String> viewOrder = new ArrayList<>(view.keySet());
        assertEquals("dailyDigestScan", viewOrder.get(0),
                "遍历首行应为登记序首行（哈希序会打乱=契约漂移）");
        assertEquals("webElementLedgerRetentionDaily", viewOrder.get(viewOrder.size() - 1),
                "遍历末行应为登记序末行（WE T661 j 面留存双回路键追加在末尾=在册同步点）");

        int weekly = viewOrder.indexOf("reportWeeklyGenerate");
        int monthly = viewOrder.indexOf("reportMonthlyGenerate");
        int retention = viewOrder.indexOf("reportRetentionDaily");
        assertEquals(weekly + 1, monthly, "每周/每月报告登记相邻序应保持");
        assertEquals(monthly + 1, retention, "每月报告/留存期清理登记相邻序应保持");

        assertEquals(3, viewOrder.indexOf("costOverrideExpire"),
                "清理任务应恒在登记序第 4 位（健康卡首屏 20 行内可见=立即执行清单人话名可达）");
    }

    @Test
    void healthFirstPageContainsAllDescribedJobsForRunNowPicker() {
        SchedulerJobCatalog catalog = new SchedulerJobCatalog();
        Map<String, String> view = catalog.descriptions();
        // Catalog size is asserted exactly (40 entries registered after the
        // ledger retention twin-loop key joined; adding a job key must
        // update this line together with the E2E first-page pin = intentional
        // sync point — the appended key sits last, so the first page is
        // unaffected).
        assertEquals(40, view.size(), "调度任务目录登记数（扩键=本行同步勘正+E2E 首屏行位联动）");
    }
}
