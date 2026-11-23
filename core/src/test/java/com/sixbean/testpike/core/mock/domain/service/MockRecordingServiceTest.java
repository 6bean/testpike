package com.sixbean.testpike.core.mock.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.mock.api.dto.MockRecordingInfo;
import com.sixbean.testpike.core.mock.api.dto.MockSpaceInput;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Recording control plane : open/close a cassette box
 * for a PUBLISHED space behind the proxy allowlist gate.
 * <p>Gate order is the contract under test: ghost space refused; non-published
 * space refused (draft traffic does not exist to record); blank/non-http target
 * refused; an EMPTY allowlist refuses fail-closed (SSRF pre-gate — recording is
 * off until an operator explicitly opens hosts); host outside the allowlist
 * refused with the host named in the message; one open box per space at a time.
 * <p>Open writes started_at plus the space's proxy_target_url 's
 * forwarder consumes it); close seals recording→stopped with ended_at and a
 * second close is refused (the box is closed — reopen means a new box).
 * <p>Allowlist is hot config: the test rewrites ConfigCacheHolder directly
 * (same semantics as the ConfigService write path, AppautoRecordDraftServiceTest
 * precedent) and restores the seeded empty value in finally. Real dev database,
 * zero mocks (same posture as MockSpaceLifecycleTest).
 */
@SpringBootTest(classes = CoreApplication.class)
class MockRecordingServiceTest {

    private static final String ALLOWLIST_KEY = "mock.proxy.allowlist";

    @Autowired
    private MockRecordingService recordingService;

    @Autowired
    private MockSpaceService spaceService;

    @Autowired
    private ConfigCacheHolder configCacheHolder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long newProject() {
        String code = "t362b" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, code, code);
    }

    /** Published space (the only lifecycle state that can carry a recording). */
    private long newPublishedSpace(String keyPrefix) {
        String key = keyPrefix + "-" + UUID.randomUUID().toString().substring(0, 10);
        long id = spaceService.create(newProject(),
                new MockSpaceInput("Space " + key, key, null, null,
                        "prefix", "none", null, null, 100, null));
        spaceService.transition(id, 1, 0);
        return id;
    }

    private int boxCount(long spaceId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tp_mock_recording WHERE space_id = ?",
                Integer.class, spaceId);
        return n == null ? 0 : n;
    }

    @Test
    void emptyAllowlistRefusesFailClosedAndLeavesNoBox() {
        long spaceId = newPublishedSpace("noal");
        configCacheHolder.remove(ALLOWLIST_KEY); // seeded value is empty = all refused
        ServiceException e = assertThrows(ServiceException.class,
                () -> recordingService.start(spaceId, "http://srv.stub.test/orders"));
        assertEquals(BizErrorCode.FORBIDDEN, e.getCode());
        assertEquals(0, boxCount(spaceId), "refusal must not persist a half-open box");
    }

    @Test
    void hostOutsideAllowlistIsRefusedByName() {
        long spaceId = newPublishedSpace("badhost");
        configCacheHolder.put(ALLOWLIST_KEY, "*.stub.test");
        try {
            ServiceException e = assertThrows(ServiceException.class,
                    () -> recordingService.start(spaceId, "http://evil.example.com/orders"));
            assertEquals(BizErrorCode.FORBIDDEN, e.getCode());
            assertTrue(e.getMessage().contains("evil.example.com"),
                    "refusal must name the offending host, got: " + e.getMessage());
            assertEquals(0, boxCount(spaceId));
        } finally {
            configCacheHolder.remove(ALLOWLIST_KEY);
        }
    }

    @Test
    void startOpensBoxWithTimestampsAndBindsSpaceTarget() {
        long spaceId = newPublishedSpace("open");
        configCacheHolder.put(ALLOWLIST_KEY, "*.stub.test");
        try {
            long boxId = recordingService.start(spaceId, "http://srv.stub.test:8899/orders");
            MockRecordingInfo box = recordingService.stop(boxId);
            assertEquals("stopped", box.status());
            assertEquals(spaceId, box.spaceId());
            assertEquals("http://srv.stub.test:8899/orders", box.targetBaseUrl());
            assertNotNull(box.startedAt(), "open stamp must be persisted by the control plane");
            assertNotNull(box.endedAt(), "seal stamp must be persisted by the control plane");
            // The space row carries the bound target for 's forwarder.
            assertEquals("http://srv.stub.test:8899/orders",
                    spaceService.get(spaceId).proxyTargetUrl());
        } finally {
            configCacheHolder.remove(ALLOWLIST_KEY);
        }
    }

    @Test
    void secondStartOnOpenBoxIsRefusedAndOnlyOneRowExists() {
        long spaceId = newPublishedSpace("dup");
        configCacheHolder.put(ALLOWLIST_KEY, "*.stub.test");
        try {
            recordingService.start(spaceId, "http://srv.stub.test/orders");
            ServiceException e = assertThrows(ServiceException.class,
                    () -> recordingService.start(spaceId, "http://srv.stub.test/orders"));
            assertEquals(BizErrorCode.VALIDATION_ERROR, e.getCode());
            assertEquals(1, boxCount(spaceId), "refused reopen must not add a second box");
        } finally {
            configCacheHolder.remove(ALLOWLIST_KEY);
        }
    }

    @Test
    void draftSpaceCannotOpenABox() {
        String key = "dr" + UUID.randomUUID().toString().substring(0, 10);
        long spaceId = spaceService.create(newProject(),
                new MockSpaceInput("Space " + key, key, null, null,
                        "prefix", "none", null, null, 100, null));
        configCacheHolder.put(ALLOWLIST_KEY, "*.stub.test");
        try {
            ServiceException e = assertThrows(ServiceException.class,
                    () -> recordingService.start(spaceId, "http://srv.stub.test/orders"));
            assertEquals(BizErrorCode.VALIDATION_ERROR, e.getCode());
        } finally {
            configCacheHolder.remove(ALLOWLIST_KEY);
        }
    }

    @Test
    void blankAndNonHttpTargetsAreRefused() {
        long spaceId = newPublishedSpace("shape");
        configCacheHolder.put(ALLOWLIST_KEY, "*.stub.test");
        try {
            assertThrows(ServiceException.class, () -> recordingService.start(spaceId, "  "));
            ServiceException e = assertThrows(ServiceException.class,
                    () -> recordingService.start(spaceId, "ftp://srv.stub.test/files"));
            assertEquals(BizErrorCode.VALIDATION_ERROR, e.getCode());
            assertEquals(0, boxCount(spaceId));
        } finally {
            configCacheHolder.remove(ALLOWLIST_KEY);
        }
    }

    @Test
    void doubleStopIsRefusedOnSealedBox() {
        long spaceId = newPublishedSpace("reseal");
        configCacheHolder.put(ALLOWLIST_KEY, "*.stub.test");
        try {
            long boxId = recordingService.start(spaceId, "http://srv.stub.test/orders");
            recordingService.stop(boxId);
            ServiceException e = assertThrows(ServiceException.class,
                    () -> recordingService.stop(boxId));
            assertEquals(BizErrorCode.VALIDATION_ERROR, e.getCode());
        } finally {
            configCacheHolder.remove(ALLOWLIST_KEY);
        }
    }

    @Test
    void stopOnUnknownBoxIsRefused() {
        assertThrows(ServiceException.class, () -> recordingService.stop(999_999_999L));
    }

    @Test
    void snapshotCarriesActiveRecordingIdAndSealDropsItFromThePin() {
        long spaceId = newPublishedSpace("snapbox");
        String key = spaceService.get(spaceId).spaceKey();
        configCacheHolder.put(ALLOWLIST_KEY, "*.stub.test");
        try {
            // No box open: the snapshot says null so the plane forwards nothing.
            assertTrue(spaceService.snapshot(key).activeRecordingId() == null,
                    "an idle space's snapshot must carry a null active recording id");

            long boxId = recordingService.start(spaceId, "http://srv.stub.test/orders");
            assertEquals(boxId, spaceService.snapshot(key).activeRecordingId(),
                    "the open box id must ride the snapshot for the forwarder");
            String pinWithBox = spaceService.snapshot(key).version();

            recordingService.stop(boxId);
            assertTrue(spaceService.snapshot(key).activeRecordingId() == null,
                    "a sealed box must drop from the snapshot — forwarding stops");
            assertNotEquals(pinWithBox, spaceService.snapshot(key).version(),
                    "recording state is snapshot content: sealing must move the pin so loaders reload");
        } finally {
            configCacheHolder.remove(ALLOWLIST_KEY);
        }
    }

    // ---- paged + whitelisted-sort window (S-2 debt clear) ----

    /**
     * Open two boxes on a space and pin promoted_count apart so counted-column
     * sorting has an observable order. Returns {first, second} ids.
     */
    private long[] seedTwoBoxes(long spaceId) {
        long first = recordingService.start(spaceId, "http://m.stub.test/one");
        recordingService.stop(first);
        long second = recordingService.start(spaceId, "http://z.stub.test/two");
        jdbcTemplate.update("UPDATE tp_mock_recording SET promoted_count = 7 WHERE id = ?", first);
        jdbcTemplate.update("UPDATE tp_mock_recording SET promoted_count = 2 WHERE id = ?", second);
        return new long[]{first, second};
    }

    @Test
    void pagedWindowKeepsHistoricalNewestFirstAndEchoesTruthfulTotal() {
        long spaceId = newPublishedSpace("page");
        configCacheHolder.put(ALLOWLIST_KEY, "*.stub.test");
        try {
            long[] ids = seedTwoBoxes(spaceId);
            var window = recordingService.listBySpacePaged(spaceId, 1, 1, null, null);
            assertEquals(2, window.total(), "total counts every box of the space");
            assertEquals(1, window.list().size(), "the window carries only the page");
            assertEquals(1, window.page());
            assertEquals(1, window.pageSize());
            assertEquals(ids[1], window.list().get(0).id(), "no-sort keeps the historical id DESC order");
            var second = recordingService.listBySpacePaged(spaceId, 2, 1, null, null);
            assertEquals(ids[0], second.list().get(0).id(), "page 2 continues the same order");
        } finally {
            configCacheHolder.remove(ALLOWLIST_KEY);
            jdbcTemplate.update("DELETE FROM tp_mock_recording WHERE space_id = ?", spaceId);
            jdbcTemplate.update("DELETE FROM tp_mock_space WHERE id = ?", spaceId);
        }
    }

    @Test
    void whitelistedSortKeysOrderInSqlWithIdTieBreak() {
        long spaceId = newPublishedSpace("sort");
        configCacheHolder.put(ALLOWLIST_KEY, "*.stub.test");
        try {
            seedTwoBoxes(spaceId);
            // counted column: promoted_count asc puts the 2-promoted box first
            var byPromoted = recordingService.listBySpacePaged(spaceId, 1, 10, "promoted_count", "asc");
            assertEquals("http://z.stub.test/two", byPromoted.list().get(0).targetBaseUrl(),
                    "promoted_count asc orders by the stored counter");
            // text column desc
            var byTarget = recordingService.listBySpacePaged(spaceId, 1, 10, "target_base_url", "desc");
            assertEquals("http://z.stub.test/two", byTarget.list().get(0).targetBaseUrl());
            // every physical whitelist key is accepted (no throw) over the live chain
            for (String key : new String[]{"target_base_url", "status", "promoted_count",
                    "started_at", "ended_at", "updated_at"}) {
                recordingService.listBySpacePaged(spaceId, 1, 10, key, "asc");
            }
        } finally {
            configCacheHolder.remove(ALLOWLIST_KEY);
            jdbcTemplate.update("DELETE FROM tp_mock_recording WHERE space_id = ?", spaceId);
            jdbcTemplate.update("DELETE FROM tp_mock_space WHERE id = ?", spaceId);
        }
    }

    @Test
    void offListOrInjectionSortKeyIsRefusedFailClosed() {
        long spaceId = newPublishedSpace("sortrefuse");
        configCacheHolder.put(ALLOWLIST_KEY, "*.stub.test");
        try {
            seedTwoBoxes(spaceId);
            // blob column never sortable + injection shape: both refused by name
            for (String bad : new String[]{"summary_json", "id DESC; DROP TABLE tp_mock_recording"}) {
                ServiceException ex = assertThrows(ServiceException.class,
                        () -> recordingService.listBySpacePaged(spaceId, 1, 10, bad, "asc"));
                assertTrue(ex.getMessage().contains("白名单"),
                        "refusal wording names the whitelist: " + ex.getMessage());
            }
            assertEquals(2, boxCount(spaceId), "refusals touch no rows");
        } finally {
            configCacheHolder.remove(ALLOWLIST_KEY);
            jdbcTemplate.update("DELETE FROM tp_mock_recording WHERE space_id = ?", spaceId);
            jdbcTemplate.update("DELETE FROM tp_mock_space WHERE id = ?", spaceId);
        }
    }

    @Test
    void pagedWindowClampsNonsenseAndRefusesGhostSpace() {
        long spaceId = newPublishedSpace("clamp");
        var window = recordingService.listBySpacePaged(spaceId, 0, 99999, null, null);
        assertEquals(1, window.page(), "page below 1 clamps to 1");
        assertEquals(500, window.pageSize(), "oversized window clamps to the shared cap");
        assertEquals(0, window.total(), "empty space answers a truthful zero, not an error");
        assertThrows(ServiceException.class,
                () -> recordingService.listBySpacePaged(999_999_999L, 1, 20, null, null));
    }

    @Test
    void listIsNewestFirstAndGhostSpaceIsRefused() {
        long spaceId = newPublishedSpace("list");
        configCacheHolder.put(ALLOWLIST_KEY, "*.stub.test");
        try {
            long first = recordingService.start(spaceId, "http://a.stub.test/one");
            recordingService.stop(first);
            long second = recordingService.start(spaceId, "http://b.stub.test/two");
            List<MockRecordingInfo> boxes = recordingService.listBySpace(spaceId);
            assertEquals(2, boxes.size());
            assertEquals(second, boxes.get(0).id(), "list must come newest box first");
            assertThrows(ServiceException.class,
                    () -> recordingService.listBySpace(999_999_999L));
        } finally {
            configCacheHolder.remove(ALLOWLIST_KEY);
            jdbcTemplate.update("DELETE FROM tp_mock_recording WHERE space_id = ?", spaceId);
            jdbcTemplate.update("DELETE FROM tp_mock_space WHERE id = ?", spaceId);
        }
    }
}
