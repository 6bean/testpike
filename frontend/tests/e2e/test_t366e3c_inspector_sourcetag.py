# -*- coding: utf-8 -*-

import time

import httpx
import pytest
from playwright.sync_api import expect

from conftest import BASE_URL, assert_no_errors, sql_query

pytestmark = [pytest.mark.timeout(300), pytest.mark.xdist_group("mock-plane-live")]

CORE = "http://localhost:8083"
TRAF = "http://localhost:18086"
MASTER = "ai.mock.enabled"
SUB = "ai.mock.llm-fallback"


def cfg_row(key):
    r = sql_query(f"SELECT id || '|' || config_value || '|' || group_name || '|' "
                  f"|| value_type || '|' || encrypt_flag || '|' || coalesce(description,'') "
                  f"FROM tp_sys_config WHERE config_key='{key}'")
    assert r, f"config key missing: {key}"
    cid, value, group, vtype, enc, desc = r.split("|", 5)
    return int(cid), value, group, vtype, enc, desc


def set_gate(key, value):
    """Flip a K8 key through the core write door (cache-consistent, pose)."""
    cid, old, group, vtype, enc, desc = cfg_row(key)
    r = httpx.put(f"{CORE}/internal/configs/{cid}", json={
        "configKey": key, "configValue": value, "groupName": group,
        "valueType": vtype, "encryptFlag": int(enc), "description": desc}, timeout=30)
    assert r.status_code == 200, r.text[:200]
    return old


def test_t366e3c_inspector_source_tag(logged_in, collectors, uniq):
    """llm provenance rides the live plane into the browser badge; miss stays untagged."""
    page = logged_in
    mark = f"t366e3c{uniq[:6]}"
    space_row = sql_query("SELECT id || '|' || project_id FROM tp_mock_space "
                          "WHERE space_key='ivo-1dc2693b-e'")
    assert space_row, "resident fixture space missing"
    space_id, project_id = (int(x) for x in space_row.split("|"))

    orig_master = orig_sub = None
    try:
        orig_master = set_gate(MASTER, "1")
        orig_sub = set_gate(SUB, "1")

        # 1) real request on the paid rung -> plane answers with source=llm
        rr = httpx.get(f"{TRAF}/mocks/ivo-1dc2693b-e/{mark}/orders/777", timeout=90)
        assert rr.status_code != 500, f"fallback leg broke the plane: {rr.status_code}"

        # 2) fallback gate closed -> the next request is a plain honest 404
        set_gate(MASTER, "0")
        rm = httpx.get(f"{TRAF}/mocks/ivo-1dc2693b-e/{mark}/ghost", timeout=30)
        assert rm.status_code == 404, f"plain miss must be 404, got {rm.status_code}"

        time.sleep(8)  # two scheduled hit-flush windows (5s each)

        # 3) the browser reads both rows through the real bridge ( AN-4:
        # the traffic screen is the workbench inspector tab of the space)
        page.goto(f"{BASE_URL}/#/mock/spaces/{space_id}?projectId={project_id}&tab=inspector",
                  wait_until="domcontentloaded")
        expect(page.locator('[data-test="mock-inspector-page"]')).to_be_visible(timeout=15000)
        llm_row = page.locator('[data-test="mock-inspector-hits"] tr',
                               has_text=f"{mark}/orders/777")
        expect(llm_row.first).to_be_visible(timeout=20000)
        tag = llm_row.locator('[data-test="mock-inspector-source-tag"]')
        expect(tag.first).to_be_visible(timeout=10000)
        expect(tag.first).to_contain_text("AI 兜底应答")

        # 4) the plain miss row carries NO provenance tag (honest absence)
        miss_row = page.locator('[data-test="mock-inspector-hits"] tr',
                                has_text=f"{mark}/ghost")
        expect(miss_row.first).to_be_visible(timeout=20000)
        assert miss_row.locator('[data-test="mock-inspector-source-tag"]').count() == 0, \
            "a pre-provenance miss row must never wear a fabricated label"

        # 5) reload keeps the tag — the value is read back from the ledger,
        # not frontend state
        page.goto(f"{BASE_URL}/#/mock/spaces/{space_id}?projectId={project_id}&tab=inspector",
                  wait_until="domcontentloaded")
        llm_row2 = page.locator('[data-test="mock-inspector-hits"] tr',
                                has_text=f"{mark}/orders/777")
        expect(llm_row2.locator('[data-test="mock-inspector-source-tag"]').first) \
            .to_be_visible(timeout=20000)

        assert_no_errors(collectors, "T366E3C 检视器应答来源紫标 正谱/负谱/reload")
    finally:
        if orig_master is not None:
            set_gate(MASTER, orig_master)
        if orig_sub is not None:
            set_gate(SUB, orig_sub)
        sql_query(f"DELETE FROM tp_mock_hit_log WHERE space_id={space_id} "
                  f"AND request_snapshot::text LIKE '%{mark}%'")
        residue = sql_query(f"SELECT count(*) FROM tp_mock_hit_log "
                            f"WHERE request_snapshot::text LIKE '%{mark}%'")
        assert residue == "0", f"fixture residue left: {residue}"
