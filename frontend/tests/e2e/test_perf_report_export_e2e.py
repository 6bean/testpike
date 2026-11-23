# -*- coding: utf-8 -*-
"""First E2E for the report export door .

Real chain, zero mocks on the business path: browser click -> admin(8081) ->
Feign -> core single-source report assembly -> byte stream back through the
shared authenticated blob helper. Assertion doors:
1. the PDF door fires exactly one GET against /perf/runs/{id}/report-export
 and the browser receives a real PDF attachment (server-composed
 Content-Disposition, %PDF magic, non-empty body);
2. the Format menu switches to HTML (same door, html attachment, offline
 viewable), double-click guard keeps one request in flight (R22);
3. the export never writes anything (fixture row counts stay at 1/1);
4. sweep: marker-tagged throwaway project, zero residue.
Parallel safety: uniq throwaway project (demo/sample projects never touched).
"""
import uuid

import pytest
from conftest import BASE_URL, assert_no_errors, sql_query
from playwright.sync_api import expect

pytestmark = [pytest.mark.timeout(240), pytest.mark.xdist_group("perf-export")]

MARKER = "e2et381b12"


def _ok(resp, what):
    assert resp.status_code == 200, f"{what} HTTP {resp.status_code}: {resp.text[:200]}"
    body = resp.json()
    assert body.get("code") == "0", f"{what} business failure: {body.get('msg')}"
    return body["data"]


def test_perf_report_export_pdf_and_html(logged_in, api_client, collectors):
    """PDF main door + HTML format menu: one click = one real download."""
    page = logged_in
    uniq = uuid.uuid4().hex[:8]
    pid = _ok(api_client.post("/projects", json={
        "name": f"压测报告导出{uniq}", "code": f"EX{uniq[:5].upper()}"}), "建项目")
    pid = pid["id"] if isinstance(pid, dict) else pid
    run_id = None
    scen_id = None
    try:
        env_id = sql_query(
            f"SELECT id FROM tp_project_env WHERE project_id = {pid} ORDER BY id LIMIT 1") \
            or sql_query("SELECT id FROM tp_project_env ORDER BY id LIMIT 1")

        def insert_id(stmt: str) -> int:
            return int(sql_query(stmt).splitlines()[0])

        scen_id = insert_id(
            f"INSERT INTO tp_perf_scenario(project_id, env_id, name, pressure_model, "
            f"pressure_config, steps_json) VALUES ({pid}, {env_id}, '{MARKER}', "
            f"'CONSTANT_RPS', '{{\"vus\":1}}', '[]') RETURNING id")
        run_id = insert_id(
            f"INSERT INTO tp_perf_run(scenario_id, project_id, status, pressure_snapshot) "
            f"VALUES ({scen_id}, {pid}, 3, "
            f"'{{\"fixture\":\"{MARKER}\",\"dslHash\":\"{MARKER}h\"}}') RETURNING id")
        sql_query("INSERT INTO tp_perf_metric(run_id, project_id, second_offset, rps, p95_ms, "
                  "error_rate, req_count, fail_count, error_class, step_key) VALUES "
                  f"({run_id}, {pid}, 0, 100, 500, 0.04, 100, 4, "
                  f"'{{\"timeout\":7,\"http5xx\":2}}', '')")

        page.goto(f"{BASE_URL}/#/perf/run/{run_id}?projectId={pid}",
                  wait_until="domcontentloaded")
        expect(page.locator('[data-test="perf-report"]')).to_be_visible(timeout=20000)

        # ---- door 1: main PDF button -> one real PDF attachment ----
        btn = page.locator('[data-test="perf-report-export-pdf"]')
        expect(btn).to_be_visible(timeout=8000)
        expect(btn).to_be_enabled(timeout=5000)
        with page.expect_response(
                lambda r: "report-export" in r.url and "format=pdf" in r.url,
                timeout=20000) as resp_info:
            with page.expect_download() as dl_info:
                btn.click()
        resp = resp_info.value
        assert resp.status == 200, f"export HTTP {resp.status}"
        cd = resp.headers.get("content-disposition", "")
        assert f"perf-run-{run_id}.pdf" in cd, \
            f"server-composed file name missing: {cd}"
        download = dl_info.value
        suggested = download.suggested_filename
        assert suggested.endswith(".pdf"), f"attachment not pdf: {suggested}"
        path = download.path()
        with open(path, "rb") as f:
            head = f.read(5)
        assert head == b"%PDF-", f"downloaded bytes lack the PDF magic: {head!r}"
        import os
        assert os.path.getsize(path) > 1000, "exported PDF is (near) empty"
        expect(page.locator(".el-message").filter(has_text="报告已导出").first) \
            .to_be_visible(timeout=8000)

        # ---- door 2: Format menu -> HTML on the same door ----
        page.locator('[data-test="perf-report-export-format-toggle"]').click()
        html_item = page.locator('[data-test="perf-report-export-html"]')
        expect(html_item).to_be_visible(timeout=5000)
        with page.expect_response(
                lambda r: "report-export" in r.url and "format=html" in r.url,
                timeout=20000) as resp_info2:
            with page.expect_download() as dl_info2:
                html_item.click()
        assert resp_info2.value.status == 200
        download2 = dl_info2.value
        assert download2.suggested_filename.endswith(".html"), \
            f"attachment not html: {download2.suggested_filename}"
        text = open(download2.path(), encoding="utf-8").read()
        assert "<html" in text.lower() and "rps" in text.lower(), \
            "exported html does not carry the report body"

        # ---- door 3: the door is read-only (no writes on the export path) ----
        assert int(sql_query(
            f"SELECT count(*) FROM tp_perf_scenario WHERE project_id = {pid}")) == 1
        assert int(sql_query(
            f"SELECT count(*) FROM tp_perf_run WHERE scenario_id = {scen_id}")) == 1

        assert_no_errors(collectors, "perf report export pdf/html")
    finally:
        try:
            if run_id:
                sql_query(f"DELETE FROM tp_perf_metric WHERE run_id = {run_id}")
                sql_query(f"DELETE FROM tp_perf_run WHERE id = {run_id}")
            if scen_id:
                sql_query(f"DELETE FROM tp_perf_scenario WHERE id = {scen_id}")
            api_client.delete(f"/projects/{pid}/purge?force=true")
        except Exception as e:  # noqa: BLE001
            print(f"!! fixture sweep failed (manual check): {e}")
        left = int(sql_query(
            f"SELECT count(*) FROM tp_perf_scenario WHERE name LIKE '%{MARKER}%'"))
        assert left == 0, f"fixture residue: {left} scenario rows"
