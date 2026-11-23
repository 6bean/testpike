
import os
import uuid

import pytest
from playwright.sync_api import expect

from conftest import BASE_URL, assert_no_errors, nav_to, pick_project_in_header


def _slot_conn():
    """psycopg2 connection to THIS chain's slot database via the single-source
    loader (conftest already bootstrapped the env at import; reuse it)."""
    import sys
    scripts = os.path.normpath(os.path.join(
        os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "scripts"))
    sys.path.insert(0, scripts)
    import db_target  # noqa: E402  # pyright: ignore[reportMissingImports]
    return db_target.db_connect()

ROW_COUNT = 32
# facet by i % 4, grade by (i // 4) % 4 -> every (facet, grade) cell holds
# exactly two rows, and the two dimensions stay independent.
FACETS = ["manual", "chat", "import", "off_agent"]
FACET_BADGE = {"manual": "人工", "chat": "站内对话", "import": "导入", "off_agent": "站外代理"}
GRADE_BADGE = {0: "无证据", 1: "站外自报", 2: "站外实证", 3: "平台复证"}


def _expected(i):
    return {
        "facet": FACETS[i % 4],
        "grade": (i // 4) % 4,
        "confidence": 3 if i < 16 else 1,
    }


@pytest.fixture()
def provenance_fixture(api_client):
    return seed_provenance(api_client)


def seed_provenance(api_client):
    suffix = uuid.uuid4().hex[:8]
    name = f"PROV项目_{suffix}"
    r = api_client.post("/projects", json={
        "name": name, "code": f"PV{suffix[:6].upper()}"})
    body = r.json()
    assert body.get("code") == "0", f"project create failed: {body}"
    # POST /projects returns Response<Long> (id scalar); keep the same
    # tolerant extraction conftest uses so either shape stays green.
    data = body["data"]
    pid = data["id"] if isinstance(data, dict) else data

    ids, titles = [], {}
    for i in range(ROW_COUNT):
        title = f"PROV{i:02d}-{suffix}"
        r = api_client.post("/cases", json={
            "projectId": pid,
            "title": title,
            "precondition": f"前置：已登录系统（{suffix}）",
            "expectedResult": f"预期：操作成功（{suffix}）",
            "priority": 1,
            "confidence": _expected(i)["confidence"],
            "endType": "web",
            "status": 1,
            "steps": [{"stepNo": 1, "description": "打开页面 /login", "isAssert": 0}],
        })
        body = r.json()
        assert body.get("code") == "0", f"case create failed: {body}"
        ids.append(body["data"]["id"])
        titles[body["data"]["id"]] = title

    # Pin provenance columns for exactly these ids (anchored, factory rows safe).
    conn = _slot_conn()
    try:
        with conn, conn.cursor() as cur:
            for i, cid in enumerate(ids):
                exp = _expected(i)
                facet, ref, source = exp["facet"], None, None
                if facet == "chat":
                    ref = f"chat:e2e-{suffix}"
                elif facet == "off_agent":
                    ref = f"mcp:e2e-{suffix}"
                elif facet == "import":
                    source = 2
                cur.execute(
                    "UPDATE tp_test_case SET source = COALESCE(%s, source), "
                    "source_ref = COALESCE(%s, source_ref), evidence_grade = %s "
                    "WHERE id = %s",
                    (source, ref, exp["grade"], cid),
                )
    finally:
        conn.close()
    return {"pid": pid, "name": name, "suffix": suffix, "ids": ids, "titles": titles}


def _titles_on_page(page):
    cells = page.locator(
        '[data-test="case-table"] tbody tr.el-table__row .case-title')
    return [cells.nth(n).inner_text().strip() for n in range(cells.count())]


def _pick(page, anchor, option_text):
    page.locator(f'[data-test="{anchor}"]').click()
    option = page.locator(
        ".el-select-dropdown:visible .el-select-dropdown__item:visible"
    ).filter(has_text=option_text).first
    expect(option).to_be_visible(timeout=5000)
    option.click()
    page.wait_for_timeout(300)


def _open_list(page, prov):
    nav_to(page, "用例管理", "/cases", '[data-test="case-page"]', submenu="设计用例")
    pick_project_in_header(page, prov["name"])
    # Widen the page so all 32 fixture rows are on a single page.
    page.locator('[data-test="case-pagination"] .el-pagination__sizes .el-select').click()
    page.locator(
        ".el-select-dropdown:visible .el-select-dropdown__item:visible"
    ).filter(has_text="50").first.click()
    page.wait_for_timeout(300)
    expect(page.locator('[data-test="case-table"] tbody tr.el-table__row')).to_have_count(ROW_COUNT)


def _expect_titles(page, want_titles):
    got = _titles_on_page(page)
    want = sorted(want_titles)
    got_norm = sorted(g for g in got)
    assert got_norm == want, f"hit set mismatch: page={got_norm} server={want}"


def test_provenance_filters_badges_and_deeplink(logged_in, api_client, collectors, provenance_fixture):
    page = logged_in
    prov = provenance_fixture
    _open_list(page, prov)

    # 1) Server truth for the two dimensions (real API, folded facet).
    server = api_client.get(
        f"/cases?projectId={prov['pid']}&page=1&size=1000").json()["data"]["list"]
    by_title = {c["title"]: c for c in server}
    facet_of = {t: c.get("sourceFacet") for t, c in by_title.items()}
    grade_of = {t: c.get("evidenceGrade") for t, c in by_title.items()}
    for i, cid in enumerate(prov["ids"]):
        t = prov["titles"][cid]
        exp = _expected(i)
        assert facet_of.get(t) == exp["facet"], f"{t}: facet {facet_of.get(t)} != {exp['facet']}"
        assert grade_of.get(t) == exp["grade"], f"{t}: grade {grade_of.get(t)} != {exp['grade']}"

    # 2) Single quick filter: facet=chat -> exactly the 8 chat rows.
    chat_titles = [t for t, f in facet_of.items() if f == "chat"]
    _pick(page, "case-filter-facet", FACET_BADGE["chat"])
    _expect_titles(page, chat_titles)

    # 3) Single quick filter: grade=self-report -> exactly the 8 grade-1 rows
    #    across every facet (facet selection cleared first via a fresh load).
    _open_list_again(page, prov)
    g1_titles = [t for t, g in grade_of.items() if g == 1]
    _pick(page, "case-filter-evidence", GRADE_BADGE[1])
    _expect_titles(page, g1_titles)

    # 4) Combined quick filters match the API's own AND set and the URL.
    import_g1 = [t for t in g1_titles if facet_of[t] == "import"]
    _pick(page, "case-filter-facet", FACET_BADGE["import"])
    _expect_titles(page, import_g1)
    _pick(page, "case-filter-evidence", GRADE_BADGE[3])
    import_g3 = [t for t, f in facet_of.items() if f == "import" and grade_of[t] == 3]
    api_and = api_client.get(
        f"/cases?projectId={prov['pid']}&page=1&size=1000"
        "&sourceFacet=import&evidenceGrade=3").json()["data"]["list"]
    want = sorted(c["title"] for c in api_and)
    assert want == sorted(import_g3), f"API AND set drifted from fixture layout: {want}"
    _expect_titles(page, want)
    assert "sourceFacet=import" in page.url and "evidenceGrade=3" in page.url, page.url

    # 5) Deep-link restore: reload keeps both selects and the hit set.
    page.reload(wait_until="domcontentloaded")
    expect(page.locator('[data-test="case-page"]')).to_be_visible()
    page.wait_for_timeout(400)
    facet_sel = page.locator('[data-test="case-filter-facet"]').inner_text()
    grade_sel = page.locator('[data-test="case-filter-evidence"]').inner_text()
    assert FACET_BADGE["import"] in facet_sel, facet_sel
    assert GRADE_BADGE[3] in grade_sel, grade_sel
    _expect_titles(page, want)

    # 6) Out-of-vocabulary query value is dropped, never sent raw.
    page.goto(f"{BASE_URL}/#/cases?projectId={prov['pid']}&sourceFacet=zebra",
              wait_until="domcontentloaded")
    page.reload(wait_until="domcontentloaded")
    expect(page.locator('[data-test="case-page"]')).to_be_visible()
    page.wait_for_timeout(500)
    expect(page.locator('[data-test="case-pagination"] .el-pagination__total')).to_contain_text(
        f"共 {ROW_COUNT}")

    # 7) Badge wording = locale vocabulary on visible rows.
    _open_list_again(page, prov)
    first_badge = page.locator('[data-test="case-facet-badge"]').first
    expect(first_badge).to_be_visible()
    seen_facet = set()
    badges = page.locator('[data-test="case-facet-badge"]')
    for n in range(badges.count()):
        seen_facet.add(badges.nth(n).inner_text().strip())
    assert seen_facet == {FACET_BADGE[f] for f in FACETS}, seen_facet
    ebadges = page.locator('[data-test="case-evidence-badge"]')
    seen_grade = set()
    for n in range(ebadges.count()):
        seen_grade.add(ebadges.nth(n).inner_text().strip())
    assert seen_grade == set(GRADE_BADGE.values()), seen_grade

    # 8) Weak-evidence escalation joins the low-confidence rule (grade<2, conf=3).
    rows = page.locator('[data-test="case-table"] tbody tr.el-table__row')
    escalated = weak = low_conf = 0
    for n in range(rows.count()):
        cls = rows.nth(n).get_attribute("class") or ""
        if "low-confidence-row" in cls:
            low_conf += 1
        if "low-evidence-row" in cls:
            escalated += 1
    conf3_weak = sum(1 for i, cid in enumerate(prov["ids"])
                     if _expected(i)["confidence"] == 3 and _expected(i)["grade"] < 2)
    conf3 = sum(1 for i in range(ROW_COUNT) if _expected(i)["confidence"] == 3)
    assert low_conf == conf3, f"legacy low-confidence tint regressed: {low_conf} != {conf3}"
    assert escalated == conf3_weak, f"escalated rows {escalated} != expected {conf3_weak}"

    assert_no_errors(collectors, "test_case_provenance_spectrum")


def _open_list_again(page, prov):
    # Re-open through a fresh navigation so every filter drops back to defaults.
    page.goto(f"{BASE_URL}/#/cases?projectId={prov['pid']}", wait_until="domcontentloaded")
    page.reload(wait_until="domcontentloaded")
    expect(page.locator('[data-test="case-page"]')).to_be_visible()
    page.wait_for_timeout(400)
    page.locator('[data-test="case-pagination"] .el-pagination__sizes .el-select').click()
    page.locator(
        ".el-select-dropdown:visible .el-select-dropdown__item:visible"
    ).filter(has_text="50").first.click()
    page.wait_for_timeout(300)
    expect(page.locator('[data-test="case-table"] tbody tr.el-table__row')).to_have_count(ROW_COUNT)
