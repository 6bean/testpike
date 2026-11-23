
import subprocess
import time
import uuid

import httpx
import pytest
from conftest import ADMIN_PASSWORD, BASE_URL, api_login, assert_no_errors
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(240)

API = "http://localhost:8081/api/v1"
DEEPLINK = f"{BASE_URL}/#/defects"


def _clear_ctx(page):
    page.evaluate(
        "() => { for (const k of Object.keys(localStorage)) "
        "{ if (k.startsWith('tp_proj_ctx_')) localStorage.removeItem(k); } }")


def _boot(page, token):
    """Real login token injection + app boot (appauto app-package posture)."""
    page.goto(f"{BASE_URL}/#/login", wait_until="domcontentloaded")
    page.evaluate(
        "() => { localStorage.setItem('tp_access_token', '%s'); "
        "localStorage.setItem('tp_onboarding_dismissed', '1'); }" % token)
    page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
    expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)


def test_owner_echo_disabled_entry_and_readonly_echo(page, collectors):
    token = api_login("admin", ADMIN_PASSWORD)
    _boot(page, token)

    suffix = uuid.uuid4().hex[:6].upper()
    pname = f"T396b归属{suffix}"
    with httpx.Client(base_url=API, timeout=15) as api:
        api.headers["Authorization"] = f"Bearer {token}"
        r = api.post("/projects", json={"name": pname, "code": f"T396B{suffix}"})
        assert r.status_code == 200, f"create project failed: {r.text[:200]}"
        pid = r.json()["data"]

    try:
        # --- assert 1: context-less entry is disabled with the plain pointer ---
        _clear_ctx(page)
        page.goto(DEEPLINK, wait_until="domcontentloaded")
        btn = page.locator('[data-test="defect-create-btn"]')
        expect(btn).to_be_visible(timeout=15000)
        expect(btn).to_be_disabled()
        btn.hover()
        # visible tooltip carrying the pointer copy (el-popper fleet keeps
        # hidden poppers in the DOM — anchor on the accessible tooltip role)
        tip = page.get_by_role("tooltip").filter(has_text="顶栏")
        expect(tip.first).to_be_visible(timeout=8000)

        # --- assert 2: with context, dialog echoes the header project name ---
        # full reload (not a hash-only nav): the visible project fleet must be
        # re-fetched or the freshly created id is pruned by the stale-domain rule
        page.goto(f"{DEEPLINK}?projectId={pid}", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        expect(btn).to_be_enabled(timeout=15000)
        # header summary landed on the deep-linked project (context = single source)
        expect(page.locator('[data-test="project-ctx-trigger"]')).to_contain_text(
            pname, timeout=15000)
        btn.click()
        dialog = page.locator('[data-test="defect-create-dialog"]')
        expect(dialog).to_be_visible(timeout=10000)
        echo = dialog.locator('[data-test="owner-echo"]')
        expect(echo).to_be_visible()
        expect(echo.locator('[data-test="owner-echo-value"]')).to_have_text(pname)
        # rule 1: echo is read-only text, never a selector inside the dialog
        expect(dialog.locator('[data-test="owner-echo"] select, [data-test="owner-echo"] .el-select')).to_have_count(0)

        assert_no_errors(collectors, "T396b owner echo defects page")
    finally:
        _clear_ctx(page)
        with httpx.Client(base_url=API, timeout=30) as api:
            api.headers["Authorization"] = f"Bearer {token}"
            api.delete(f"/projects/{pid}/purge", params={"force": "true"})
        out = subprocess.run(
            ["docker", "exec", "testpike-postgres", "psql", "-U", "testpike",
             "-d", "testpike", "-tAc",
             f"SELECT count(*) FROM tp_defect WHERE project_id={pid}"],
            capture_output=True, text=True)
        assert out.stdout.strip() == "0", f"defect rows survived purge: {out.stdout}"


PERF_DEEPLINK = f"{BASE_URL}/#/perf"


def test_owner_echo_perf_wizard_inherits_header_context(page, collectors):
    """T396b pack-3: perf workbench entry gate + wizard echo (legacy in-wizard
    project selector retired — the dialog must carry zero selector inside the
    echo and zero pw-project anywhere, single source = header context)."""
    token = api_login("admin", ADMIN_PASSWORD)
    _boot(page, token)

    suffix = uuid.uuid4().hex[:6].upper()
    pname = f"T396b性能{suffix}"
    with httpx.Client(base_url=API, timeout=15) as api:
        api.headers["Authorization"] = f"Bearer {token}"
        r = api.post("/projects", json={"name": pname, "code": f"T396P{suffix}"})
        assert r.status_code == 200, f"create project failed: {r.text[:200]}"
        pid = r.json()["data"]

    try:
        # --- assert 1: context-less perf entry is disabled with the pointer ---
        _clear_ctx(page)
        page.goto(PERF_DEEPLINK, wait_until="domcontentloaded")
        btn = page.locator('[data-test="perf-create-btn"]')
        expect(btn).to_be_visible(timeout=15000)
        expect(btn).to_be_disabled()
        btn.hover()
        tip = page.get_by_role("tooltip").filter(has_text="顶栏")
        expect(tip.first).to_be_visible(timeout=8000)

        # --- assert 2: deep-linked context, wizard echoes the header name ---
        # full reload refreshes the visible-project fleet so the fresh id
        # survives the stale-domain pruning rule (known pitfall 2)
        page.goto(f"{PERF_DEEPLINK}?projectId={pid}", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        expect(btn).to_be_enabled(timeout=15000)
        expect(page.locator('[data-test="project-ctx-trigger"]')).to_contain_text(
            pname, timeout=15000)
        btn.click()
        dialog = page.locator('[data-test="perf-wizard"]')
        expect(dialog).to_be_visible(timeout=10000)
        echo = dialog.locator('[data-test="owner-echo"]')
        expect(echo).to_be_visible()
        expect(echo.locator('[data-test="owner-echo-value"]')).to_have_text(pname)
        # iron rule 1: read-only echo, never a nested selector
        expect(dialog.locator('[data-test="owner-echo"] .el-select')).to_have_count(0)
        # legacy in-wizard selector is retired site-wide on this dialog
        expect(page.locator('[data-test="pw-project"]')).to_have_count(0)

        assert_no_errors(collectors, "T396b owner echo perf wizard")
    finally:
        _clear_ctx(page)
        with httpx.Client(base_url=API, timeout=30) as api:
            api.headers["Authorization"] = f"Bearer {token}"
            api.delete(f"/projects/{pid}/purge", params={"force": "true"})
        out = subprocess.run(
            ["docker", "exec", "testpike-postgres", "psql", "-U", "testpike",
             "-d", "testpike", "-tAc",
             f"SELECT count(*) FROM tp_perf_scenario WHERE project_id={pid}"],
            capture_output=True, text=True)
        assert out.stdout.strip() == "0", f"perf rows survived purge: {out.stdout}"


MOCK_SPACES_DEEPLINK = f"{BASE_URL}/#/mock/spaces"


def test_owner_echo_mock_spaces_entry_and_dialog(page, collectors):
    """T396b pack-4: MockSpaces converged from page-level exemption to the
    same posture as every other context page — gated header entry (rule 2)
    plus read-only ownership echo inside the create dialog (rule 1).
    No space is submitted: the dialog is opened for the echo assert, then
    cancelled, so the project purge in finally leaves zero residue."""
    token = api_login("admin", ADMIN_PASSWORD)
    _boot(page, token)

    suffix = uuid.uuid4().hex[:6].upper()
    pname = f"T396b仿真{suffix}"
    with httpx.Client(base_url=API, timeout=15) as api:
        api.headers["Authorization"] = f"Bearer {token}"
        r = api.post("/projects", json={"name": pname, "code": f"T396S{suffix}"})
        assert r.status_code == 200, f"create project failed: {r.text[:200]}"
        pid = r.json()["data"]

    try:
        # --- assert 1: context-less entry disabled + pointer tooltip ---
        _clear_ctx(page)
        page.goto(MOCK_SPACES_DEEPLINK, wait_until="domcontentloaded")
        btn = page.locator('[data-test="mock-spaces-new"]')
        expect(btn).to_be_visible(timeout=15000)
        expect(btn).to_be_disabled()
        btn.hover()
        tip = page.get_by_role("tooltip").filter(has_text="顶栏")
        expect(tip.first).to_be_visible(timeout=8000)
        # page-level honest empty block stays as the belt piece
        expect(page.locator('[data-test="mock-spaces-no-project"]')).to_be_visible()

        # --- assert 2: deep-linked context, dialog echoes the header name ---
        page.goto(f"{MOCK_SPACES_DEEPLINK}?projectId={pid}", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        expect(btn).to_be_enabled(timeout=15000)
        expect(page.locator('[data-test="project-ctx-trigger"]')).to_contain_text(
            pname, timeout=15000)
        btn.click()
        dialog = page.locator('[data-test="mock-spaces-create-dialog"]')
        expect(dialog).to_be_visible(timeout=10000)
        echo = dialog.locator('[data-test="owner-echo"]')
        expect(echo).to_be_visible()
        expect(echo.locator('[data-test="owner-echo-value"]')).to_have_text(pname)
        # iron rule 1: read-only echo, never a nested selector
        expect(dialog.locator('[data-test="owner-echo"] .el-select')).to_have_count(0)
        page.locator('[data-test="mock-spaces-create-cancel"]').click()

        assert_no_errors(collectors, "T396b owner echo mock spaces")
    finally:
        _clear_ctx(page)
        with httpx.Client(base_url=API, timeout=30) as api:
            api.headers["Authorization"] = f"Bearer {token}"
            api.delete(f"/projects/{pid}/purge", params={"force": "true"})
        out = subprocess.run(
            ["docker", "exec", "testpike-postgres", "psql", "-U", "testpike",
             "-d", "testpike", "-tAc",
             f"SELECT count(*) FROM tp_mock_space WHERE project_id={pid}"],
            capture_output=True, text=True)
        assert out.stdout.strip() == "0", f"mock space rows survived purge: {out.stdout}"


TAG_DEEPLINK = f"{BASE_URL}/#/tags"


def test_owner_echo_tag_list_entry_and_dialog(page, collectors):
    """ closing: TagListView was the missed page in the T396b inventory
 sweep (S-3 scanner caught it at the closing gate) — the create dialog now
 hosts the read-only ownership echo and the header entry keeps the gated
 tooltip posture. No tag is submitted: dialog opened for the echo assert,
 then cancelled, so the project purge in finally leaves zero residue."""
    token = api_login("admin", ADMIN_PASSWORD)
    _boot(page, token)

    suffix = uuid.uuid4().hex[:6].upper()
    pname = f"T363标签{suffix}"
    with httpx.Client(base_url=API, timeout=15) as api:
        api.headers["Authorization"] = f"Bearer {token}"
        r = api.post("/projects", json={"name": pname, "code": f"T363G{suffix}"})
        assert r.status_code == 200, f"create project failed: {r.text[:200]}"
        pid = r.json()["data"]

    try:
        # --- assert 1: context-less entry disabled + pointer tooltip ---
        _clear_ctx(page)
        page.goto(TAG_DEEPLINK, wait_until="domcontentloaded")
        btn = page.locator('[data-test="tag-create-btn"]')
        expect(btn).to_be_visible(timeout=15000)
        expect(btn).to_be_disabled()
        btn.hover()
        tip = page.get_by_role("tooltip").filter(has_text="顶栏")
        expect(tip.first).to_be_visible(timeout=8000)

        # --- assert 2: deep-linked context, dialog echoes the header name ---
        # full reload after deep link: the visible project fleet must be
        # re-fetched or the fresh id gets pruned by the stale-domain rule
        page.goto(f"{TAG_DEEPLINK}?projectId={pid}", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        expect(btn).to_be_enabled(timeout=15000)
        expect(page.locator('[data-test="project-ctx-trigger"]')).to_contain_text(
            pname, timeout=15000)
        btn.click()
        dialog = page.locator('[data-test="tag-create-dialog"]')
        expect(dialog).to_be_visible(timeout=10000)
        echo = dialog.locator('[data-test="owner-echo"]')
        expect(echo).to_be_visible()
        expect(echo.locator('[data-test="owner-echo-value"]')).to_have_text(pname)
        # iron rule 1: read-only echo, never a nested selector
        expect(dialog.locator('[data-test="owner-echo"] .el-select')).to_have_count(0)
        page.locator('[data-test="tag-create-cancel"]').click()

        assert_no_errors(collectors, "T363 owner echo tag list")
    finally:
        _clear_ctx(page)
        with httpx.Client(base_url=API, timeout=30) as api:
            api.headers["Authorization"] = f"Bearer {token}"
            api.delete(f"/projects/{pid}/purge", params={"force": "true"})
        out = subprocess.run(
            ["docker", "exec", "testpike-postgres", "psql", "-U", "testpike",
             "-d", "testpike", "-tAc",
             f"SELECT count(*) FROM tp_tag_dict WHERE project_id={pid}"],
            capture_output=True, text=True)
        assert out.stdout.strip() == "0", f"tag rows survived purge: {out.stdout}"
