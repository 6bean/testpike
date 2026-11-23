"""E2E for the two evaluation doors shipped by the AI-evaluation write facade.

Covers, against a real browser plus the chain-lane admin/core services and the
lane's own database (no mocks, no skipped layers):

Eval-set management page (``/#/judge/evalset``)
- Deep link ``?setName=&version=1`` loads the seeded samples; total chip matches.
- Keyword narrowing reduces the visible rows and is written into the page URL,
  so a reload restores the same filtered view (page state = URL, single source).
- Manual-add door: the confirm button stays disabled with an on-screen hint
  while the draft payload is empty, and publishing a new line produces a *new
  version* instead of overwriting version 1 (lineage semantics).

Evaluation run-history page (``/#/judge/runs``)
- Seeded run is listed with its status chip.
- Create door: submit stays disabled until eval-set version, rubric and target
  are all picked (nothing is typed free-form), then the created run appears.
- Row action "report" lands on ``/#/judge/report?runId=`` and loads that run's
  scorecard — the same deep-link slot the K2 notification uses.

Seeding uses SQL inserts into the lane database for the immutable fixtures and
the real HTTP doors for everything under test, mirroring the report-page test.
Namespaced rows (prefix ``NS``) are removed in ``finally`` with a zero-residual
re-scan, and the three zero-tolerance classes (non-2xx/4xx-5xx responses,
console errors/warnings, browser Issues) are asserted at the end of each test.
"""
import hashlib
import json
import uuid
from urllib.parse import quote

import pytest
from conftest import assert_no_errors, inject_token, sql_query
from playwright.sync_api import expect

# The two doors share one fixture namespace (NS) and each _seed() clears it
# wholesale, so xdist must keep them on a single worker (loadgroup) — running
# them in parallel wiped a sibling's seeded rows mid-test (measured: the set
# door got a fail-closed 400 on its version query after reload while the peer
# worker was cleaning up).
pytestmark = [pytest.mark.timeout(900), pytest.mark.xdist_group("eval-facade-doors")]

NS = "eval-facade-e2e."
TABLE_ROWS = ".el-table__body tr.el-table__row"


def _q(value: str) -> str:
    """Escape a Python string for embedding into a single-quoted SQL literal."""
    return value.replace("'", "''")


def _scalar(stmt: str) -> str:
    """Run an INSERT ... RETURNING and keep the first value line of stdout."""
    return sql_query(stmt).strip().splitlines()[0].strip()


def _cleanup() -> None:
    for stmt in (
        f"DELETE FROM tp_judge_review WHERE run_id IN "
        f"(SELECT id FROM tp_judge_run WHERE target_ref LIKE '{_q(NS)}%')",
        f"DELETE FROM tp_judge_record WHERE run_id IN "
        f"(SELECT id FROM tp_judge_run WHERE target_ref LIKE '{_q(NS)}%')",
        f"DELETE FROM tp_judge_run WHERE target_ref LIKE '{_q(NS)}%'",
        f"DELETE FROM tp_judge_sample WHERE set_name LIKE '{_q(NS)}%'",
        f"DELETE FROM tp_judge_rubric WHERE name LIKE '{_q(NS)}%'",
    ):
        sql_query(stmt)


def _digest(rows: list[tuple[str, str]]) -> str:
    """Canonical digest over (input, expected) pairs, same shape as the core door."""
    def part(value: str) -> str:
        text = value or ""
        return f"{len(text)}:{text}|"

    canonical = "\n".join(sorted(part(i) + part(e) + "[]" + "|0" for i, e in rows))
    return hashlib.sha256(canonical.encode()).hexdigest()


def _seed() -> dict:
    """Insert rubric + eval-set version 1 (two samples) + one pending run."""
    suffix = uuid.uuid4().hex[:8]
    set_name = f"{NS}集-{suffix}"
    rubric_name = f"{NS}标准-{suffix}"
    domain = f"case-gen-{suffix}"
    dims = [
        {"name": "合规性", "definition": "格式规范", "weight": 0.6, "anchorExamples": []},
        {"name": "完整性", "definition": "覆盖完整", "weight": 0.4, "anchorExamples": []},
    ]
    _cleanup()
    sql_query(
        "INSERT INTO tp_judge_rubric (name, domain, version, dimensions, rules, "
        "pass_score, w_rule, w_llm, status, created_by) VALUES ("
        f"'{_q(rubric_name)}', '{_q(domain)}', 1, "
        f"'{_q(json.dumps(dims, ensure_ascii=False))}'::jsonb, '[]'::jsonb, "
        "60, 0.400, 0.600, 1, 1)"
    )
    snapshot = json.dumps(
        {"name": rubric_name, "domain": domain, "version": 1, "dimensions": dims,
         "rules": [], "passScore": 60, "wRule": 0.4, "wLlm": 0.6},
        ensure_ascii=False,
    )
    line_a = f"{NS} 会话续期用例：令牌到期前自动刷新且不打断当前操作"
    line_b = f"{NS} 订单导出用例：导出九十天订单并核对金额与状态字段"
    digest = _digest([(line_a, line_a), (line_b, line_b)])
    for text in (line_a, line_b):
        sql_query(
            "INSERT INTO tp_judge_sample (set_name, version, snapshot_digest, input, "
            "expected, reference_docs, source, sensitive, created_by) VALUES ("
            f"'{_q(set_name)}', 1, '{digest}', '{_q(text)}', '{_q(text)}', "
            "'[]'::jsonb, 'manual', 0, 1)"
        )
    run_id = _scalar(
        "INSERT INTO tp_judge_run (rubric_name, rubric_domain, rubric_version, "
        "rubric_snapshot, evalset_name, evalset_version, evalset_digest, "
        "target_type, target_ref, llm_config_id, status, created_by) VALUES ("
        f"'{_q(rubric_name)}', '{_q(domain)}', 1, '{_q(snapshot)}'::jsonb, "
        f"'{_q(set_name)}', 1, '{digest}', 'pipeline', "
        f"'{_q(NS + suffix)}.seeded', NULL, 0, 1) RETURNING id"
    )
    return {"set": set_name, "rubric": rubric_name, "run": run_id, "suffix": suffix,
            "marker_a": "会话续期"}


def _rows(page, table_selector: str) -> int:
    return page.locator(f"[data-test='{table_selector}'] {TABLE_ROWS}").count()


def _pick_option(page, selector: str, needle: str) -> None:
    """Open an Element Plus filterable select and click the option holding ``needle``.

    Element Plus keeps every select's popper mounted but hidden (persistent
    tooltip), so a page-wide ``.el-select-dropdown__item`` search can resolve
    to a closed popper's duplicate. Scope to the currently visible popper.
    """
    page.locator(f"[data-test='{selector}']").click()
    option = page.locator(
        ".el-select-dropdown:visible .el-select-dropdown__item", has_text=needle
    ).first
    option.wait_for(state="visible")
    option.click()


def test_eval_set_door(page, collectors):
    """Eval-set page: deep link load, keyword narrowing, manual publish lineage."""
    seed = _seed()
    base = None
    try:
        inject_token(page)
        base = page.url.split("#")[0]

        # 1. Deep link on set name + version 1 loads the seeded samples.
        page.goto(f"{base}#/judge/evalset?setName={quote(seed['set'])}&version=1",
                  wait_until="domcontentloaded")
        expect(page.locator("[data-test='judge-evalset-page']")).to_be_visible()
        page.locator("[data-test='evalset-search-btn']").click()
        expect(page.locator(f"[data-test='evalset-sample-table'] {TABLE_ROWS}")).to_have_count(2)
        expect(page.locator("[data-test='evalset-total-chip']")).to_contain_text("2")

        # 2. Keyword narrowing shrinks the visible rows and lands in the URL.
        page.locator("[data-test='evalset-keyword-input']").fill(seed["marker_a"])
        page.locator("[data-test='evalset-search-btn']").click()
        expect(page.locator(f"[data-test='evalset-sample-table'] {TABLE_ROWS}")).to_have_count(1)
        expect(page.locator("[data-test='evalset-total-chip']")).to_contain_text("1")
        page.wait_for_timeout(400)
        assert "keyword=" in page.url, f"filtered view is not restorable: {page.url}"

        # 3. Reload from the same URL restores the narrowed view (page state = URL).
        page.reload(wait_until="domcontentloaded")
        expect(page.locator(f"[data-test='evalset-sample-table'] {TABLE_ROWS}")).to_have_count(1)

        # 4. Manual door refuses an empty payload before any request leaves (R28 guard).
        page.locator("[data-test='evalset-keyword-input']").fill("")
        page.locator("[data-test='evalset-search-btn']").click()
        expect(page.locator(f"[data-test='evalset-sample-table'] {TABLE_ROWS}")).to_have_count(2)
        page.locator("[data-test='evalset-manual-btn']").click()
        dialog = page.locator("[data-test='evalset-write-dialog']")
        expect(dialog).to_be_visible()
        confirm = page.locator("[data-test='evalset-write-confirm-btn']")
        expect(confirm).to_be_disabled()
        expect(page.locator("[data-test='evalset-write-guard']")).to_be_visible()

        # 5. One fully filled draft line publishes a NEW version; version 1 stays intact.
        new_line = f"{NS} 新增评测样本：校验表单必填项缺失时逐项点名"
        cells = dialog.locator(".draft-cell input")
        cells.nth(0).fill(new_line)
        cells.nth(1).fill(new_line)
        expect(confirm).to_be_enabled()
        confirm.click()
        page.wait_for_timeout(3000)
        expect(page.locator(f"[data-test='evalset-sample-table'] {TABLE_ROWS}")).to_have_count(3)

        versions = _sql_versions(seed["set"])
        assert versions == [1, 2], f"publish must append a version, got {versions}"
        assert _sql_sample_count(seed["set"], 1) == 2, "version 1 was overwritten"

        # 6. Switching the version field back to 1 shows the untouched lineage.
        # ``data-test`` sits on the el-input-number wrapper; type into the inner input.
        version_field = page.locator("[data-test='evalset-version-input'] input")
        version_field.fill("1")
        version_field.press("Enter")
        page.locator("[data-test='evalset-search-btn']").click()
        expect(page.locator(f"[data-test='evalset-sample-table'] {TABLE_ROWS}")).to_have_count(2)

        assert_no_errors(collectors, "eval-set door E2E")
    finally:
        _cleanup()
        assert sql_query(
            f"SELECT count(*) FROM tp_judge_sample WHERE set_name LIKE '{_q(NS)}%'"
        ).strip() == "0", "fixture rows survived cleanup"


def test_eval_run_door(page, collectors):
    """Run-history page: seeded run listed, create door, report deep link."""
    seed = _seed()
    try:
        inject_token(page)
        base = page.url.split("#")[0]

        # 1. The seeded pending run is listed with its status chip.
        page.goto(f"{base}#/judge/runs?setName={quote(seed['set'])}",
                  wait_until="domcontentloaded")
        expect(page.locator("[data-test='judge-runs-page']")).to_be_visible()
        page.locator("[data-test='judge-runs-search-btn']").click()
        expect(page.locator(f"[data-test='judge-runs-table'] {TABLE_ROWS}")).to_have_count(1)
        expect(page.locator(f"[data-test='judge-run-status-{seed['run']}']")).to_be_visible()

        # 2. The create door refuses an incomplete payload (nothing typed, all picked).
        page.locator("[data-test='judge-runs-create-btn']").click()
        dialog = page.locator("[data-test='judge-runs-create-dialog']")
        expect(dialog).to_be_visible()
        submit = page.locator("[data-test='judge-run-create-submit']")
        expect(submit).to_be_disabled()
        expect(page.locator("[data-test='judge-run-create-guard']")).to_be_visible()

        # 3. Pick set version + rubric + target, then submit creates the run.
        _pick_option(page, "judge-run-create-evalset", seed["set"])
        _pick_option(page, "judge-run-create-rubric", seed["rubric"])
        # ElInput forwards data-test onto the inner native input itself (unlike
        # el-input-number, whose wrapper keeps it), so no descendant ``input`` here.
        dialog.locator("[data-test='judge-run-create-ref']").fill(f"{NS}{seed['suffix']}.target")
        expect(submit).to_be_enabled()
        submit.click()
        page.wait_for_timeout(3000)
        expect(page.locator(f"[data-test='judge-runs-table'] {TABLE_ROWS}")).to_have_count(2)

        # 4. The row action lands on the report deep link and loads that run (K2 slot).
        # After the create step both rows carry the same eval-set name, so anchor
        # the target row on the seeded run's per-id status chip instead of text.
        row = page.locator(
            f"[data-test='judge-runs-table'] {TABLE_ROWS}",
            has=page.locator(f"[data-test='judge-run-status-{seed['run']}']"),
        )
        row.locator("[data-test='judge-runs-report-btn']").click()
        page.wait_for_timeout(2500)
        assert f"runId={seed['run']}" in page.url, f"deep link missing run id: {page.url}"
        expect(page.locator("[data-test='judge-report-page']")).to_be_visible()
        expect(page.locator("[data-test='judge-summary-meta']")).to_contain_text(seed["set"])

        assert_no_errors(collectors, "eval-run door E2E")
    finally:
        _cleanup()
        assert sql_query(
            f"SELECT count(*) FROM tp_judge_run WHERE target_ref LIKE '{_q(NS)}%'"
        ).strip() == "0", "fixture runs survived cleanup"


def _sql_versions(set_name: str) -> list[int]:
    return [int(v) for v in sql_query(
        f"SELECT version FROM tp_judge_sample WHERE set_name = '{_q(set_name)}' "
        "GROUP BY version ORDER BY version").split()]


def _sql_sample_count(set_name: str, version: int) -> int:
    return int(sql_query(
        f"SELECT count(*) FROM tp_judge_sample WHERE set_name = '{_q(set_name)}' "
        f"AND version = {version}").strip() or "0")
