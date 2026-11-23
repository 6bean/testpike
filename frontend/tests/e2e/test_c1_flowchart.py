
import json

import httpx
import pytest
from playwright.sync_api import expect

from conftest import api_client
from conftest import assert_no_errors, logged_in, sql_query, uniq  # noqa: F401

pytestmark = pytest.mark.timeout(180)

CORE = "http://localhost:8083"


def _mk_project(api, uid):
    r = api.post("/projects", json={"name": f"C1项目_{uid}", "code": f"C1{uid[:6].upper()}"})
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建项目失败: {r.text[:300]}"
    return r.json()["data"]


def _mk_case(api, pid, uid, title="登录场景验证"):
    r = api.post("/cases", json={
        "projectId": pid,
        "title": f"{title}_{uid}",
        "precondition": f"前置：浏览器已打开（{uid}）",
        "expectedResult": f"预期：流程可达（{uid}）",
        "priority": 1,
        "confidence": 1,
        "endType": "web",
        "status": 1,
        "steps": [{"stepNo": 1, "description": "打开页面 /login", "isAssert": 0}],
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建用例失败: {r.text[:300]}"
    return r.json()["data"]["id"]


def _action_json(case_id, title):
    pass
    return json.dumps({
        "version": "v2", "caseId": case_id, "title": title, "endType": "web",
        "steps": [
            {"stepId": 1, "action": "goto", "target": {"css": "#login"}},
            {"stepId": 2, "action": "if", "condition": "用户已登录",
             "steps": [{"stepId": 3, "action": "input", "target": {"text": "用户名"},
                        "value": "tester"}],
             "elseSteps": [{"stepId": 4, "action": "click", "target": {"css": "#login-btn"}}]},
            {"stepId": 5, "action": "loop", "count": 3,
             "steps": [{"stepId": 6, "action": "expect", "target": {"css": ".row"}}]},
        ],
        "env": {"ENV": "dev", "PACK": "retail-pack-v1", "DATA": "pool-a"},
    }, ensure_ascii=False)


def _save_script(case_id, title):
    pass
    r = httpx.post(f"{CORE}/internal/scripts", timeout=30,
                   json={"caseId": case_id, "schemaVersion": "v2",
                         "actionJson": _action_json(case_id, title), "generatedBy": 1})
    assert r.status_code == 200, f"save 脚本失败: {r.status_code} {r.text[:200]}"
    assert r.json().get("code") == "0", f"save 脚本业务失败: {r.text[:200]}"


def _open_detail(page, case_id):
    page.goto(f"{page.url.split('#')[0]}#/cases/{case_id}", wait_until="domcontentloaded")


def _purge(api, pid, case_ids):
    pass
    if pid:
        api.delete(f"/projects/{pid}/purge?force=true")


def test_c1_flowchart_if_loop_render(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid = case_id = None
    try:
        pid = _mk_project(api_client, uniq)
        case_id = _mk_case(api_client, pid, uniq)
        _save_script(case_id, f"登录场景_{uniq}")

        _open_detail(page, case_id)
        expect(page.locator('[data-test="detail-title"]')).to_contain_text(f"登录场景验证_{uniq}")

        block = page.locator('[data-test="flowchart-block"]')
        expect(block).to_be_visible()

        svg = page.locator('[data-test="flowchart-svg"]')
        expect(svg).to_contain_text("判定（用户已登录）", timeout=15000)
        svg_text = svg.inner_text()
        for frag in ("成立", "不成立", "汇聚", "循环（count=3）", "重复", "循环结束", "完成"):
            assert frag in svg_text, f"SVG 缺「{frag}」节点/边标签: {svg_text[:400]}"
        for verb in ("导航", "输入", "点击", "断言"):
            assert verb in svg_text, f"SVG 缺中文动词「{verb}」（回退了动作码？）"
        assert "#login" in svg_text, "locator 摘要缺失（FlowchartLabels LOCATOR_KEYS 优先序）"

        expect(page.locator('[data-test="flowchart-step-count"]')).to_contain_text("6")

        assert page.locator('[data-test="flowchart-source"]').count() == 0, "SVG 成功却渲染回退原文"
        assert page.locator('[data-test="flowchart-error"]').count() == 0, "流程图加载报错"

        _open_detail(page, case_id)
        expect(page.locator('[data-test="flowchart-svg"]')).to_contain_text("判定（用户已登录）", timeout=15000)
        expect(page.locator('[data-test="flowchart-step-count"]')).to_contain_text("6")

        assert_no_errors(collectors, "C1 流程图 if+loop 渲染")
    finally:
        _purge(api_client, pid, [case_id] if case_id else [])


def test_c1_flowchart_no_script_placeholder(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid = case_id = None
    try:
        pid = _mk_project(api_client, uniq)
        case_id = _mk_case(api_client, pid, uniq, title="无脚本用例")

        _open_detail(page, case_id)
        expect(page.locator('[data-test="detail-title"]')).to_contain_text(f"无脚本用例_{uniq}")
        block = page.locator('[data-test="flowchart-block"]')
        expect(block).to_be_visible()
        expect(page.locator('[data-test="flowchart-svg"]')).to_contain_text("暂无操作步骤", timeout=15000)
        expect(page.locator('[data-test="flowchart-step-count"]')).to_contain_text("0")
        assert_no_errors(collectors, "C1 无脚本占位图")
    finally:
        _purge(api_client, pid, [case_id] if case_id else [])


def _save_script_b11(case_id, title):
    pass
    body = {
        "version": "v2", "caseId": case_id, "title": title, "endType": "web",
        "steps": [
            {"stepId": 1, "action": "goto", "target": {"css": "#login"}},
            {"stepId": 2, "action": "net.assert",
             "match": {"urlPattern": "http://localhost:8899/api/order"},
             "expect": {"status": 200}},
            {"stepId": 3, "action": "console.assert", "mode": "collect",
             "level": ["error"]},
            {"stepId": 4, "action": "sql.assert", "conn": "demo-db",
             "sql": "SELECT count(*) FROM t_order",
             "expect": {"type": "row_count", "matchType": "exact", "value": 1}},
        ],
    }
    r = httpx.post(f"{CORE}/internal/scripts", timeout=30,
                   json={"caseId": case_id, "schemaVersion": "v2",
                         "actionJson": json.dumps(body, ensure_ascii=False),
                         "generatedBy": 1})
    assert r.status_code == 200, f"save B11 脚本失败: {r.status_code} {r.text[:200]}"
    assert r.json().get("code") == "0", f"save B11 脚本业务失败: {r.text[:200]}"


def test_c1_flowchart_b11_nonui_labels_dashed(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid = case_id = None
    try:
        pid = _mk_project(api_client, uniq)
        case_id = _mk_case(api_client, pid, uniq, title="跨系统下单校验")
        _save_script_b11(case_id, f"跨系统脚本_{uniq}")

        _open_detail(page, case_id)
        svg = page.locator('[data-test="flowchart-svg"]')
        expect(svg).to_contain_text("网络观测", timeout=15000)
        svg_text = svg.inner_text()
        for verb in ("导航", "网络观测", "控制台断言", "数据库校验"):
            assert verb in svg_text, f"SVG 缺中文动词「{verb}」（回退了动作码？）: {svg_text[:400]}"
        for code in ("net.assert", "console.assert", "sql.assert"):
            assert code not in svg_text, f"SVG 回退动作码字面 {code}（FlowchartLabels 表未生效？）"
        svg_html = svg.inner_html()
        assert "nonui" in svg_html, "非 UI 节点未挂虚线类（classDef/:::nonui 缺位）"
        assert "导航" in svg_text
        dashed_nodes = page.locator('[data-test="flowchart-svg"] g.node.nonui')
        assert dashed_nodes.count() == 3, f"虚线节点数应=3（net/console/sql），实测={dashed_nodes.count()}"
        expect(page.locator('[data-test="flowchart-step-count"]')).to_contain_text("4")

        _open_detail(page, case_id)
        expect(page.locator('[data-test="flowchart-svg"]')).to_contain_text("数据库校验", timeout=15000)

        assert_no_errors(collectors, "C1 流程图 B11 标签+虚线")
    finally:
        _purge(api_client, pid, [case_id] if case_id else [])


def test_c1_flowchart_purge_zero_residue(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid = case_id = None
    try:
        pid = _mk_project(api_client, uniq)
        case_id = _mk_case(api_client, pid, uniq, title="残留守卫用例")
        _save_script(case_id, f"守卫脚本_{uniq}")
        assert sql_query(
            f"SELECT count(*) FROM tp_script_version WHERE case_id = {case_id}") == "1", "快照行未写入"
    finally:
        _purge(api_client, pid, [case_id] if case_id else [])
    assert sql_query(
        f"SELECT count(*) FROM tp_script_version WHERE case_id = {case_id}") == "0", "版本快照行残留"
    assert sql_query(
        f"SELECT count(*) FROM tp_script WHERE case_id = {case_id}") == "0", "脚本行残留"
    assert sql_query(
        f"SELECT count(*) FROM tp_project WHERE id = {pid}") == "0", "项目残留"
    assert_no_errors(collectors, "C1 零残留")
