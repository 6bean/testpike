
import hashlib
import os
import subprocess
import time
import uuid

import httpx
import pytest
from conftest import (  # noqa: F401
    ADMIN_PASSWORD,
    API_ORIGIN,
    BASE_URL,
    api_login,
    assert_no_errors,
    open_journey_leaf,
    s3_bucket,
    s3_client,
    sql_query,
)
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(300)

API = API_ORIGIN
BUCKET = s3_bucket()


def sql(q):
    pass
    return sql_query(q)


def _s3_head(object_key):
    pass
    import botocore.exceptions
    try:
        s3_client().head_object(Bucket=BUCKET, Key=object_key)
        return True
    except botocore.exceptions.ClientError as e:
        if e.response["Error"]["Code"] in ("404", "NoSuchKey"):
            return False
        raise


def object_exists(object_key):
    return _s3_head(object_key)


def wait_object_absent(object_key, timeout_s=10):
    pass
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        if not _s3_head(object_key):
            return True
        time.sleep(0.5)
    return not _s3_head(object_key)


def inject_token(page):
    pass
    for attempt in (1, 2):
        token = api_login("admin", ADMIN_PASSWORD)
        page.goto(f"{BASE_URL}/#/login", wait_until="domcontentloaded")
        page.evaluate(f"() => {{ localStorage.setItem('tp_access_token', '{token}'); localStorage.setItem('tp_onboarding_dismissed', '1') }}")
        page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
        try:
            expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
            return page, token
        except AssertionError:
            if attempt == 2:
                raise AssertionError("inject_token 注入后未进入工作台")
    raise AssertionError("unreachable")


def _pick_project(page, pid):
    """: page-level selector is gone — the ?projectId deep link lands the segment.
 Bounce via the dashboard first: the router reuses the component across
 query-only navigation, so a same-route goto never re-runs the setup that
 consumes the deep link (and reload-persistence needs a genuine remount)."""
    page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
    expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
    page.goto(f"{BASE_URL}/#/appauto/packages?projectId={pid}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="app-package-page"]')).to_be_visible(timeout=15000)


def _rows(page, text):
    return page.locator('[data-test="package-table"] tbody tr').filter(has_text=text)


def _row(page, text):
    return _rows(page, text).first


def _open_package_page(page):
    pass
    open_journey_leaf(page, "menu-app-package", "appauto")
    expect(page.locator('[data-test="app-package-page"]')).to_be_visible(timeout=10000)
    expect(page.locator('[data-test="package-usage-alert"]')).to_be_visible()


def test_appauto_app_package_page(page, collectors):
    pass
    page, token = inject_token(page)
    h = {"Authorization": f"Bearer {token}"}
    suffix = uuid.uuid4().hex[:6]
    proj_name = f"档案E2E项目{suffix}"
    pkg = f"com.demo.e2e{suffix}"
    app_name = f"商城App 主干包{suffix}"
    renamed = f"{app_name}改"
    bytes1 = b"PK\x03\x04e2e-app-package-" + suffix.encode() + b"-a" * 3072
    bytes2 = b"PK\x03\x04e2e-app-package-" + suffix.encode() + b"-b" * 4096
    sha1 = hashlib.sha256(bytes1).hexdigest()
    sha2 = hashlib.sha256(bytes2).hexdigest()
    tmp1 = f"/tmp/e2e-app-pkg-{suffix}-1.apk"
    tmp2 = f"/tmp/e2e-app-pkg-{suffix}-2.apk"
    api = httpx.Client(base_url=API, timeout=30, headers=h)
    pid = None
    app_id = None
    try:
        pid = api.post("/api/v1/projects",
                       json={"name": proj_name, "code": f"APE{suffix}"}).json()["data"]

        _open_package_page(page)

        create_btn = page.locator('[data-test="package-create-btn"]')
        expect(create_btn).to_be_disabled(timeout=5000)
        _pick_project(page, pid)
        expect(create_btn).to_be_enabled(timeout=8000)
        create_btn.click()
        dlg = page.locator('[data-test="package-create-dialog"]')
        expect(dlg).to_be_visible()
        page.locator('[data-test="package-create-save"]').click()
        expect(dlg).to_be_visible(timeout=3000)
        page.locator('[data-test="package-form-name"]').fill(app_name)
        page.locator('[data-test="package-form-package-name"]').fill(pkg)
        page.locator('[data-test="package-form-version"]').fill("1.0.0")
        page.locator('[data-test="package-create-save"]').click()
        expect(dlg).to_be_hidden(timeout=8000)
        row = sql(f"SELECT id FROM tp_appauto_app WHERE project_id={pid} AND package_name='{pkg}'")
        assert row.isdigit(), f"档案行未落库: {row}"
        app_id = int(row)

        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="app-package-page"]')).to_be_visible(timeout=15000)
        _pick_project(page, pid)
        expect(_rows(page, app_name)).to_have_count(1, timeout=10000)
        expect(_row(page, app_name).locator('[data-test="package-artifact-missing"]')).to_be_visible()

        page.locator('[data-test="package-create-btn"]').click()
        dlg2 = page.locator('[data-test="package-create-dialog"]')
        expect(dlg2).to_be_visible()
        page.locator('[data-test="package-form-name"]').fill(renamed)
        page.locator('[data-test="package-form-package-name"]').fill(pkg)
        page.locator('[data-test="package-form-version"]').fill("1.0.0")
        page.locator('[data-test="package-create-save"]').click()
        expect(dlg2).to_be_hidden(timeout=8000)
        assert sql(f"SELECT count(*) FROM tp_appauto_app WHERE project_id={pid}") == "1", \
            "同包同版本重复建档新增了行（覆盖留痕语义失效）"
        assert int(sql(f"SELECT id FROM tp_appauto_app WHERE project_id={pid}")) == app_id
        assert sql(f"SELECT name FROM tp_appauto_app WHERE id={app_id}") == renamed

        with open(tmp1, "wb") as f:
            f.write(bytes1)
        _row(page, renamed).locator('[data-test="package-upload-btn"]').click()
        updlg = page.locator('[data-test="package-upload-dialog"]')
        expect(updlg).to_be_visible()
        page.locator('[data-test="package-file-input"]').set_input_files(tmp1)
        expect(page.locator('[data-test="package-file-name"]')).to_contain_text(
            f"e2e-app-pkg-{suffix}-1.apk", timeout=5000)
        page.locator('[data-test="package-upload-start"]').click()
        expect(updlg).to_be_hidden(timeout=30000)
        db = sql(f"SELECT apk_artifact_key, artifact_sha256, artifact_size_bytes "
                 f"FROM tp_appauto_app WHERE id={app_id}").split("|")
        key1 = db[0]
        assert key1, "上传后 apk_artifact_key 未复位"
        assert db[1] == sha1, f"DB 摘要与本地实算不符: {db[1][:16]}… want {sha1[:16]}…"
        assert db[2] == str(len(bytes1)), f"DB 体积不符: {db[2]} want {len(bytes1)}"
        assert object_exists(key1), f"对象存储未见归档对象: {key1}"
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="app-package-page"]')).to_be_visible(timeout=15000)
        _pick_project(page, pid)
        row_loc = _row(page, renamed)
        expect(row_loc.locator('[data-test="package-artifact-uploaded"]')).to_be_visible(timeout=10000)
        expect(row_loc.locator('[data-test="package-artifact-sha"]')).to_have_text(sha1[:12], timeout=8000)
        expect(row_loc.locator('[data-test="package-artifact-size"]')).to_contain_text("KB")

        with open(tmp2, "wb") as f:
            f.write(bytes2)
        _row(page, renamed).locator('[data-test="package-upload-btn"]').click()
        updlg2 = page.locator('[data-test="package-upload-dialog"]')
        expect(updlg2).to_be_visible()
        page.locator('[data-test="package-file-input"]').set_input_files(tmp2)
        page.locator('[data-test="package-upload-start"]').click()
        expect(updlg2).to_be_hidden(timeout=30000)
        db2 = sql(f"SELECT apk_artifact_key, artifact_sha256 FROM tp_appauto_app WHERE id={app_id}").split("|")
        key2 = db2[0]
        assert key2 != key1, "重传未产生新对象 key（覆盖语义异常）"
        assert db2[1] == sha2, f"重传摘要未复位: {db2[1][:16]}… want {sha2[:16]}…"
        assert object_exists(key2), f"新对象未落盘: {key2}"
        assert wait_object_absent(key1), f"旧对象未即清（孤儿水位残留）: {key1}"

        _row(page, renamed).locator('[data-test="package-delete-btn"]').click()
        confirm = page.locator('.el-message-box')
        expect(confirm).to_be_visible(timeout=5000)
        confirm.locator('button', has_text="删除").click()
        expect(_rows(page, renamed)).to_have_count(0, timeout=8000)
        assert sql(f"SELECT count(*) FROM tp_appauto_app WHERE id={app_id}") == "0"
        assert wait_object_absent(key2), f"删除档案后安装包对象未清理: {key2}"
        app_id = None

        assert_no_errors(collectors, "appauto app package page")
    finally:
        for p in (tmp1, tmp2):
            try:
                os.remove(p)
            except OSError:
                pass
        if pid:
            if app_id:
                api.delete(f"/api/v1/appauto/packages/{app_id}")
            r = api.delete(f"/api/v1/projects/{pid}/purge", params={"force": "true"})
            assert r.status_code == 200, f"项目清理失败: {r.status_code} {r.text[:200]}"
            assert sql(f"SELECT count(*) FROM tp_appauto_app WHERE project_id={pid}") == "0"
        api.close()
