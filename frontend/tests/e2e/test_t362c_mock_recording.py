# -*- coding: utf-8 -*-

import json
import os
import re
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import httpx
import pytest
from playwright.sync_api import expect

from conftest import (BASE_URL, assert_no_errors, core_base_url, mock_mgmt_port,
                      mock_plane_kill, mock_plane_start, mock_traffic_port,
                      s3_bucket, s3_client,
                      sql_query, upstream_port)

pytestmark = [pytest.mark.timeout(600), pytest.mark.xdist_group("mock-plane-live")]

# Slot-aware addressing resolved by the conftest single source; an unset
# overlay keeps the main-slot values, so a plain checkout stays usable.
CORE = core_base_url()
MGMT = mock_mgmt_port()
TRAFFIC = mock_traffic_port()
UPSTREAM_PORT = upstream_port()
ALLOW_KEY = "mock.proxy.allowlist"
PAGE_URL = f"{BASE_URL}/#/mock/recording"
SPACES_URL = f"{BASE_URL}/#/mock/spaces"


def _wb(sid, pid, tab="recording"):
    """ AN-4: the recording screen rides the space workbench tab."""
    return f"{SPACES_URL}/{sid}?projectId={pid}&tab={tab}"


class Upstream(BaseHTTPRequestHandler):
    """A real upstream answering any path — the proxy forwards misses here."""

    def _serve(self):
        body = json.dumps({"source": "UPSTREAM", "path": self.path}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("X-Upstream-Marker", "UPSTREAM")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        self._serve()

    def do_POST(self):
        self._serve()

    def log_message(self, *a):
        pass


def _start_mock_server(space_key, interval_ms=800):
    return mock_plane_start(space_key, interval_ms=interval_ms,
                            recording_flush_ms=1500)


def _wait_http(url, code=200, deadline_s=240):
    deadline = time.time() + deadline_s
    while time.time() < deadline:
        try:
            if httpx.get(url, timeout=5).status_code == code:
                return True
        except Exception:
            pass
        time.sleep(2)
    return False


def _load_state():
    return httpx.get(f"http://localhost:{MGMT}/load-state", timeout=5).json() or {}


def _mk_project(api, name, code):
    r = api.post("/projects", json={"name": name, "code": code})
    assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]
    return r.json()["data"]


def _mk_space(api, pid, name, key):
    r = api.post("/mock/spaces", json={"projectId": pid, "name": name, "spaceKey": key})
    assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]
    return r.json()["data"]


def _publish(api, space_id, version):
    r = api.post(f"/mock/spaces/{space_id}/transition",
                 json={"targetStatus": 1, "expectedVersion": version})
    assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]


def _set_allowlist(api, value):
    """Whole-row PUT through the config API (same posture as the L3 script)."""
    rows = api.get("/configs?groupName=" + "接口仿真").json().get("data") or []
    cfg = next(x for x in rows if x["configKey"] == ALLOW_KEY)
    cfg["configValue"] = value
    r = api.put(f"/configs/{cfg['id']}", json=cfg)
    assert str(r.json().get("code")) == "0", r.text[:300]


def test_t362c_recording_replay_full_journey(logged_in, collectors, api_client, uniq):
    """Deep link -> disabled door -> owner echo -> open/seal via UI -> replay
    drafts (strict/fuzzy, >=30-row scroll-contained) -> confirm -> reload ->
    idempotent re-file."""
    page = logged_in
    pid = _mk_project(api_client, f"RecE2E {uniq}", f"re{uniq[:6].upper()}")
    bare_pid = _mk_project(api_client, f"RecE2Ebare {uniq}", f"rb{uniq[:6].upper()}")
    space_key = f"rec{uniq}".lower()
    space_id = _mk_space(api_client, pid, f"rec-space-{uniq}", space_key)
    _publish(api_client, space_id, 0)

    upstream = ThreadingHTTPServer(("127.0.0.1", UPSTREAM_PORT), Upstream)
    threading.Thread(target=upstream.serve_forever, daemon=True).start()

    box_id = None
    mock_proc = None
    try:
        _set_allowlist(api_client, "127.0.0.1")
        mock_plane_kill()
        mock_proc = _start_mock_server(space_key)
        assert _wait_http(f"http://localhost:{MGMT}/actuator/health"), "mock mgmt not up"
        deadline = time.time() + 60
        while time.time() < deadline:
            st = _load_state()
            if st.get("pinnedSpaceKey") == space_key and st.get("everLoaded"):
                break
            time.sleep(1)
        else:
            pytest.fail(f"rule loader never pinned the fixture space: {_load_state()}")

        # --- door: deep link lands in the header context .
        # AN-4: the recording screen is the workbench recording tab; the
        # retired page-level space combo is gone (the tab host carries it). ---
        page.goto(_wb(space_id, pid), wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="mock-recording-page"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="mock-recording-space-select"]')).to_have_count(0)
        expect(page.locator('[data-test="mock-recording-empty"]')).to_be_visible(timeout=10000)

        # --- door: a project without spaces lands (via the retired bookmark)
        # on the spaces list with the tab intent — the honest empty state
        # points at creating a space (iron rule 2 on the new IA: the workbench
        # cannot host a space that does not exist) ---
        page.goto(f"{PAGE_URL}?projectId={bare_pid}", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        expect(page).to_have_url(re.compile(r"/#/mock/spaces\?"), timeout=15000)
        expect(page.locator('[data-test="mock-spaces-empty"]')).to_be_visible(timeout=15000)

        # --- open the box through the UI on the fixture project ---
        page.goto(_wb(space_id, pid), wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        open_btn = page.locator('[data-test="mock-recording-open"]')
        expect(open_btn).to_be_enabled(timeout=10000)
        open_btn.click()
        dialog = page.locator('[data-test="mock-recording-start-dialog"]')
        expect(dialog).to_be_visible(timeout=5000)
        # ownership echo (iron rule 1): read-only single source = header name
        echo = page.locator('[data-test="owner-echo-value"]')
        expect(echo).to_have_text(f"RecE2E {uniq}", timeout=10000)
        dialog.locator('[data-test="mock-recording-f-target"]').fill(
            f"http://127.0.0.1:{UPSTREAM_PORT}/orders")
        page.locator('[data-test="mock-recording-start-confirm"]').click()
        row = page.locator('[data-test="mock-recording-table"] .el-table__row')
        expect(row).to_have_count(1, timeout=10000)
        expect(page.locator('[data-test="mock-recording-status"]')).to_have_text("录制中")
        box_id = int(sql_query(
            f"SELECT id FROM tp_mock_recording WHERE space_id={space_id} "
            f"ORDER BY id DESC LIMIT 1"))

        # wait for the pin carrying the open box: a miss must ride the proxy
        forwarded = False
        deadline = time.time() + 40
        while time.time() < deadline and not forwarded:
            r = httpx.get(f"http://localhost:{TRAFFIC}/mocks/{space_key}/orders/probe",
                          timeout=5)
            forwarded = "x-tp-mock-forwarded" in {k.lower() for k in r.headers}
            if not forwarded:
                time.sleep(1)
        assert forwarded, "proxy never started forwarding misses for the open box"

        # --- worst-size capture: 35 distinct order URLs + 1 payment POST ---
        for i in range(1, 36):
            r = httpx.get(f"http://localhost:{TRAFFIC}/mocks/{space_key}/orders/{i}",
                          timeout=5)
            assert r.status_code == 200 and "UPSTREAM" in r.text, r.text[:120]
        r = httpx.post(f"http://localhost:{TRAFFIC}/mocks/{space_key}/payments/9",
                       json={"amount": 1}, headers={"Authorization": "***"},
                       timeout=5)
        assert r.status_code == 200, r.text[:120]
        # frames must land in the core summary before the seal snapshot is read
        deadline = time.time() + 40
        frames = 0
        while time.time() < deadline:
            frames = int(sql_query(
                f"SELECT summary_json->>'totalFrames' FROM tp_mock_recording "
                f"WHERE id={box_id}") or 0)
            if frames >= 37:
                break
            time.sleep(1)
        assert frames >= 37, f"recorded frames did not reach the summary: {frames}"

        # --- seal through the UI ---
        page.locator('[data-test="mock-recording-refresh"]').click()
        expect(page.locator('[data-test="mock-recording-row-target"]')).to_contain_text(
            f"127.0.0.1:{UPSTREAM_PORT}", timeout=10000)
        # Seal verdict forensics: the seal click fires one POST .../stop through
        # the owner channel. A silent refusal (empty reason in the UI) previously
        # left the window guessing, so capture the response status and body here
        # and dump them whenever the sealed badge does not arrive.
        seal_evidence = {}

        def _note_seal_call(resp):
            if resp.url.endswith("/stop") and "/recordings/" in resp.url:
                seal_evidence["status"] = resp.status
                try:
                    seal_evidence["body"] = resp.text()[:400]
                except Exception:
                    seal_evidence["body"] = "<unread>"
        page.on("response", _note_seal_call)
        page.locator('[data-test="mock-recording-seal"]').click()
        try:
            expect(page.locator('[data-test="mock-recording-status"]')).to_have_text(
                "已封盒", timeout=15000)
        except Exception:
            shown = page.locator('[data-test="mock-recording-status"]').inner_text()[:80]
            print("SEAL-VERDICT stop-response=%r badge=%r" % (seal_evidence, shown),
                  flush=True)
            raise
        page.remove_listener("response", _note_seal_call)
        print("SEAL-VERDICT stop-response=%r" % (seal_evidence,), flush=True)

        # --- preview drawer: strict draft rows, scroll-contained at scale ---
        page.locator('[data-test="mock-recording-preview"]').click()
        drawer = page.locator('[data-test="mock-recording-preview-drawer"]')
        expect(drawer).to_be_visible(timeout=10000)
        rows = page.locator('[data-test="mock-recording-draft-list"] .draft-row')
        expect(rows).to_have_count(37, timeout=15000)
        contained = page.evaluate("""() => {
            const b = document.querySelector('[data-test="mock-recording-draft-list"]');
            return b.scrollHeight > b.clientHeight && b.clientHeight > 0;
        }""")
        assert contained, "37-row draft list is not scroll-contained in the drawer"

        # --- fuzzy spectrum collapses the id family into a template draft ---
        page.locator('[data-test="mock-recording-mode"]').get_by_text("模糊匹配").click()
        expect(rows).to_have_count(3, timeout=15000)
        expect(page.locator('[data-test="mock-recording-draft-list"]')).to_contain_text("{id}")

        # --- confirm-and-file (fuzzy): plain-word summary, no JSON dump ---
        page.locator('[data-test="mock-recording-confirm-import"]').click()
        result = page.locator('[data-test="mock-recording-last-result"]')
        expect(result).to_contain_text("新建规则 3 条", timeout=15000)
        expect(result).to_contain_text("本盒累计转正 3 条")

        # --- reload keeps truth: counter and rows are DB-persisted ---
        page.locator('[data-test="mock-recording-preview-close"]').click()
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="mock-recording-promoted"]')).to_have_text("3")
        promoted_stubs = int(sql_query(
            f"SELECT count(*) FROM tp_mock_stub WHERE space_id={space_id}"))
        assert promoted_stubs == 3, f"DB stub rows {promoted_stubs} != filed 3"

        # --- idempotence: re-filing creates 0 and skips the covered keys ---
        page.locator('[data-test="mock-recording-preview"]').click()
        expect(drawer).to_be_visible(timeout=10000)
        page.locator('[data-test="mock-recording-mode"]').get_by_text("模糊匹配").click()
        expect(rows).to_have_count(3, timeout=15000)
        page.locator('[data-test="mock-recording-confirm-import"]').click()
        expect(result).to_contain_text("新建规则 0 条", timeout=15000)
        expect(result).to_contain_text("跳过已覆盖 3 条")
        expect(result).to_contain_text("本盒累计转正 3 条")

        assert_no_errors(collectors, "T362c cassette recording journey")
    finally:
        try:
            _set_allowlist(api_client, "")
        except Exception as e:
            print(f"cleanup-warn allowlist: {e}")
        try:
            if box_id:
                ref = sql_query(f"SELECT cassette_ref FROM tp_mock_recording WHERE id={box_id}")
                sql_query(f"DELETE FROM tp_mock_recording WHERE id={box_id}")
                if ref and ref != "NULL":
                    s3_client().delete_object(Bucket=s3_bucket(), Key=ref)
            sql_query(f"DELETE FROM tp_mock_stub WHERE space_id={space_id}")
            sql_query(f"DELETE FROM tp_mock_space WHERE id={space_id}")
            api_client.delete(f"/projects/{pid}/purge")
            api_client.delete(f"/projects/{bare_pid}/purge")
        except Exception as e:
            print(f"cleanup-warn rows: {e}")
        mock_plane_kill()
        # Restore the resident plane in its pinned form: the inspector
        # provenance journey (test_t366e3c) serves the resident fixture space
        # ivo-1dc2693b-e, so an idle no-key plane would fail that door. Wait
        # until it answers again — a cold restart window must not strand the
        # next test on this worker with "connection refused".
        _start_mock_server(os.environ.get("MOCK_RESIDENT_SPACE_KEY",
                                          "ivo-1dc2693b-e"), interval_ms=3000)
        if not _wait_http(f"http://localhost:{MGMT}/actuator/health"):
            print("cleanup-warn: restored mock-server never became healthy")
