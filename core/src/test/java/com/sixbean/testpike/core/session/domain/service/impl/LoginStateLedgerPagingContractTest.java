package com.sixbean.testpike.core.session.domain.service.impl;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.exception.ValidationException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.session.SessionJwtFixture;
import com.sixbean.testpike.core.session.api.dto.LoginStateInfo;
import com.sixbean.testpike.core.session.api.dto.LoginStateInput;
import com.sixbean.testpike.core.session.domain.service.LoginStateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Login-state ledger paging and search contract (AV2, S-group display-scale
 * rule: an admin list door must be bounded, never a capped full dump).
 *
 * <p>Covers the page envelope ({@code list}+{@code total}), the keyword and
 * status filters, the page-size cap rejection, the server-side sort whitelist
 * (unknown or derived-only columns are refused instead of silently sorting the
 * current pageful), and the delegation of the legacy full-list door onto the
 * same single implementation (R40: one query source, no second SQL).
 * Real slot database, no mocks.
 */
@SpringBootTest(classes = CoreApplication.class)
class LoginStateLedgerPagingContractTest {

    private static final int ROW_COUNT = 25;

    @Autowired
    private LoginStateService loginStateService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;

    @BeforeEach
    void setUp() {
        String code = "lsp" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "session-paging-" + code, code);
        projectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
        jdbcTemplate.update("DELETE FROM tp_login_state WHERE project_id = ?", projectId);
        for (int i = 0; i < ROW_COUNT; i++) {
            loginStateService.register(projectId, input(rowName(i), "runner",
                    "acct-" + (i % 2 == 0 ? "alpha" : "beta"), "token",
                    "key_" + i, SessionJwtFixture.signedWithExp(
                            Instant.now().getEpochSecond() + 3600 + i)));
        }
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM tp_login_state WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_credential WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    private static String rowName(int i) {
        return String.format("state-%02d", i);
    }

    private static LoginStateInput input(String stateName, String role, String label,
                                         String kind, String credKey, String secret) {
        return new LoginStateInput(stateName, role, label, kind, credKey, secret,
                null, List.of("https://app.test"), null);
    }

    private PageResult<LoginStateInfo> page(int pageNo, int size) {
        return loginStateService.listPage(projectId, null, null, null, null, pageNo, size);
    }

    @Test
    void pageDoorReturnsBoundedSliceAndTrueTotal() {
        PageResult<LoginStateInfo> first = page(1, 20);
        assertEquals(ROW_COUNT, first.total(), "total counts the whole slice, not the pageful");
        assertEquals(20, first.list().size(), "the page must be cut at the requested size");
        assertEquals("state-00", first.list().get(0).stateName());

        PageResult<LoginStateInfo> second = page(2, 20);
        assertEquals(5, second.list().size());
        assertEquals("state-20", second.list().get(0).stateName());
    }

    @Test
    void keywordMatchesStateNameOrAccountLabel() {
        PageResult<LoginStateInfo> byName =
                loginStateService.listPage(projectId, "state-1", null, null, null, 1, 20);
        assertEquals(10, byName.total(), "state-10..state-19");
        assertTrue(byName.list().stream().allMatch(r -> r.stateName().startsWith("state-1")));

        PageResult<LoginStateInfo> byLabel =
                loginStateService.listPage(projectId, "beta", null, null, null, 1, 20);
        assertEquals(12, byLabel.total(), "every odd row carries acct-beta");
        assertTrue(byLabel.list().stream().allMatch(r -> "acct-beta".equals(r.accountLabel())));
    }

    @Test
    void statusFilterKeepsOnlyTheRequestedLedgerState() {
        long disabledId = loginStateService.list(projectId, null).stream()
                .filter(r -> "state-03".equals(r.stateName()))
                .map(LoginStateInfo::id).findFirst().orElseThrow();
        loginStateService.setEnabled(disabledId, false);

        PageResult<LoginStateInfo> enabled =
                loginStateService.listPage(projectId, null, 1, null, null, 1, 200);
        assertEquals(ROW_COUNT - 1, enabled.total());
        assertTrue(enabled.list().stream().noneMatch(r -> r.id().equals(disabledId)));

        PageResult<LoginStateInfo> disabled =
                loginStateService.listPage(projectId, null, 0, null, null, 1, 200);
        assertEquals(1, disabled.total());
        assertEquals("state-03", disabled.list().get(0).stateName());
    }

    @Test
    void oversizePageIsRejectedNotSilentlyClamped() {
        assertThrows(ValidationException.class, () -> page(1, 201),
                "S-group: an over-cap page size must fail loudly, never fake a full page");
    }

    @Test
    void serverSortWhitelistOrdersWholeSliceAndRefinesPaging() {
        PageResult<LoginStateInfo> desc = loginStateService.listPage(
                projectId, null, null, "expiresAt", Boolean.FALSE, 1, 5);
        assertEquals("state-24", desc.list().get(0).stateName(),
                "sort applies across the whole slice, so page 1 of a desc sort is the newest");
        assertEquals(ROW_COUNT, desc.total());

        PageResult<LoginStateInfo> asc = loginStateService.listPage(
                projectId, null, null, "expiresAt", Boolean.TRUE, 1, 5);
        assertEquals("state-00", asc.list().get(0).stateName());
    }

    @Test
    void sortKeysOutsideTheWhitelistAreRefused() {
        // "light" is derived (status + expiry) and "credKey" lives in the vault row:
        // neither is a ledger column, so a global sort on them would be a fake
        // promise. The door refuses; the UI has to drop the affordance (R25).
        ServiceException name = assertThrows(ServiceException.class, () -> loginStateService.listPage(
                projectId, null, null, "light", Boolean.TRUE, 1, 20));
        assertTrue(name.getMessage().contains("light"), name.getMessage());
        assertThrows(ServiceException.class, () -> loginStateService.listPage(
                projectId, null, null, "password", Boolean.TRUE, 1, 20));
    }

    @Test
    void legacyFullListDoorDelegatesOntoTheSameImplementation() {
        List<LoginStateInfo> all = loginStateService.list(projectId, null);
        assertEquals(ROW_COUNT, all.size());
        assertEquals(all.stream().map(LoginStateInfo::stateName).toList(),
                page(1, 200).list().stream().map(LoginStateInfo::stateName).toList(),
                "one query source: the legacy door and the page door read the same slice");

        long disabledId = all.get(0).id();
        loginStateService.setEnabled(disabledId, false);
        assertEquals(ROW_COUNT, loginStateService.list(projectId, null).size());
        assertEquals(ROW_COUNT - 1, loginStateService.list(projectId, Boolean.TRUE).size(),
                "enabledOnly keeps the consumable rows (picker door unchanged)");
    }
}
