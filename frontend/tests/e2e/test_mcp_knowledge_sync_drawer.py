"""MCP knowledge sync subscription drawer E2E (front-end face of the chain).

Flow under a real browser: register an MCP server against a local streamable-http
JSON-RPC fixture (scripts/mock-mcp-server.py, real protocol on both legs), probe it
through the admin->core real chain, open the read-only tool, seed 30 subscriptions
through the real API (worst-case scale), then drive the drawer UI: ledger paging,
register form, immediate run (added count), fingerprint-dedup replay (skipped
count), reload persistence, and the R30/G guard (create disabled without a header
project context, enabled after one is picked).

Iron rules honoured: no mocked business legs (admin/core/DB real, fixture server is
the external third-party MCP provider speaking its real wire protocol); zero-tolerance
4xx/5xx + console error/warning + browser Issues via conftest collectors
(--headless=new, launched there); hard assertions only, no conditional wrappers
(iron rule 5d).
"""

import json
import socket
import subprocess
import sys
import time
import uuid
from pathlib import Path

import httpx
import pytest
from conftest import (
    API_BASE,
    ADMIN_PASSWORD,
    ADMIN_USERNAME,
    api_login,
    assert_no_errors,
    pick_project_in_header,
)
from playwright.sync_api import expect

REPO_ROOT = Path(__file__).resolve().parents[3]
FIXTURE_SCRIPT = REPO_ROOT / "scripts" / "mock-mcp-server.py"
READONLY_TOOL = "kb_search"
PAGE_SIZE = 10  # drawer ledger contract (query.size)


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@pytest.fixture(scope="module")
def mcp_fixture_server():
    """Real streamable-http JSON-RPC MCP provider process (fixture-side tooling,
    not a mock of any testPike service)."""
    port = _free_port()
    proc = subprocess.Popen(
        [sys.executable, str(FIXTURE_SCRIPT), "--port", str(port), "--mode", "normal"],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    deadline = time.time() + 10
    while time.time() < deadline:
        if proc.poll() is not None:
            raise RuntimeError("mock-mcp-server exited before the port was ready")
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=0.2):
                break
        except OSError:
            time.sleep(0.15)
    else:
        proc.kill()
        raise RuntimeError("mock-mcp-server port never came up")
    yield f"http://127.0.0.1:{port}/mcp"
    proc.terminate()
    try:
        proc.wait(timeout=5)
    except subprocess.TimeoutExpired:
        proc.kill()


def _unwrap(resp: httpx.Response) -> dict:
    body = resp.json()
    assert resp.status_code == 200, f"{resp.request.method} {resp.url} -> {resp.status_code}: {body}"
    assert body.get("code") in (0, 200, "0", "200", None) or "data" in body, body
    return body["data"]


@pytest.fixture(scope="module")
def seeded_server(mcp_fixture_server):
    """Register + probe + open one read-only tool through the real admin chain;
    returns (server_id, server_name, project_name, uniq)."""
    token = api_login(ADMIN_USERNAME, ADMIN_PASSWORD)
    h = {"Authorization": f"Bearer {token}"}
    uniq = uuid.uuid4().hex[:8]

    page_data = _unwrap(httpx.get(f"{API_BASE}/projects", params={"page": 1, "size": 50}, headers=h, timeout=10))
    projects = page_data["list"] if isinstance(page_data, dict) and "list" in page_data else page_data
    project = projects[0]
    project_name = project["name"]
    project_id = project["id"]

    with httpx.Client(base_url=API_BASE, timeout=15) as c:
        c.headers.update(h)
        server_name = f"e2e-sync-srv-{uniq}"
        srv = _unwrap(c.post("/mcp-servers", json={
            "name": server_name,
            "endpoint": mcp_fixture_server,
            "transport": 2,
        }))
        server_id = srv["id"]

        # Real handshake through admin -> core (writes the tools_json snapshot).
        probe = _unwrap(c.post(f"/mcp-servers/{server_id}/test"))
        assert probe["success"] is True, f"real MCP handshake failed: {probe}"

        _unwrap(c.put(f"/mcp-servers/{server_id}/tools", json={"toolNames": [READONLY_TOOL]}))

        # Worst-case scale: 30 subscriptions registered through the real chain.
        for i in range(30):
            _unwrap(c.post(f"/mcp-servers/{server_id}/knowledge-syncs", json={
                "projectId": project_id,
                "name": f"seed-sync-{i:02d}-{uniq}",
                "toolName": READONLY_TOOL,
                "targetProjectId": 0,
                "targetPackId": 0,
            }))

    yield {
        "id": server_id,
        "name": server_name,
        "projectName": project_name,
        "projectId": project_id,
        "uniq": uniq,
    }

    # Ledger cleanup by id (factory baseline untouched, new rows only).
    with httpx.Client(base_url=API_BASE, timeout=15) as c:
        c.headers.update(h)
        listing = _unwrap(c.get(f"/mcp-servers/{server_id}/knowledge-syncs",
                                params={"page": 1, "size": 100}))
        for row in (listing["list"] if isinstance(listing, dict) else listing):
            try:
                c.delete(f"/mcp-servers/{server_id}/knowledge-syncs/{row['id']}")
            except Exception:  # noqa: BLE001 - best-effort teardown
                pass
        c.delete(f"/mcp-servers/{server_id}")


def _open_drawer(page, server_name: str):
    page.goto(f"{__import__('conftest').BASE_URL}/#/extensions?tab=mcp", wait_until="domcontentloaded")
    row = page.locator(".el-table__row", has_text=server_name).first
    expect(row).to_be_visible(timeout=15000)
    row.get_by_role("button", name="同步订阅").click()
    drawer = page.locator('[data-test="ksync-drawer"]')
    expect(drawer).to_be_visible(timeout=10000)
    return drawer


def test_create_disabled_without_project_context(logged_in, collectors, seeded_server):
    """G/R30 guard: with no header project, the register entry is disabled and
    points at the fix in plain words (spec, not silent grey)."""
    page = logged_in
    drawer = _open_drawer(page, seeded_server["name"])

    create_btn = drawer.locator('[data-test="ksync-create-open"]')
    expect(create_btn).to_be_disabled()
    create_btn.hover()
    # Element-plus popper class is `.el-popper` (repo-wide precedent, e.g.
    # test_chat_image_attach_spectrum.py:72); `.el-tooltip__popper` is the
    # Vue-2 Element UI name and never renders here. Text = full zh locale
    # value of mcp.syncPickProjectFirst (utils/locales/zh-CN.ts:4054).
    expect(
        page.locator(".el-popper").get_by_text("请先在顶部选择项目，再新建同步订阅").first
    ).to_be_visible(timeout=5000)

    # Ledger still renders under worst-case scale (30 rows paged at 10).
    table = drawer.locator('[data-test="ksync-table"]')
    expect(table).to_be_visible()
    expect(table.locator(".el-table__row")).to_have_count(PAGE_SIZE)
    expect(drawer.locator('[data-test="ksync-pagination"]')).to_be_visible()
    assert_no_errors(collectors, "ksync no-context guard")


def test_ledger_scale_create_run_dedup_persist(logged_in, collectors, seeded_server):
    """Main ledger face: paging at worst-case scale -> register via the form ->
    immediate run writes real fetched content (added count) -> replay dedups
    (skipped count) -> reload keeps everything."""
    page = logged_in
    pick_project_in_header(page, seeded_server["projectName"])
    drawer = _open_drawer(page, seeded_server["name"])

    table = drawer.locator('[data-test="ksync-table"]')
    expect(table.locator(".el-table__row")).to_have_count(PAGE_SIZE)
    pager = drawer.locator('[data-test="ksync-pagination"]')
    expect(pager).to_contain_text("共 30 条", timeout=10000)
    pager.locator(".btn-next").click()
    expect(table.locator(".el-table__row")).to_have_count(PAGE_SIZE)

    # Register one subscription through the UI form.
    sub_name = f"e2e-sync-{seeded_server['uniq']}"
    drawer.locator('[data-test="ksync-create-open"]').click()
    dialog = page.locator('[data-test="ksync-form-dialog"]')
    expect(dialog).to_be_visible()
    # Ownership echo (G rule): ProjectOwnerEcho renders [data-test="owner-echo"]
    # with the project name in [data-test="owner-echo-value"] (component source:
    # components/ProjectOwnerEcho.vue:14-16; same two-step assertion as the
    # repo precedent in test_a2_batch.py:54-56).
    echo = dialog.locator('[data-test="owner-echo"]')
    expect(echo.locator('[data-test="owner-echo-value"]')).to_have_text(
        seeded_server["projectName"], timeout=10000
    )
    dialog.locator('[data-test="ksync-form-name"]').fill(sub_name)
    dialog.locator('[data-test="ksync-form-tool"]').click()
    tool_popper = page.locator(".tp-ksync-tool-popper")
    tool_popper.locator(f".el-select-dropdown__item:has-text('{READONLY_TOOL}')").first.click()
    # The write-tool must never be offered: hard absence assertion (5d).
    expect(tool_popper.locator(".el-select-dropdown__item", has_text="shell_exec")).to_have_count(0)
    dialog.locator('[data-test="ksync-form-target"]').click()
    page.locator(".tp-ksync-target-popper").locator(".el-select-dropdown__item").first.click()
    expect(dialog.locator('[data-test="ksync-form-auto"]')).to_be_visible()
    dialog.locator('[data-test="ksync-form-submit"]').click()
    expect(page.locator(".el-message--success").first).to_be_visible(timeout=15000)

    sub_row = drawer.locator(".el-table__row", has_text=sub_name)
    expect(sub_row).to_be_visible(timeout=15000)
    expect(sub_row.first.locator('[data-test="ksync-counts"]')).to_have_count(0)
    expect(sub_row.first).to_contain_text("尚未同步")

    # Immediate run: real admin -> core -> probe -> tools/call -> knowledge landing.
    sub_row.locator('button:has-text("立即同步")').first.click()
    expect(sub_row.first.locator('[data-test="ksync-counts"]')).to_contain_text(
        "新增 1 条", timeout=30000
    )

    # Fingerprint replay: identical payload lands nothing new.
    sub_row.locator('button:has-text("立即同步")').first.click()
    expect(sub_row.first.locator('[data-test="ksync-counts"]')).to_contain_text(
        "去重跳过", timeout=30000
    )

    # Reload persistence through the real browser + real ledger read.
    # A goto back to the *same* hash URL is a same-document fragment
    # navigation: Chromium never reloads the document, the drawer overlay
    # from the previous step stays open and swallows the row-button click
    # (observed: "el-overlay is-drawer ... intercepts pointer events").
    # Force a real document reload so the ledger is genuinely re-read from
    # the server, then reopen the drawer on the fresh page.
    page.reload(wait_until="domcontentloaded")
    _open_drawer(page, seeded_server["name"])
    again = page.locator('[data-test="ksync-drawer"] .el-table__row', has_text=sub_name)
    expect(again).to_be_visible(timeout=15000)
    expect(again.first).to_contain_text("去重跳过")

    assert_no_errors(collectors, "ksync ledger flow")
