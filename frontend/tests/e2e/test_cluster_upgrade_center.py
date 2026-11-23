
import subprocess
import re
import uuid
from pathlib import Path

import httpx
import pytest
from conftest import ADMIN_PASSWORD, BASE_URL, api_login, assert_no_errors, open_journey_leaf
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(300)

API = BASE_URL.replace(":5173", ":8081")


def sql(q):
    r = subprocess.run(
        ["docker", "exec", "testpike-postgres", "psql", "-U", "testpike", "-d", "testpike", "-tAc", q],
        check=True, capture_output=True, text=True)
    return r.stdout.strip()


def inject_token(page):
    pass
    token = api_login("admin", ADMIN_PASSWORD)
    page.goto(f"{BASE_URL}/#/login", wait_until="domcontentloaded")
    page.evaluate(f"() => {{ localStorage.setItem('tp_access_token', '{token}'); localStorage.setItem('tp_onboarding_dismissed', '1') }}")
    page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
    page.reload(wait_until="domcontentloaded")
    expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
    return page, token


def test_cluster_upgrade_center(page, collectors):
    pass
    page, token = inject_token(page)
    h = {"Authorization": f"Bearer {token}"}
    api = httpx.Client(base_url=API, timeout=30, headers=h)
    version = f"9.{uuid.uuid4().hex[:2]}.0-test"

    try:
        open_journey_leaf(page, "menu-upgrade-center", "system")
        expect(page.locator('[data-test="upgrade-center-page"]')).to_be_visible(timeout=10000)

        count0 = sql("SELECT count(*) FROM tp_release_manifest")
        if count0 == "0":
            expect(page.locator('[data-test="upgrade-empty-state"]')).to_be_visible(timeout=8000)
            expect(page.locator('[data-test="upgrade-empty-state"]')).to_contain_text("尚未导入发布清单")

        body = {
            "version": version,
            "componentMatrixJson": '[{"component":"executor","support":"0.0.0 ~ 9.9.9","pinned":"1.0.0"}]',
            # supply-chain gate: a pinned family must ship its artifact.
            "artifactsJson": ('[{"component_family":"executor",'
                              '"url":"https://github.com/testpike/releases/executor-1.0.0.zip",'
                              '"sha256":"' + "a" * 64 + '","size":1024}]'),
            "breakingChangesJson": '["升级前需手动执行数据库迁移脚本"]',
            "executorVersionMin": "0.0.0",
            "executorVersionMax": "9.9.9",
            "suggestedPath": "先从 1.x 升到 2.x 再升本版本",
            "source": "manual",
        }
        r = api.post("/api/v1/cluster/release-manifests", json=body)
        assert r.status_code == 200, f"登记 HTTP {r.status_code}"
        assert r.json()["code"] == "0", f"登记业务码 {r.json()}"
        assert sql(f"SELECT count(*) FROM tp_release_manifest WHERE version='{version}'") == "1"

        page.reload(wait_until="domcontentloaded")
        banner = page.locator('[data-test="upgrade-version-banner"]')
        expect(banner).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="upgrade-banner-version"]')).to_have_text(version)
        expect(page.locator('[data-test="upgrade-banner-range"]')).to_contain_text("0.0.0")

        self_banner = page.locator('[data-test="upgrade-self-version-banner"]')
        expect(self_banner).to_be_visible(timeout=8000)
        expect(page.locator('[data-test="upgrade-self-verdict"]')).to_contain_text("落后")
        expect(page.locator('[data-test="upgrade-self-latest"]')).to_contain_text(version)
        import json as _json
        _fe_ver = _json.loads((Path(__file__).resolve().parents[2] / "package.json").read_text(
            encoding="utf-8"))["version"]
        expect(page.locator('[data-test="upgrade-self-frontend-version"]')).to_contain_text(_fe_ver)

        page.locator('[data-test="upgrade-tabs"]').get_by_role("tab", name="版本全景").click()
        expect(page.locator('[data-test="upgrade-panorama-table"]')).to_be_visible(timeout=8000)
        # Fixture fix : the panorama table may carry several engine
        # rows (dev DB accumulates runtime rows across batches) — pin the anchor to
        # one row instead of tripping the strict-mode assertion.
        node_link = page.locator('[data-test="upgrade-homepage-engine-runtime.node"]').first
        if page.locator('[data-test="upgrade-homepage-engine-runtime.node"]').count() > 0:
            expect(node_link).to_have_attribute("href", "https://nodejs.org")
            expect(node_link).to_have_attribute("target", "_blank")
            expect(node_link).to_have_attribute("rel", re.compile("noopener"))
            with page.expect_popup() as popup_info:
                node_link.click()
            popup = popup_info.value
            popup.wait_for_url("https://nodejs.org/**", timeout=15000)
            popup.close()
        agent_dash = page.locator('[data-test="upgrade-homepage-executor-agent-empty"]').first
        if page.locator('[data-test="upgrade-homepage-executor-agent"]').count() == 0:
            expect(agent_dash).to_be_visible(timeout=5000)
        # back to the manifest tab before the health-card steps (pane switch keeps
        # inactive panes hidden — same restore posture as test_cluster_release_candidate)
        page.get_by_role("tab", name="版本清单").click()

        page.locator('[data-test="upgrade-changelog-btn"]').click()
        drawer = page.locator('[data-test="upgrade-changelog-drawer"]')
        expect(drawer).to_be_visible(timeout=8000)
        item = page.locator(f'[data-test="upgrade-release-{version}"]')
        expect(item).to_contain_text("升级前需手动执行数据库迁移脚本")
        expect(item.locator('[data-test="upgrade-matrix-table"]')).to_be_visible()
        page.keyboard.press("Escape")
        expect(drawer).to_be_hidden(timeout=8000)

        page.locator('[data-test="upgrade-guide-btn"]').click()
        guide = page.locator('[data-test="upgrade-guide-drawer"]')
        expect(guide).to_be_visible(timeout=8000)
        expect(page.locator('[data-test="upgrade-guide-path"]')).to_contain_text("先从 1.x 升到 2.x")
        page.locator('[data-test="upgrade-guide-export"]').click()
        expect(page.locator('.el-message--success').first).to_contain_text("已导出", timeout=8000)
        page.keyboard.press("Escape")
        expect(guide).to_be_hidden(timeout=8000)

        page.locator('[data-test="upgrade-health-btn"]').click()
        panel = page.locator('[data-test="upgrade-health-panel"]')
        expect(panel).to_be_visible(timeout=8000)
        exec_rows = int(sql("SELECT count(*) FROM tp_executor"))
        if exec_rows > 0:
            expect(page.locator('[data-test^="upgrade-compat-tag-"]').first).to_be_visible(timeout=8000)
            expect(page.locator('[data-test="upgrade-health-panel"] .compat-row-danger')).to_have_count(0, timeout=8000)
            for txt in page.locator('[data-test^="upgrade-compat-tag-"]').all_inner_texts():
                assert txt == "兼容", f"宽区间下出现不兼容判定行: {txt}"
        else:
            expect(page.locator('[data-test="upgrade-health-table"]')).to_be_visible()
        assert_no_errors(collectors, "升级中心页")
    finally:
        sql(f"DELETE FROM tp_release_manifest WHERE version='{version}'")
        api.close()
