
import re

import pytest
from playwright.sync_api import expect

from conftest import api_client
from conftest import assert_no_errors, logged_in, sql_query  # noqa: F401

pytestmark = pytest.mark.timeout(120)

_STYLE_SWITCH = '[data-test="style-gate-card"] [data-test="style-gate-enabled"]'


def _db_style_gate_value():
    return sql_query("SELECT config_value FROM tp_sys_config "
                     "WHERE config_key='script.style-gate.enabled'")


def test_b6_style_gate_switch_roundtrip(logged_in, api_client, collectors):
    pass
    page = logged_in
    try:
        base = page.url.split("#")[0]
        page.goto(f"{base}#/configs", wait_until="domcontentloaded")
        card = page.locator('[data-test="style-gate-card"]')
        expect(card).to_be_visible(timeout=10000)
        switch = page.locator(_STYLE_SWITCH).first
        assert "is-checked" not in (switch.get_attribute("class") or ""), \
            "初始应为关（默认关存量零变化）"
        switch.click()
        expect(page.locator(".el-message--success").first).to_be_visible(timeout=5000)

        page.goto(f"{base}#/configs", wait_until="domcontentloaded")
        expect(page.locator(_STYLE_SWITCH).first).to_have_class(
            re.compile(r"is-checked"), timeout=5000)
        assert _db_style_gate_value() == "1"

        page.locator(_STYLE_SWITCH).first.click()
        expect(page.locator(".el-message--success").first).to_be_visible(timeout=5000)
        assert _db_style_gate_value() == "0"
    finally:
        sql_query("UPDATE tp_sys_config SET config_value='0' "
                  "WHERE config_key='script.style-gate.enabled'")
    assert_no_errors(collectors, "B6 脚本规范闸开关卡往返")
