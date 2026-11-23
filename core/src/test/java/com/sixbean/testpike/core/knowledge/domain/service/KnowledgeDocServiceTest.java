package com.sixbean.testpike.core.knowledge.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.exception.ValidationException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.knowledge.api.dto.KnowledgeDocItem;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class KnowledgeDocServiceTest {

    @Autowired
    private KnowledgeDocService knowledgeDocService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> fixtureDocIds = new ArrayList<>();

    /** Fresh synthetic project per test: tp_knowledge_doc.project_id carries no
 * FK, and a never-reused segment keeps the ledger assertions deterministic
 * while concurrent chains share the dev rows (R37). */
    private static final AtomicLong SYNTHETIC_PROJECT = new AtomicLong(9_000_000_000L);

    private static long newProject() {
        return SYNTHETIC_PROJECT.incrementAndGet();
    }

    @BeforeEach
    @AfterEach
    void cleanFixtureRows() {
        // R37 teardown: tracked ids only. The former TRUNCATE tp_knowledge_doc
        // RESTART IDENTITY CASCADE wiped the 51 shared dev ledger rows per test
        // (generation-family postmortem, ).
        SharedDbFixtureSweep.byIds(jdbcTemplate, "tp_knowledge_doc", fixtureDocIds);
        fixtureDocIds.clear();
    }

    private long seed(long projectId, long packId, String sourceType, String sourceRef,
                      String title, String status) {
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO tp_knowledge_doc "
                        + "(project_id, pack_id, source_type, source_ref, title, content, status) "
                        + "VALUES (?, ?, ?, ?::bigint, ?, ?, ?) RETURNING id",
                Long.class, projectId, packId, sourceType, sourceRef, title,
                "正文-" + title, status);
        fixtureDocIds.add(id);
        return id;
    }

    @Test
    void 分页_id倒序_total独立于页大小() {
        long project = newProject();
        seed(project, 0L, "TEST_SPEC", null, "spec-a", "ACTIVE");
        seed(project, 0L, "TEST_CASE", "501", "case-a", "ACTIVE");
        seed(project, 0L, "TEST_SPEC", null, "spec-b", "ACTIVE");

        // Fixture-scoped window (R37): paging/ordering assert inside a fresh
        // project segment; window-total independence holds across both sizes.
        PageResult<KnowledgeDocItem> page1 =
                knowledgeDocService.list(project, null, null, 1, 2);
        assertEquals(3L, page1.total(), "total=窗口全量 count，不受 size 影响");
        assertEquals(2, page1.list().size());
        PageResult<KnowledgeDocItem> page2 =
                knowledgeDocService.list(project, null, null, 2, 2);
        assertEquals(1, page2.list().size());
        assertEquals(3L, page2.total(), "size 变 total 不变");
        List<Long> ids = page1.list().stream().map(KnowledgeDocItem::id).toList();
        assertTrue(ids.get(0) > ids.get(1), "id DESC 最新在前：" + ids);
        assertEquals("spec-b", page1.list().get(0).title());
        assertEquals("spec-a", page2.list().get(0).title());
        KnowledgeDocItem head = page1.list().get(0);
        assertEquals("spec-b", head.title());
        assertEquals("正文-spec-b", head.content());
        assertEquals("TEST_SPEC", head.sourceType());
        assertEquals("ACTIVE", head.status());
        assertNotNull(head.createdAt(), "创建时间随行透传");
    }

    @Test
    void 筛选三键_项目来源状态可组合() {
        long p1 = newProject();
        long p2 = newProject();
        seed(p1, 0L, "TEST_SPEC", null, "s1", "ACTIVE");
        seed(p1, 0L, "TEST_CASE", "501", "c1", "ACTIVE");
        seed(p1, 0L, "TEST_SPEC", null, "s2", "INACTIVE");
        seed(p2, 0L, "TEST_CASE", "502", "c2", "ACTIVE");

        assertEquals(3L, knowledgeDocService.list(p1, null, null, 1, 20).total());
        assertEquals(1L, knowledgeDocService.list(p1, "TEST_CASE", null, 1, 20).total());
        assertEquals(2L, knowledgeDocService.list(p1, null, "ACTIVE", 1, 20).total());
        assertEquals(1L, knowledgeDocService.list(p2, null, "ACTIVE", 1, 20).total(),
                "状态筛选只在项目切片内计数");
        PageResult<KnowledgeDocItem> combo =
                knowledgeDocService.list(p1, "TEST_SPEC", "INACTIVE", 1, 20);
        assertEquals(1L, combo.total());
        assertEquals("s2", combo.list().get(0).title());
        assertEquals(501L, knowledgeDocService.list(p1, "TEST_CASE", null, 1, 20)
                .list().get(0).sourceRef());
    }

    @Test
    void 状态非法值_40001人话拒() {
        seed(newProject(), 0L, "TEST_SPEC", null, "s1", "ACTIVE");
        ValidationException ex = assertThrows(ValidationException.class,
                () -> knowledgeDocService.list(null, null, "DELETED", 1, 20));
        assertTrue(ex.getMessage().contains("状态"), "人话点名状态字段：" + ex.getMessage());
    }

    
    @Test
    void 来源筛选_DDL_DICT合法行可查() {
        long p1 = newProject();
        long p2 = newProject();
        seed(p1, 0L, "DDL_DICT", null, "d1", "ACTIVE");
        seed(p2, 0L, "TEST_SPEC", null, "s1", "ACTIVE");
        PageResult<KnowledgeDocItem> ddl =
                knowledgeDocService.list(p1, "DDL_DICT", null, 1, 20);
        assertEquals(1L, ddl.total());
        assertEquals("d1", ddl.list().get(0).title());
        assertEquals(0L, knowledgeDocService.list(p2, "DDL_DICT", null, 1, 20).total(),
                "来源筛选不误纳他项目行");
    }

    // ---- page 16: server-side sort over the ledger window ----
    // (English capability comments; the seed helper pins updated_at directly so
    //  tie groups are deterministic regardless of wall-clock insert speed.)

    private long seedWithUpdated(long projectId, String sourceType, String title,
                                 String status, String updatedAt) {
        long id = seed(projectId, 0L, sourceType, null, title, status);
        jdbcTemplate.update("UPDATE tp_knowledge_doc SET updated_at = ?::timestamp WHERE id = ?",
                updatedAt, id);
        return id;
    }

    /** Title ascending orders the whole window, not one pageful: page 2
     *  continues the order instead of reshuffling within its own rows. */
    @Test
    void 排序_标题升序_跨页延续() {
        long project = newProject();
        seed(project, 0L, "TEST_CASE", null, "m-doc", "ACTIVE");
        seed(project, 0L, "TEST_CASE", null, "a-doc", "ACTIVE");
        seed(project, 0L, "TEST_CASE", null, "z-doc", "ACTIVE");

        PageResult<KnowledgeDocItem> p1 =
                knowledgeDocService.list(project, null, null, 1, 2, "title", "asc");
        PageResult<KnowledgeDocItem> p2 =
                knowledgeDocService.list(project, null, null, 2, 2, "title", "asc");
        assertEquals("a-doc", p1.list().get(0).title());
        assertEquals("m-doc", p1.list().get(1).title());
        assertEquals("z-doc", p2.list().get(0).title(), "次页延续整窗排序");
    }

    /** Equal updated_at values still page deterministically: id DESC tie-break
     *  pinned after every whitelisted key. */
    @Test
    void 排序_更新时间升序_id钉底() {
        long project = newProject();
        long first = seedWithUpdated(project, "TEST_SPEC", "tie-a", "ACTIVE", "2026-10-01 00:00:00");
        long second = seedWithUpdated(project, "TEST_SPEC", "tie-b", "ACTIVE", "2026-10-01 00:00:00");
        seedWithUpdated(project, "TEST_SPEC", "older", "ACTIVE", "2026-09-01 00:00:00");

        PageResult<KnowledgeDocItem> asc =
                knowledgeDocService.list(project, null, null, 1, 20, "updated_at", "asc");
        assertEquals("older", asc.list().get(0).title());
        List<Long> tied = asc.list().subList(1, 3).stream()
                .map(KnowledgeDocItem::id).toList();
        assertEquals(List.of(second, first), tied, "同值组 id DESC 钉底");
    }

    /** Off-list and injection-shaped keys are refused fail-closed with a human
     *  400 naming the whitelist; nothing reaches the SQL string. */
    @Test
    void 排序_白名单外键_400点名允许集() {
        long project = newProject();
        seed(project, 0L, "TEST_SPEC", null, "s1", "ACTIVE");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> knowledgeDocService.list(project, null, null, 1, 20, "id; DROP TABLE", "asc"));
        assertTrue(ex.getMessage().contains("title"), "报错回显允许集：" + ex.getMessage());
        assertEquals(1L, knowledgeDocService.list(project, null, null, 1, 20).total(),
                "拒排序不动数据行");
    }

    /** Zero-drift guard: the legacy five-argument entry delegates to the sort
     *  overload with nulls and keeps the historical id DESC order exactly. */
    @Test
    void 排序_空键零漂移_历史id倒序不变() {
        long project = newProject();
        seed(project, 0L, "TEST_SPEC", null, "t1", "ACTIVE");
        seed(project, 0L, "TEST_CASE", null, "t2", "ACTIVE");
        List<Long> legacy = knowledgeDocService.list(project, null, null, 1, 20)
                .list().stream().map(KnowledgeDocItem::id).toList();
        List<Long> explicitNull =
                knowledgeDocService.list(project, null, null, 1, 20, null, null)
                        .list().stream().map(KnowledgeDocItem::id).toList();
        assertEquals(legacy, explicitNull);
        assertEquals(legacy.stream().sorted((a, b) -> Long.compare(b, a)).toList(), legacy,
                "默认仍为 id DESC");
    }

    /** Sort composes with the three filters: the ordered window is the
 * filtered segment, not the unfiltered table. */
    @Test
    void 排序_与筛选组合_项目切片内排序() {
        long p1 = newProject();
        long p2 = newProject();
        seedWithUpdated(p1, "TEST_CASE", "b", "ACTIVE", "2026-10-02 00:00:00");
        seedWithUpdated(p1, "TEST_CASE", "a", "INACTIVE", "2026-10-03 00:00:00");
        seedWithUpdated(p2, "TEST_SPEC", "z", "ACTIVE", "2026-10-01 00:00:00");

        PageResult<KnowledgeDocItem> ordered =
                knowledgeDocService.list(p1, null, "ACTIVE", 1, 20, "updated_at", "desc");
        assertEquals(1L, ordered.total(), "排序不改筛选切片");
        assertEquals("b", ordered.list().get(0).title());
    }

    /**
 * Ledger revision surfaces through the row view : a first
 * ledger row carries revision 1, and the bumped value on the table reaches
 * the api DTO through SELECT_DOC untouched.
 */
    @Test
    void revision随行透出_入库为一_台账递增后透出新值() {
        long project = newProject();
        long id = seed(project, 0L, "TEST_SPEC", null, "rev-a", "ACTIVE");
        assertEquals(1, knowledgeDocService.list(project, null, null, 1, 20).list().get(0).revision(),
                "first ledger row carries revision 1");

        jdbcTemplate.update("UPDATE tp_knowledge_doc SET revision = revision + 1 WHERE id = ?", id);
        assertEquals(2, knowledgeDocService.list(project, null, null, 1, 20).list().get(0).revision(),
                "bumped revision surfaces through the row view");
    }
}
