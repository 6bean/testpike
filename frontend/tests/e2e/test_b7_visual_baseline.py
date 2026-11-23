
import base64
import httpx
import pytest
from conftest import (  # noqa: F401
    API_BASE,
    TESTER_PASSWORD,
    s3_bucket,
    s3_client,
    add_member,
    api_client,
    api_login,
    assert_no_errors,
    inject_user_token,
    logged_in,
    sql_query,
    uniq,
)
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(240)

_PNG_B64 = ("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42m"
            "Nk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==")
def _bucket() -> str:
    return s3_bucket()


def _s3():
    pass
    return s3_client()


def _mk_project(api, uid):
    r = api.post("/projects", json={"name": f"B7基线项目_{uid}", "code": f"B7{uid[:6].upper()}"})
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建项目失败: {r.text[:300]}"
    return r.json()["data"]


def _mk_case(api, pid, uid):
    r = api.post("/cases", json={
        "projectId": pid,
        "title": f"登录页视觉验证_{uid}",
        "precondition": f"前置：已打开登录页（{uid}）",
        "expectedResult": f"预期：登录按钮渲染一致（{uid}）",
        "priority": 0,
        "confidence": 1,
        "endType": "web",
        "status": 1,
        "steps": [{"stepNo": 1, "description": "打开页面 /login", "isAssert": 0}],
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建用例失败: {r.text[:300]}"
    return r.json()["data"]["id"]


def _put_object(object_key: str) -> None:
    pass
    _s3().put_object(Bucket=_bucket(), Key=object_key,
                   Body=base64.b64decode(_PNG_B64), ContentType="image/png")


def _seed_baseline(pid, step_key, status, source_key, baseline_key):
    pass
    q = ("INSERT INTO tp_visual_baseline (project_id, step_key, source_artifact_key,"
         " baseline_key, status, diff_tolerance, confirmed_at)"
         f" VALUES ({pid}, '{step_key}', "
         f"{'NULL' if source_key is None else repr(source_key)},"
         f" {'NULL' if baseline_key is None else repr(baseline_key)},"
         f" {status}, 0.02,"
         f" {'now()' if status in (2, 3) else 'NULL'}) RETURNING id")
    out = sql_query(q)
    first = out.strip().splitlines()[0].strip() if out.strip() else ""
    assert first.isdigit(), f"造种失败: {out[:200]}"
    return int(first)


def _row(page, step_key_tail):
    return page.locator('[data-test="baseline-table"] tr', has_text=step_key_tail)


def _mk_dev(api, uid):
    pass
    username = f"b7dev_{uid}"
    r = api.post("/users", json={
        "username": username, "password": TESTER_PASSWORD,
        "displayName": f"b7开发{uid[:6]}", "email": f"{username}@test.local",
        "phone": "", "role": 3,
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建开发人员失败: {r.text[:300]}"
    return r.json()["data"], username


def _cleanup(api, pid, obj_prefix):
    pass
    try:
        resp = _s3().list_objects_v2(Bucket=_bucket(), Prefix=obj_prefix or "")
        for obj in resp.get("Contents", []):
            _s3().delete_object(Bucket=_bucket(), Key=obj["Key"])
    except Exception as exc:  # noqa: BLE001
        print(f"B7 对象清理告警: {exc}")
    try:
        sql_query(f"DELETE FROM tp_visual_baseline WHERE project_id = {pid}")
        api.delete(f"/projects/{pid}/purge?force=true")
    except Exception as exc:  # noqa: BLE001
        print(f"B7 造种清理告警: {exc}")


def _open_detail(page, case_id):
    base = page.url.split("#")[0]
    if f"#/cases/{case_id}" in page.url:
        page.reload(wait_until="domcontentloaded")
    else:
        page.goto(f"{base}#/cases/{case_id}", wait_until="domcontentloaded")


def test_b7_visual_baseline_card(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid = None
    obj_prefix = None
    try:
        pid = _mk_project(api_client, uniq)
        case_id = _mk_case(api_client, pid, uniq)
        sk1 = f"case:{case_id}:step:1:shot"
        sk2 = f"case:{case_id}:step:2:shot"
        obj_prefix = f"testpike/{pid}"
        src1 = f"{obj_prefix}/exec-artifact/209912/e2e{uniq[:6]}-s1.png"
        src2 = f"{obj_prefix}/exec-artifact/209912/e2e{uniq[:6]}-s2.png"
        base_old = f"{obj_prefix}/visual-baseline/209912/e2e{uniq[:6]}-old.png"
        _put_object(src1)
        _put_object(src2)
        _put_object(base_old)
        pending1 = _seed_baseline(pid, sk1, 1, src1, None)
        confirmed_old = _seed_baseline(pid, sk1, 2, None, base_old)
        pending2 = _seed_baseline(pid, sk2, 1, src2, None)

        _open_detail(page, case_id)
        expect(page.locator('[data-test="baseline-block"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="baseline-status"]')).to_have_count(3)
        block_text = page.locator('[data-test="baseline-block"]').inner_text()
        assert "待确认" in block_text and "当前基线" in block_text, f"状态标签缺失: {block_text[:400]}"
        assert sk1 in block_text and sk2 in block_text, "步骤键未展示"
        expect(page.locator('[data-test="baseline-pending-count"]')).to_contain_text("2")

        img = page.locator('[data-test^="baseline-shot-"] img').first
        expect(img).to_be_visible(timeout=15000)
        src = img.get_attribute("src") or ""
        assert "X-Amz-Signature" in src, f"查看链非预签名: {src[:120]}"
        page.wait_for_function(
            "(sel) => { const i = document.querySelector(sel);"
            " return i && i.complete && i.naturalWidth > 0; }",
            arg='[data-test^="baseline-shot-"] img', timeout=15000)

        _row(page, "step:1:").locator('[data-test="baseline-confirm-btn"]').first.click()
        expect(page.locator(".el-message-box")).to_be_visible(timeout=5000)
        page.locator(".el-message-box").get_by_role("button", name="确定").click()
        expect(page.locator(".el-message--success").first).to_be_visible(timeout=8000)
        _open_detail(page, case_id)
        expect(_row(page, "step:1:").filter(has_text="当前基线")).to_have_count(1)
        expect(_row(page, "step:1:").filter(has_text="已被取代")).to_have_count(1)
        pair = sql_query(
            "SELECT status || ',' || COALESCE(confirmed_by, -1) || ',' ||"
            " COALESCE(baseline_key, 'none') FROM tp_visual_baseline"
            f" WHERE id IN ({pending1}, {confirmed_old}) ORDER BY id")
        lines = pair.splitlines()
        assert len(lines) == 2, f"DB 对账行数异常: {pair}"
        new_status, new_confirmer, new_key = lines[0].split(",")
        assert new_status == "2", f"pending 行未翻转为当前基线: {lines[0]}"
        assert new_confirmer != "-1", f"confirmed_by 未落: {lines[0]}"
        assert new_key.startswith("testpike/"), f"基线对象键未按项目路由生成: {new_key}"
        assert lines[1].split(",")[0] == "3", f"旧基线未让位: {lines[1]}"
        vr = api_client.get(f"/visual-baselines/{pending1}/view-url")
        assert vr.status_code == 200 and vr.json().get("code") == "0", f"view-url 失败: {vr.text[:200]}"
        import httpx
        got = httpx.get(vr.json()["data"], timeout=20, follow_redirects=True)
        assert got.status_code == 200 and got.content[:4] == b"\x89PNG", \
            f"新基线字节不可取: {got.status_code}"

        _row(page, "step:2:").locator('[data-test="baseline-reject-btn"]').first.click()
        expect(page.locator(".el-message-box")).to_be_visible(timeout=5000)
        page.locator(".el-message-box").get_by_role("button", name="确定").click()
        expect(page.locator(".el-message--success").first).to_be_visible(timeout=8000)
        _open_detail(page, case_id)
        expect(page.locator('[data-test="baseline-status"]')).to_have_count(2)
        assert "待确认" not in page.locator('[data-test="baseline-block"]').inner_text()
        assert sql_query(f"SELECT count(*) FROM tp_visual_baseline WHERE project_id = {pid}"
                         " AND status = 1") == "0", "拒绝后仍有 pending 行"

        assert_no_errors(collectors, "B7 视觉基线卡")
    finally:
        if pid is not None:
            _cleanup(api_client, pid, obj_prefix)


def test_b7_visual_baseline_permission_negative(page, collectors, api_client, uniq):
    pass
    pid = None
    obj_prefix = None
    try:
        pid = _mk_project(api_client, uniq)
        case_id = _mk_case(api_client, pid, uniq)
        sk1 = f"case:{case_id}:step:1:shot"
        obj_prefix = f"testpike/{pid}"
        src1 = f"{obj_prefix}/exec-artifact/209913/e2e{uniq[:6]}-p1.png"
        _put_object(src1)
        pending1 = _seed_baseline(pid, sk1, 1, src1, None)

        user_id, uname = _mk_dev(api_client, uniq)
        add_member(api_client, pid, user_id)

        inject_user_token(page, uname, TESTER_PASSWORD)
        base = page.url.split("#")[0]
        page.goto(f"{base}#/cases/{case_id}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="baseline-block"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="baseline-status"]').first).to_be_visible(timeout=10000)
        block_text = page.locator('[data-test="baseline-block"]').inner_text()
        assert "操作" not in block_text, f"开发身份不应见操作列: {block_text[:400]}"
        assert page.locator('[data-test="baseline-confirm-btn"]').count() == 0, "待确认行不应渲染确认按钮"
        assert page.locator('[data-test="baseline-reject-btn"]').count() == 0, "待确认行不应渲染拒绝按钮"

        token = api_login(uname, TESTER_PASSWORD)
        headers = {"Authorization": f"Bearer {token}"}
        r = httpx.post(f"{API_BASE}/visual-baselines/{pending1}/confirm",
                       headers=headers, timeout=15)
        assert r.status_code == 403, f"开发确认应 403，实际 {r.status_code}: {r.text[:200]}"
        body = r.json()
        assert str(body.get("code")) == "40301", f"业务码应 40301: {body}"
        assert ("权限" in str(body.get("msg")) or "管理员" in str(body.get("msg"))), \
            f"403 人话缺失: {body}"
        r2 = httpx.post(f"{API_BASE}/visual-baselines/{pending1}/reject",
                        headers=headers, timeout=15)
        assert r2.status_code == 403, f"开发拒绝应 403，实际 {r2.status_code}: {r2.text[:200]}"

        row = sql_query("SELECT status || ',' || COALESCE(confirmed_by, -1)"
                        f" FROM tp_visual_baseline WHERE id = {pending1}")
        assert row.strip() == "1,-1", f"越权调用后 pending 行被改动: {row}"

        assert_no_errors(collectors, "B7 基线权限块边界")
    finally:
        if pid is not None:
            _cleanup(api_client, pid, obj_prefix)
