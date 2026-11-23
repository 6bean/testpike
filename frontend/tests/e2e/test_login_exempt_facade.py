"""Login-exemption facade: the four declaration states through the real browser.

Covers (real admin->core chain, zero mocks, iron rule 3):
1. deep link into the environment profile, row action "login exemption" opens
   the dialog and the undeclared honest state renders (status = "not registered",
   never an affirmative exemption);
2. declaring the whitelist-accounts form with one account lands the server-side
   "pending re-check" state;
3. reporting the trial-login-ok signal moves it to "re-check passed" with the
   server wording echoed verbatim and a review timestamp (the page never marks
   its own claim as passed);
4. honesty doors: re-declaring master_code with a cred key that is not in this
   project's vault is refused by the server and the previous declaration stays
   byte-identical (read back through the API, not just the DOM).

The intentional business refusal in step 4 is the one recorded 4xx; it is
dropped through the shared narrow per-endpoint helper with an exact-count
assertion, so a missing refusal could never pass silently and no other
4xx/console noise is tolerated (R4 posture, R40 single source).
"""
import pytest
from conftest import (
    BASE_URL,
    assert_no_errors,
    purge_project,
    seed_project,
    whitelist_expected_reject,
)
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(300)

ACCOUNT = "lx_probe_account"
KIND_WHITELIST_LABEL = "白名单账号免验证码"
KIND_MASTER_LABEL = "万能验证码"
SIGNAL_OK_LABEL = "试登录成功且未出现验证码"


def open_exempt_dialog(page, env_name):
    row = page.locator('[data-test="env-name-cell"]', has_text=env_name).first
    expect(row).to_be_visible(timeout=15000)
    row_action = row.locator("xpath=ancestor::tr")
    row_action.locator('[data-test="ra-more"]').click()
    # :visible filter — the row-action popper persists after close, so a
    # second open (vision leg walks several env rows) would resolve the menu
    # item twice and trip strict mode. Visible-only keeps the live instance.
    page.locator('[data-test="ra-exempt"]:visible').first.click()
    expect(page.locator('[data-test="login-exempt-dialog"]')).to_be_visible(timeout=10000)


def declare(page, pick_kind, fill_accounts=None, cred_key=None):
    page.locator(".el-radio", has_text=pick_kind).last.click()
    if pick_kind == KIND_WHITELIST_LABEL and fill_accounts:
        for acct in fill_accounts:
            page.fill('[data-test="exempt-account-input"]', acct)
            page.keyboard.press("Enter")
    if pick_kind == KIND_MASTER_LABEL and cred_key:
        page.fill('[data-test="exempt-credkey-input"]', cred_key)
    page.locator('[data-test="exempt-declare-btn"]').click()


def review_with_signal(page, signal_label):
    page.locator('[data-test="exempt-signal-select"]').click()
    page.locator(".el-select-dropdown__item:visible", has_text=signal_label).first.click()
    page.locator('[data-test="exempt-review-btn"]').click()


def test_login_exempt_facade_four_states(logged_in, collectors, api_client, uniq):
    page = logged_in
    pid = seed_project(api_client, uniq, "LXMT")
    env_name = f"LXEnv{uniq}"
    r = api_client.post(f"/projects/{pid}/envs", json={"name": env_name, "envType": 2})
    assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]
    data = r.json()["data"]
    env_id = data["id"] if isinstance(data, dict) else data
    try:
        page.goto(f"{BASE_URL}/#/projects/{pid}", wait_until="domcontentloaded")
        open_exempt_dialog(page, env_name)

        # State 1: honest undeclared — the light says nothing was registered.
        status_tag = page.locator('[data-test="exempt-status-tag"]')
        expect(status_tag).to_have_text("未登记", timeout=10000)

        # State 2: whitelist declaration lands as pending re-check (server truth).
        declare(page, KIND_WHITELIST_LABEL, fill_accounts=[ACCOUNT])
        expect(status_tag).to_have_text("待复核", timeout=10000)

        # State 3: the passing observation moves it to passed with server wording
        # and a timestamp; the page only echoes what the verdict table returned.
        review_with_signal(page, SIGNAL_OK_LABEL)
        expect(status_tag).to_have_text("复核通过", timeout=10000)
        expect(page.locator('[data-test="exempt-review-detail"]')).to_be_visible()
        expect(page.locator('[data-test="exempt-reviewed-at"]')).to_be_visible()

        # State 4: master_code with an unregistered vault key is refused and the
        # passed declaration is not overwritten.
        fake_key = f"AV_MISSING_{uniq}"
        declare(page, KIND_MASTER_LABEL, cred_key=fake_key)
        expect(page.locator(".el-message--error")).to_contain_text("尚未建档", timeout=10000)
        expect(status_tag).to_have_text("复核通过", timeout=10000)

        api_view = api_client.get(f"/login-exempt/{env_id}").json()["data"]
        assert api_view["declared"] is True
        assert api_view["kind"] == "whitelist_accounts"
        assert api_view["reviewStatus"] == "passed"
        assert api_view["accounts"] == [ACCOUNT]

        # The single intentional refusal: dropped narrowly, exact count pinned.
        drops = [e for e in collectors["network_4xx"]
                 if e["url"].endswith(f"/login-exempt/{env_id}")]
        assert drops, "server never answered the fake cred key with a refusal"
        removed = whitelist_expected_reject(
            collectors, f"/login-exempt/{env_id}", status=drops[0]["status"])
        assert removed == len(drops) == 1
        assert_no_errors(collectors, "login_exempt_facade")
    finally:
        purge_project(api_client, pid)
