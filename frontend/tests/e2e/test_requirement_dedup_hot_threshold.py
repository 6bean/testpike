""" acceptance piece 3: live hot-reload of the duplicate-gate thresholds.

Real browser over the shared dev stack (no mocks): the requirement create
dialog refuses a near-duplicate probe with a human line naming the existing
id (L2 high tier at the seeded 0.85 line), raising ai.dedup.l2-high-score to
0.98 through the system-config page releases the same probe into the mid
tier, which passes the create and leaves a DEDUP_NEAR_MISS audit row. The
threshold is restored afterwards through the same hot-reload path.

Calibration note: the probe title extends the seeded title by one extra CJK
character (shared prefix, one character apart) so that pg_trgm similarity
lands inside (0.86, 0.97) -- strictly above the default high line and
strictly below the raised one. A miss raises honestly instead of loosening
the assertion.
"""
from playwright.sync_api import expect

from conftest import (
    nav_to, sql_query, assert_no_errors, pick_project_in_header,
    whitelist_expected_reject, BASE_URL, _set_sys_config_value, hard_goto,
)

HIGH_KEY = "ai.dedup.l2-high-score"
BODY = "需求版本化去重阈值热更验证"
REFUSE_TEXT = "重复检出拦截"


def _mk_project(api, uniq):
    r = api.post("/projects", json={"name": f"ALD项目_{uniq}", "code": f"ALD{uniq[:6].upper()}"})
    assert r.status_code == 200, f"create project failed: {r.status_code} {r.text[:300]}"
    body = r.json()
    assert body.get("code") == "0", f"create project rejected: {body.get('msg')}"
    return body["data"]


def _mk_requirement(api, pid, title):
    r = api.post(f"/projects/{pid}/requirements", json={
        "source": 3, "sourceId": f"SRC-{title[:12]}", "title": title,
        "acceptanceCriteria": f"验收标准：{title}",
    })
    assert r.status_code == 200, f"seed requirement failed: {r.status_code} {r.text[:300]}"
    body = r.json()
    assert body.get("code") == "0", f"seed requirement rejected: {body.get('msg')}"
    return body["data"]


def _similarity(a, b):
    out = sql_query(f"SELECT similarity('{a}', '{b}')")
    return float(out)


def _pick_probe(uniq):
    """First candidate title whose trgm score lands inside the calibration band.

    Shape note (calibration rerun 2026-11-02): swapping the single tail char
    keeps pg_trgm at 0.84 -- under the default high line 0.85, so such a
    probe would not even be blocked. Extending the base title by one extra
    CJK char lands at 0.88, stably inside the band, so the candidates append
    to the base instead of replacing its tail.
    """
    base = f"{BODY}{uniq}甲"
    for tail in ("乙", "丙", "丁", "戊"):
        probe = f"{base}{tail}"
        score = _similarity(base, probe)
        if 0.86 <= score <= 0.97:
            return base, probe, score
    raise AssertionError(
        f"calibration miss: no tail candidate landed in band (0.86, 0.97) for base {base!r}"
    )


def _open_req_page(page, project_name):
    nav_to(page, "需求点管理", "/requirements", '[data-test="req-page"]', submenu="设计用例")
    expect(page.locator('[data-test="project-ctx-select"]')).to_be_visible(timeout=10000)
    # R40 single source: the header switch helper owns the reload-retry for
    # projects created after the login /users/me snapshot (T372c3/W1C precedent).
    pick_project_in_header(page, project_name)
    expect(page.locator('[data-test="req-page"]')).to_be_visible(timeout=10000)


def _create_via_dialog(page, title):
    page.locator('[data-test="req-create"]').click()
    expect(page.locator('[data-test="req-edit-dialog"]')).to_be_visible(timeout=5000)
    page.locator('[data-test="req-title"]').fill(title)
    page.locator('[data-test="req-acceptance"]').fill(f"验收标准：{title}")
    page.locator('[data-test="req-submit"]').click()


def _count_requirements(pid):
    return int(sql_query(f"SELECT count(*) FROM tp_requirement WHERE project_id = {pid} AND deleted = 0"))


def _count_near_miss_audit():
    return int(sql_query("SELECT count(*) FROM tp_audit_log WHERE action = 'DEDUP_NEAR_MISS'"))


def test_hot_threshold_release_blocks_then_releases_with_audit(
    logged_in, api_client, collectors, uniq
):
    """Refuse at the seeded high line -> raise the line in the UI -> the same
    probe passes into the mid tier with a near-miss audit row; threshold
    restored through the same page."""
    page = logged_in
    orig_high = sql_query(f"SELECT config_value FROM tp_sys_config WHERE config_key = '{HIGH_KEY}'").strip()
    assert orig_high, f"{HIGH_KEY} missing from tp_sys_config (factory seed drift)"

    proj = _mk_project(api_client, uniq)
    pid = proj["id"] if isinstance(proj, dict) else proj
    base, probe, score = _pick_probe(uniq)

    try:
        # Seed the anchor requirement through the API (its own gate pass).
        created = _mk_requirement(api_client, pid, base)
        base_id = created["id"] if isinstance(created, dict) else created
        assert _count_requirements(pid) == 1

        # 1) Create dialog refuses the probe at the seeded high line.
        pname = f"ALD项目_{uniq}"
        _open_req_page(page, pname)
        _create_via_dialog(page, probe)
        toast = page.locator(".el-message").filter(has_text=REFUSE_TEXT).first
        expect(toast).to_be_visible(timeout=8000)
        expect(toast).to_contain_text(f"id={base_id}", timeout=8000)
        assert _count_requirements(pid) == 1, "refused create must not persist a row"
        # The refusal keeps the create dialog open by design; closing a dirty
        # form goes through the N9 leave guard (R22 F-group): X -> confirm box
        # "unsaved changes" -> discard clears the draft and closes. Follow the
        # real interaction, never force the dialog away.
        page.locator('[data-test="req-dialog-close"]').click()
        guard = page.locator(".el-message-box")
        expect(guard).to_be_visible(timeout=5000)
        expect(guard.locator(".el-message-box__title")).to_contain_text("未保存的修改")
        guard.locator('.el-message-box__btns button:has-text("丢弃草稿")').click()
        expect(page.locator('[data-test="req-edit-dialog"]')).not_to_be_visible(timeout=5000)

        # 2) Raise ai.dedup.l2-high-score to 0.98 on the system-config page.
        nav_to(page, "系统配置", "/configs", '[data-test="config-page"]', submenu="系统管理")
        row = page.locator(f'tr:has(code:text-is("{HIGH_KEY}"))')
        expect(row).to_be_visible(timeout=10000)
        row.locator('[data-test="config-edit"]').click()
        expect(page.locator('[data-test="config-dialog"]')).to_be_visible()
        page.locator('[data-test="config-value-input"]').fill("0.98")
        page.locator('[data-test="config-submit"]').click()
        expect(page.locator(f'tr:has(code:text-is("{HIGH_KEY}")) [data-test="config-plain"]')) \
            .to_contain_text("0.98", timeout=10000)
        page.goto(f"{BASE_URL}/#/configs", wait_until="domcontentloaded")
        expect(page.locator('[data-test="config-page"]')).to_be_visible()
        expect(page.locator(f'tr:has(code:text-is("{HIGH_KEY}")) [data-test="config-plain"]')) \
            .to_contain_text("0.98", timeout=10000)

        # 3) Same probe now lands in the mid tier: create passes, audit grows.
        audit_before = _count_near_miss_audit()
        _open_req_page(page, pname)
        _create_via_dialog(page, probe)
        page.wait_for_timeout(1500)  # create roundtrip through the gate
        assert _count_requirements(pid) == 2, "released probe must persist after the raise"
        assert _count_near_miss_audit() >= audit_before + 1, \
            "mid-tier pass must leave a DEDUP_NEAR_MISS audit row"
        # Hard navigation on purpose: the previous step left the document on the
        # requirements route, so a plain goto of another hash would only be a
        # same-document change (see hard_goto) and the menu click that follows
        # would resolve to a duplicate navigation with no navigation event.
        hard_goto(page, f"{BASE_URL}/#/extraction")
        _open_req_page(page, pname)
        expect(page.locator(f'[data-test="req-title-{base_id}"]')).to_be_visible(timeout=10000)
    finally:
        _set_sys_config_value(HIGH_KEY, orig_high)

    # Restored line must read back the factory value through the same page.
    # Hard navigation: the document is on the requirements route here, so the
    # configs hash would otherwise be applied as a same-document change.
    hard_goto(page, f"{BASE_URL}/#/configs")
    expect(page.locator('[data-test="config-page"]')).to_be_visible()
    expect(page.locator(f'tr:has(code:text-is("{HIGH_KEY}")) [data-test="config-plain"]')) \
        .to_contain_text(orig_high, timeout=10000)

    # The only designed browser-side reject is the step-1 refusal POST
    # (HTTP 400 / code 40001); drop exactly that one entry, nothing else.
    assert whitelist_expected_reject(collectors, f"/projects/{pid}/requirements", status=400) == 1, \
        "the designed refusal must be the only exempted 400"
    assert_no_errors(collectors, "dedup hot-threshold release spectrum")
