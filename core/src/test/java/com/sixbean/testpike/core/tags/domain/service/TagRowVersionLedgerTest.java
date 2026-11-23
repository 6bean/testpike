package com.sixbean.testpike.core.tags.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.tags.api.dto.TagCreateInput;
import com.sixbean.testpike.core.tags.api.dto.TagInfo;
import com.sixbean.testpike.core.tags.api.dto.TagMergeInput;
import com.sixbean.testpike.core.tags.api.dto.TagRenameInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Optimistic-lock version ledger on tag rows .
 * <p>
 * The dialog re-anchors an edit proposal onto the live row version at card
 * time (V330 server-side anchor), which only has teeth if persisted edits
 * bump a version at all. The tag dictionary had no version column, so every
 * rename looked "unchanged" to the anchor chain. This suite pins the new
 * posture against the real dev database (no mocks, R37-friendly: unique rows
 * created through the service and cleaned by id, factory tables untouched).
 */
@SpringBootTest(classes = CoreApplication.class)
class TagRowVersionLedgerTest {

    @Autowired
    private TagService tagService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String tag;
    private long projectId;
    private long userId;
    private final List<Long> tagIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        tag = "tvr" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        userId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_user (username, password_hash, display_name, role, status)
                VALUES (?, '$2a$10$x', ?, 1, 1) RETURNING id
                """, Long.class, tag, tag);
        projectId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, tag, tag);
    }

    @AfterEach
    void tearDown() {
        for (long id : tagIds) {
            jdbcTemplate.update("DELETE FROM tp_case_tag WHERE tag_id = ?", id);
            jdbcTemplate.update("DELETE FROM tp_tag_dict WHERE id = ?", id);
        }
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_user WHERE id = ?", userId);
    }

    private long seedTag(String name) {
        TagInfo created = tagService.create(userId, new TagCreateInput(projectId, name, 4));
        tagIds.add(created.id());
        return created.id();
    }

    private int versionOf(long tagId) {
        Integer version = jdbcTemplate.queryForObject(
                "SELECT version FROM tp_tag_dict WHERE id = ?", Integer.class, tagId);
        assertNotNull(version, "a tag row carries an optimistic version");
        return version;
    }

    @Test
    void freshTagRowStartsAtVersionOne() {
        long tagId = seedTag("anchor-" + tag);
        assertEquals(1, versionOf(tagId),
                "the ledger starts at 1 so an echoed anchor of 1 means an untouched row");
    }

    @Test
    void renameBumpsTheRowVersion() {
        long tagId = seedTag("bump-" + tag);
        tagService.rename(userId, tagId, new TagRenameInput("bumped-" + tag));
        assertEquals(2, versionOf(tagId),
                "a real rename is a content change and must advance the version ledger");
    }

    @Test
    void noOpRenameKeepsTheRowVersion() {
        long tagId = seedTag("noop-" + tag);
        tagService.rename(userId, tagId, new TagRenameInput("noop-" + tag));
        assertEquals(1, versionOf(tagId),
                "an identical name returns early without a write, so the version must not move");
    }

    @Test
    void mergeBumpsTheSurvivingRowVersion() {
        long fromId = seedTag("from-" + tag);
        long toId = seedTag("to-" + tag);
        tagService.merge(userId, new TagMergeInput(fromId, toId));
        assertEquals(2, versionOf(toId),
                "a merge rewrites the surviving row's associations — drafts anchored on it "
                        + "must see the row as changed");
    }
}
