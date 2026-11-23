
import json
import os

import httpx
from playwright.sync_api import expect

# Chain-slot aware: slot env exports CORE_BASE_URL (scripts/chain_slot.py),
# the main slot falls back to the shipped default (same key as conftest).
CORE = os.environ.get("CORE_BASE_URL", "http://localhost:8083")


def mk_project(api, uid, tag="V4"):
    r = api.post("/projects", json={"name": f"{tag}项目_{uid}", "code": f"{tag}{uid[:6].upper()}"})
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建项目失败: {r.text[:300]}"
    return r.json()["data"]


def mk_case(api, pid, uid, tag="V4"):
    pass
    r = api.post("/cases", json={
        "projectId": pid,
        "title": f"{tag}场景_{uid}",
        "precondition": f"前置：浏览器已打开（{uid}）",
        "expectedResult": f"预期：脚本可手编（{uid}）",
        "priority": 1,
        "confidence": 1,
        "endType": "web",
        "status": 1,
        "steps": [
            {"stepNo": 1, "description": "打开登录页", "isAssert": 0},
            {"stepNo": 2, "description": "输入账号密码", "isAssert": 0},
            {"stepNo": 3, "description": "点击登录按钮", "isAssert": 1},
        ],
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建用例失败: {r.text[:300]}"
    return r.json()["data"]["id"]


def base_doc(case_id, uid):
    pass
    return {
        "version": "v2", "caseId": case_id, "title": f"V4场景_{uid}", "endType": "web",
        "steps": [
            {"stepId": 1, "action": "goto", "url": "http://localhost:8899/login"},
            {"stepId": 2, "action": "input", "target": {"text": "用户名", "chain": ["text"]},
             "value": "tester"},
            {"stepId": 3, "action": "expect", "target": {"css": ".welcome", "chain": ["css"]}},
        ],
        "env": {"ENV": "dev"},
    }


def tag_safe(uid):
    return "V4场景"


def save_doc(case_id, doc):
    pass
    r = httpx.post(f"{CORE}/internal/scripts", timeout=30,
                   json={"caseId": case_id, "schemaVersion": "v2",
                         "actionJson": json.dumps(doc, ensure_ascii=False), "generatedBy": 1})
    assert r.status_code == 200, f"save 脚本失败: {r.status_code} {r.text[:200]}"
    assert r.json().get("code") == "0", f"save 脚本业务失败: {r.text[:200]}"


def open_detail(page, case_id):
    page.goto(f"{page.url.split('#')[0]}#/cases/{case_id}", wait_until="domcontentloaded")


def open_tree_dialog(page):
    page.locator('[data-test="detail-script-tree-edit"]').click()
    dialog = page.locator('[data-test="step-tree-dialog"]')
    expect(dialog).to_be_visible(timeout=10000)
    return dialog


def read_editor_tier_facts(page):
    """Measured geometry of the open step-editor dialog (single source).

    Both the acceptance spectrum and the vision capture window assert the same
    shipped tier spec (theme tokens: xl = min(1440px, 92vw), cap 85vh, dialog
    body owning vertical scroll), so the readout lives here once — a second
    copy would let the two facets drift apart while both stayed green.
    """
    return page.evaluate(
        """() => {
            const dlg = document.querySelector('[data-test="step-tree-dialog"]');
            const body = dlg.querySelector('.el-dialog__body');
            return {
                hasTier: [...dlg.classList].includes('tp-dialog--xl'),
                width: dlg.offsetWidth,
                expectedWidth: Math.min(1440, Math.floor(window.innerWidth * 0.92)),
                height: dlg.offsetHeight,
                cap: Math.floor(window.innerHeight * 0.85) + 2,
                overflowY: getComputedStyle(body).overflowY,
            };
        }"""
    )
