
import uuid

import httpx
import pytest
from conftest import (
    ADMIN_PASSWORD,
    API_BASE,
    TESTER_PASSWORD,
    _set_sys_config_value,
    add_member,
    api_login,
    assert_no_errors,
    mk_tester,
)
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(300)

API = API_BASE
BASE = API_BASE.replace("/api/v1", "").replace(":8081", ":5173")


def inject_token(page):
    pass
    token = api_login("admin", ADMIN_PASSWORD)
    page.goto(f"{BASE}/#/login", wait_until="domcontentloaded")
    page.evaluate(f"() => {{ localStorage.setItem('tp_access_token', '{token}'); localStorage.setItem('tp_onboarding_dismissed', '1') }}")
    page.goto(f"{BASE}/#/dashboard", wait_until="domcontentloaded")
    expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
    return page, token


def _strip_self403(collectors):
    pass
    collectors["network_4xx"] = [
        e for e in collectors["network_4xx"]
        if not ("/api/v1/appauto/upgrade-window" in e["url"] and e["status"] == 403)]
    collectors["console_errors"] = [
        c for c in collectors["console_errors"] if "status of 403" not in c]


def _clear_header_context(page):
    """: the header context persists per user in localStorage
 (tp_proj_ctx_<userId>) and the pinia store survives hash-route switches —
 the no-project disabled negative must therefore wipe every stored context
 key and hard-reload, not just removeItem."""
    page.evaluate(
        "() => { for (let i = localStorage.length - 1; i >= 0; i--) {"
        " const k = localStorage.key(i);"
        " if (k && k.startsWith('tp_proj_ctx_')) localStorage.removeItem(k); } }")
    page.reload(wait_until="domcontentloaded")


def _deep_link_engine_center(page, pid=None):
    """: page-level selector removed — ?projectId deep link lands the segment.
 Dashboard bounce forces a remount (query-only navigation reuses the component)."""
    page.goto(f"{BASE}/#/dashboard", wait_until="domcontentloaded")
    expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
    url = f"{BASE}/#/appauto/engines" + (f"?projectId={pid}" if pid else "")
    page.goto(url, wait_until="domcontentloaded")
    expect(page.locator('[data-test="engine-center-page"]')).to_be_visible(timeout=15000)


def test_appauto_support_insight(page, collectors):
    pass
    page, token = inject_token(page)
    h = {"Authorization": f"Bearer {token}"}
    api = httpx.Client(base_url=API, timeout=30, headers=h)
    uid = uuid.uuid4().hex[:8]
    pname = f"E2E支持度{uid}"
    pcode = f"e2esup{uid}"

    r = api.post("/projects", json={"name": pname, "code": pcode})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建项目夹具失败: {r.text[:300]}"
    pid = r.json()["data"]
    tuser = None
    pid2 = None

    try:
        # ── 1. engine center → support button disabled with no project context
        # (negative; wipe the persisted per-user context before entering) ──
        _clear_header_context(page)
        _deep_link_engine_center(page)
        btn = page.locator('[data-test="engine-support-btn"]')
        expect(btn).to_be_visible(timeout=8000)
        expect(btn).to_be_disabled()

        # ── 2. a ?projectId deep link lands the global context → button enables →
        # drawer opens → catalog-driven rows all render ──
        _deep_link_engine_center(page, pid)
        expect(btn).to_be_enabled(timeout=8000)
        btn.click()
        drawer = page.locator('[data-test="engine-support-drawer"]')
        expect(drawer).to_be_visible(timeout=10000)
        mw = page.locator('[data-test="engine-support-row-MOBILEWRIGHT"]')
        expect(mw).to_be_visible(timeout=15000)
        expect(mw.locator('[data-test="engine-support-version-tag"]')).to_be_visible(timeout=8000)
        expect(mw.locator('[data-test="engine-support-health-tag"]')).to_be_visible(timeout=8000)
        expect(mw.locator('[data-test="engine-support-conclusion"]')).to_be_visible(timeout=8000)
        caliber = mw.locator('[data-test="engine-support-caliber"]')
        expect(caliber).to_be_visible(timeout=8000)
        assert len((caliber.inner_text() or "").strip()) > 10, "口径注脚缺失=验收硬项不满足"

        #    and the drawer's modal overlay swallows clicks (measured pitfall) —
        #    Esc-close the drawer before page.reload;
        # : the deep-link URL carries ?projectId, so after reload the context
        #    comes back from localStorage with no re-picking needed) ──
        page.keyboard.press("Escape")
        expect(drawer).to_be_hidden(timeout=5000)
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="engine-center-page"]')).to_be_visible(timeout=15000)
        expect(btn).to_be_enabled(timeout=8000)
        page.locator('[data-test="engine-support-btn"]').click()
        expect(drawer).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="engine-support-row-MOBILEWRIGHT"]')).to_be_visible(timeout=15000)

        # Closed-state is self-provisioned here: after K8 the factory default is ON,
        # so the honest 403 error line is only observable in the off state — pin it to
        # 0 through the real hot-update /configs chain (finally restores factory 1;
        # depending on the on-disk default was exactly the stale-assertion root cause).
        _set_sys_config_value("ai.appauto.upgrade-window", "0")
        page.keyboard.press("Escape")
        expect(drawer).to_be_hidden(timeout=5000)
        page.goto(f"{BASE}/#/cluster/upgrade", wait_until="domcontentloaded")
        expect(page.locator('[data-test="upgrade-center-page"]')).to_be_visible(timeout=15000)
        tab = page.locator('[data-test="upgrade-tabs"] #tab-insight')
        expect(tab).to_be_visible(timeout=8000)
        tab.click()
        expect(page.locator('[data-test="upgrade-insight-advisor"]')).to_be_visible(timeout=8000)
        expect(page.locator('[data-test="upgrade-insight-window"]')).to_be_visible(timeout=8000)
        expect(page.locator('[data-test="upgrade-insight-flaky"]')).to_be_visible(timeout=8000)

        werr = page.locator('[data-test="upgrade-window-error"]')
        expect(werr).to_be_visible(timeout=10000)
        expect(werr).to_contain_text("未开启")

        flaky_empty = page.locator('[data-test="upgrade-flaky-empty"]')
        expect(flaky_empty).to_be_visible(timeout=10000)

        run = page.locator('[data-test="upgrade-advisor-run"]')
        expect(run).to_be_visible(timeout=8000)
        expect(run).to_be_disabled()

        # ── 8. Negative proof (cross-project): tester is a member of the drawer
        #     project only; consulting advice for another project must be refused.
        # The K12 role template grants the tester the RUN family, so
        # perm:appauto:advisor:run IS held by design — the old "403 = permission
        # denied" reading was actually the closed-state rejection from years past
        # (ai.appauto.upgrade-window/advisor defaulted to off before K8). The real
        # security gate to prove here is the project-visibility guard
        # (guard.assertCanView): cross-project consultation must be refused with 403.
        r2 = api.post("/projects", json={"name": f"E2E越权{uid}", "code": f"e2eesc{uid}"})
        assert r2.status_code == 200 and r2.json().get("code") == "0", \
            f"第二个项目夹具失败: {r2.text[:300]}"
        pid2 = r2.json()["data"]
        tuser, _nick = mk_tester(api, uid)
        # membership in pid only — proves the 403 on pid2 is a cross-project refusal,
        # not a blanket lack of access.
        add_member(api, int(pid), tuser)
        ttoken = api_login(f"ttester_{uid}", TESTER_PASSWORD)
        rt = httpx.post(f"{API}/appauto/upgrade-advice",
                        json={"projectId": int(pid2), "manifestVersion": "9.9.9"},
                        headers={"Authorization": f"Bearer {ttoken}"}, timeout=30)
        rtb = rt.json()
        assert rt.status_code == 403 and str(rtb.get("code")) != "0", \
            f"tester 跨项目征询应 403 越权拒，实际 {rt.status_code} {rtb}"

        _strip_self403(collectors)
        assert_no_errors(collectors, "appauto support+insight E2E")
    finally:
        # Tail cleanup: restore the window switch to factory ON (K8 26-key default),
        # delete the tester account, purge both projects — zero residue (A8 precedent).
        _set_sys_config_value("ai.appauto.upgrade-window", "1")
        if tuser is not None:
            api.delete(f"/users/{tuser}")
        if pid2 is not None:
            api.delete(f"/projects/{pid2}/purge?force=true")
        api.delete(f"/projects/{pid}/purge?force=true")
