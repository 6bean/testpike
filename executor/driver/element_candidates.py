
from __future__ import annotations

import json
import re
from typing import Any

MAX_CANDIDATES = 80
MAX_DETAIL_CHARS = 6000
# Cross-viewport survey envelope budget (WE S4 P1): the default tier keeps
# MAX_DETAIL_CHARS; declared viewport classes append one serialized payload
# each, and the whole survey detail is tail-trimmed under this cap so a
# multi-class replay can never blow the result payload (same discipline).
SURVEY_MAX_DETAIL_CHARS = 20000
# Viewport breakpoint classes for the survey replay (design 8.2). The class
# names are the stored values of tp_web_element.variants[].viewport; the
# desktop tier matches the executor's single-source default viewport.
VIEWPORT_CLASS_SIZES = {"mobile": (375, 667), "tablet": (768, 1024),
                        "desktop": (1440, 900)}
# Single-source step/envelope key for the survey tier list (WE-S4 P2c):
# core SurveyEnvelopeTiers.ENVELOPE_TIERS_FIELD pins the same literal through
# executor/tests/test_element_envelope_key_contract.py — rename either side
# without the other and that gate turns red.
VIEWPORT_CLASSES_STEP_KEY = "viewportClasses"

_ID_IDENT_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_\-]*")
_FORM_TAGS = ("input", "textarea", "select")
# Accessible-name signal precedence (W3C accname, survey-simplified):
# aria-label > aria-labelledby > associated <label> > alt.
_ACCNAME_KEYS = ("ariaLabel", "labelledbyText", "labelText", "altAttr")
# ARIA name-from-content roles kept for the survey (button/link are the two
# that actually appear in the interactive selector set below).
_CONTENT_NAME_ROLES = ("button", "link")
# input[type] values that map to the implicit "textbox" role.
_TEXTBOX_INPUT_TYPES = ("", "text", "search", "email", "tel", "url",
                        "password", "number", "date", "time")

_JS_COLLECT = r"""
() => {
  const SEL = 'a,button,input,select,textarea,summary,[role],'
            + '[onclick],[data-test],[data-testid],[placeholder],'
            + '[contenteditable="true"]';
  const clip60 = (s) => String(s || '').trim().slice(0, 60) || null;
  const labelledByText = (el) => {
    const ids = (el.getAttribute('aria-labelledby') || '').split(/\s+/)
        .filter(Boolean);
    if (!ids.length) return null;
    return clip60(ids.map((i) => {
      const ref = document.getElementById(i);
      return ref ? (ref.innerText || ref.textContent || '') : '';
    }).join(' '));
  };
  const labelText = (el) => {
    if (el.labels && el.labels.length) {
      const t = clip60(el.labels[0].innerText);
      if (t) return t;
    }
    const wrap = el.closest && el.closest('label');
    return wrap ? clip60(wrap.innerText) : null;
  };
  const items = [];
  for (const el of document.querySelectorAll(SEL)) {
    if (items.length >= 400) break;
    const rect = el.getBoundingClientRect();
    const style = window.getComputedStyle(el);
    const visible = rect.width > 0 && rect.height > 0
        && style.display !== 'none' && style.visibility !== 'hidden';
    let text = (el.innerText || '').trim();
    if (!text && el.value) text = String(el.value);
    items.push({
      tag: el.tagName.toLowerCase(),
      id: el.id || null,
      testAttr: el.hasAttribute('data-test') ? 'data-test'
              : (el.hasAttribute('data-testid') ? 'data-testid' : null),
      testId: el.getAttribute('data-test') || el.getAttribute('data-testid') || null,
      name: el.getAttribute('name') || null,
      placeholder: el.getAttribute('placeholder') || null,
      text: text.slice(0, 60),
      visible,
      roleAttr: clip60(el.getAttribute('role')),
      ariaLabel: clip60(el.getAttribute('aria-label')),
      labelledbyText: labelledByText(el),
      labelText: labelText(el),
      altAttr: clip60(el.getAttribute('alt')),
      inputType: (el.getAttribute('type') || '').toLowerCase() || null,
      hasHref: el.hasAttribute('href'),
    });
  }
  return items;
}
"""


def _css_attr(value: str) -> str:
    pass
    return '"' + str(value).replace("\\", "\\\\").replace('"', '\\"') + '"'


def _css_for_id(value: str) -> str:
    return ("#" + value if _ID_IDENT_RE.fullmatch(value)
            else "[id=" + _css_attr(value) + "]")


def _implicit_role(item: dict) -> str | None:
    """Tag/type → implicit ARIA role (only roles this selector set can hit)."""
    tag = str(item.get("tag") or "").lower()
    if tag == "button":
        return "button"
    if tag == "a":
        return "link" if item.get("hasHref") else None
    if tag == "select":
        return "combobox"
    if tag == "textarea":
        return "textbox"
    if tag == "summary":
        return "button"
    if tag == "input":
        itype = str(item.get("inputType") or "").lower()
        if itype in ("checkbox", "radio"):
            return itype
        if itype in ("submit", "button", "reset", "image"):
            return "button"
        if itype in _TEXTBOX_INPUT_TYPES:
            return "textbox"
    return None


def _accessible_name(item: dict) -> str:
    """aria-label > aria-labelledby > label > alt (W3C accname, simplified)."""
    for key in _ACCNAME_KEYS:
        value = str(item.get(key) or "").strip()
        if value:
            return value[:60]
    return ""


def _role_anchor(item: dict) -> tuple[str, str] | None:
    """(role, accessible-name) pair, or None — never emit a bare-role target:
    a role without a name matches many elements and would break the honesty
    nail (every candidate must be usable as-is). Name falls back to own text
    for ARIA name-from-content roles only (button/link)."""
    role = str(item.get("roleAttr") or "").strip().lower() or _implicit_role(item)
    if not role:
        return None
    name = _accessible_name(item)
    if not name and role in _CONTENT_NAME_ROLES:
        name = str(item.get("text") or "").strip()[:60]
    return (role, name) if name else None


def _target_for(item: dict) -> dict | None:
    pass
    if item.get("id"):
        return {"css": _css_for_id(str(item["id"]))}
    if item.get("testAttr") and item.get("testId"):
        return {"css": f"[{item['testAttr']}={_css_attr(str(item['testId']))}]"}
    anchor = _role_anchor(item)
    if anchor:
        return {"role": f"{anchor[0]}:{anchor[1]}"}
    tag = str(item.get("tag") or "")
    if item.get("name") and tag in _FORM_TAGS:
        return {"css": f"[name={_css_attr(str(item['name']))}]"}
    if item.get("placeholder") and tag in _FORM_TAGS:
        return {"css": f"[placeholder={_css_attr(str(item['placeholder']))}]"}
    text = str(item.get("text") or "").strip()
    if text and tag in ("a", "button"):
        return {"text": text}
    return None


def _candidate_row(item: dict, target: dict) -> dict:
    """One survey candidate row: prompt target + the role/accName signals the
    ledger upsert (survey outcome reader) maps into anchor rows."""
    row = {"tag": item.get("tag"), "text": item.get("text") or "",
           "target": target}
    anchor = _role_anchor(item)
    if anchor:
        row["role"] = anchor[0]
        row["accName"] = anchor[1]
    return row


def _payload_for(items: list[dict], max_chars: int = MAX_DETAIL_CHARS,
                 max_count: int = MAX_CANDIDATES) -> dict:
    pass
    candidates: list[dict] = []
    truncated = False
    for item in items or []:
        if not isinstance(item, dict) or not item.get("visible"):
            continue
        target = _target_for(item)
        if target is None:
            continue
        if len(candidates) >= max_count:
            truncated = True
            break
        candidates.append(_candidate_row(item, target))
    payload: dict[str, Any] = {"candidates": candidates}
    while candidates and len(json.dumps(payload, ensure_ascii=False)) > max_chars:
        candidates.pop()
        truncated = True
    payload["count"] = len(candidates)
    payload["truncated"] = truncated
    return payload


def serialize_candidates(items: list[dict], max_chars: int = MAX_DETAIL_CHARS,
                         max_count: int = MAX_CANDIDATES) -> str:
    pass
    return json.dumps(_payload_for(items, max_chars, max_count),
                      ensure_ascii=False)


def collect_element_candidates(page: Any,
                               viewport_classes: Any = ()) -> str:
    pass
    try:
        raw = page.evaluate(_JS_COLLECT)
        payload = _payload_for(raw if isinstance(raw, list) else [])
        variants: list[dict] = []
        skipped: list[str] = []
        swept = False
        original = getattr(page, "viewport_size", None)
        for cls in viewport_classes or ():
            name = str(cls).strip().lower()
            if not name:
                continue
            size = VIEWPORT_CLASS_SIZES.get(name)
            if size is None:
                skipped.append(name)
                continue
            page.set_viewport_size({"width": size[0], "height": size[1]})
            swept = True
            items = page.evaluate(_JS_COLLECT)
            entry: dict[str, Any] = {"viewport": name}
            entry.update(_payload_for(items if isinstance(items, list) else []))
            variants.append(entry)
        if swept and isinstance(original, dict) and original:
            page.set_viewport_size(dict(original))
        payload["variants"] = variants
        if skipped:
            payload["skippedViewportClasses"] = skipped
        text = json.dumps(payload, ensure_ascii=False)
        while len(text) > SURVEY_MAX_DETAIL_CHARS and variants:
            variants.pop()
            payload["variantsTruncated"] = True
            text = json.dumps(payload, ensure_ascii=False)
        return text
    except Exception as e:  # noqa: BLE001
        return f"元素候选采集失败: {e}"
