"""Shared kit for the page element library E2E specs (worst-scale ledger
page + survey panel). Single source for the installed-env DSN reader, the
fixture purge and the group/inner row locators, per the shared-logic rule.
"""
import os

import psycopg2
from playwright.sync_api import expect

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__),
                                         "..", "..", ".."))
PAGES_TABLE = "[data-test='ledger-pages-table']"
# Only outer (group) rows carry the expand toggle; inner element tables have
# no expand column, so this selector counts groups and never their contents.
OUTER_ROW = PAGES_TABLE + " tr.el-table__row:has(.el-table__expand-icon)"


def dsn():
    env = {}
    with open(os.path.join(REPO_ROOT, ".env")) as fh:
        for line in fh:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                env[k.strip()] = v.strip().strip('"')
    url = env["DB_URL"].replace("jdbc:postgresql://", "").replace("jdbc:", "")
    return f"postgresql://{env['DB_USERNAME']}:{env['DB_PASSWORD']}@{url}"


def purge_ledger_fixture(project_id, ids):
    """Remove the fixture project and its element rows by owned id set."""
    with psycopg2.connect(dsn()) as conn, conn.cursor() as cur:
        cur.execute("DELETE FROM tp_web_element WHERE id = ANY(%s)", (ids,))
        cur.execute("DELETE FROM tp_project WHERE id = %s", (project_id,))
        conn.commit()


def group_row(page, page_key):
    return page.locator(OUTER_ROW, has_text=page_key).first


def expand_group(page, page_key):
    """Toggle the group expansion exactly once (re-clicking would collapse
    it again, which is how the panel spec originally raced the refetch)."""
    icon = group_row(page, page_key).locator(".el-table__expand-icon")
    if "expand-icon--expanded" not in (icon.get_attribute("class") or ""):
        icon.click()


def create_profile_via_ui(page, profile_name, start_url):
    """Single source (R40) for creating a site profile through the drawer
    form: opens the create form, fills name and start address, saves and
    waits for the ledger row, returning that row locator."""
    page.locator("[data-test='profile-create-btn']").click()
    form = page.locator("[data-test='profile-form']")
    expect(form).to_be_visible()
    form.locator("[data-test='profile-field-name']").fill(profile_name)
    form.locator("[data-test='profile-field-start-url']").fill(start_url)
    form.locator("[data-test='profile-save-btn']").click()
    row = page.locator("[data-test='profile-table'] tr.el-table__row",
                       has_text=profile_name).first
    expect(row).to_be_visible(timeout=10000)
    return row


def inner_rows(page, page_key):
    return page.locator(f"[data-test='ledger-elements-{page_key}'] tr.el-table__row")
