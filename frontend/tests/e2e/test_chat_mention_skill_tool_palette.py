
import pytest
from conftest import (
    BASE_URL,
    assert_no_errors,
    inject_token,
    purge_project,
    seed_project,
)
from playwright.sync_api import expect

pytestmark = [pytest.mark.timeout(180), pytest.mark.xdist_group("ai-chat-page")]


def test_mention_palette_lists_skill_and_tool_assembly_rows(page, collectors, api_client, uniq):
    """Open composer -> @ -> two gated groups visible -> keyboard pick -> chip."""
    pid = seed_project(api_client, uniq, "MTN")
    try:
        inject_token(page)
        page.goto(f"{BASE_URL}/#/ai/chat?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=15000)

        inp = page.locator('[data-test="chat-dock-input"]')
        assert page.locator('[data-test="chat-chips"]').count() == 0

        # "@" with an empty token: the full palette (debounce 200ms + door fetch).
        inp.click()
        inp.type("@", delay=30)
        expect(page.locator('[data-test="chat-popup"]')).to_be_visible(timeout=15000)

        # Both gated families land as rows (skill block first, then tool block
        # — the assembly order core returned, never reshuffled client-side).
        expect(page.locator('[data-test="chat-mention-skill"]').first).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="chat-mention-tool"]').first).to_be_visible(timeout=15000)

        # Group headers ride the locale single source (zh words pinned).
        header_text = page.locator('[data-test="chat-popup"] .chat-popup-group').all_inner_texts()
        assert "技能" in header_text and "工具" in header_text, f"group headers: {header_text}"

        # Accept the first SKILL row as a chip. (The keyboard Enter-accepts-
        # active path is proven by the case-chip spectrum in
        # test_ai_chat_page.py; here the palette also carries case/doc groups
        # whose active row is not the skill, so the pick is explicit.)
        first_skill = page.locator('[data-test="chat-mention-skill"]').first
        expected_title = first_skill.locator(".chat-popup-title").inner_text()
        first_skill.click()
        chip = page.locator('[data-test="chat-chip-skill"]').first
        expect(chip).to_be_visible(timeout=5000)
        assert expected_title in chip.inner_text(), \
            f"chip title {expected_title!r} missing from {chip.inner_text()!r}"
        # The "@token" fragment leaves the composer (chip replaced the text).
        assert "@" not in inp.input_value(), "accepted mention fragment must leave the composer"

        assert_no_errors(collectors, "chat mention skill/tool palette")
    finally:
        purge_project(api_client, pid)
