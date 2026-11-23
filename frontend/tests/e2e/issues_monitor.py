"""Browser Issues monitor — shared helper for the E2E iron rule (zero tolerance).

Captures every Chrome DevTools "Issues" panel report via the CDP event
``Audits.issueAdded``: form/accessibility association problems
(FormLabelForMatchesNonExistingIdError, missing labels, inputs whose id/name
collide with browser autofill keywords) and network problems (blocked
resources and the like). Any single entry fails the test — no whitelist, same
severity as 4xx/5xx and console errors.

Chromium 151 field-tested trap: the *old* headless mode (the default behind
``launch(headless=True)`` in older Playwright builds) silently emits zero
events, i.e. fake monitoring. Launch with ``headless=False, args=["--headless=new"]``
(or xvfb-run headed) and prove the pipeline is live once per session via
``selftest_issue_monitoring`` — a probe that must fire; zero events aborts the
whole run.

Usage (conftest):
    selftest_issue_monitoring(browser)              # session level, after launch
    issues = create_issues_monitor(page)            # per test, inside attach_monitors
    assert_no_browser_issues(issues, test_name)     # folded into assert_no_errors
"""

# Self-test probe: a deliberately dirty form that MUST produce >=1 issue.
# A bare <input>/<select> with neither id nor name fires
# FormEmptyIdAndNameAttributesForInputError. Field-tested: an input that has a
# name but no label does NOT trigger an Audits issue — the probe must keep the
# empty id+name shape.
_PROBE_HTML = "<form><input type='text'><select></select></form>"


def create_issues_monitor(page):
    """Attach CDP Audits.issueAdded capture to `page`. Returns list of issue dicts.

    Each entry: {code, error_type, source, nodes:[tag names], frame_id, raw}
    """
    try:
        cdp = page.context.new_cdp_session(page)
        cdp.send("Audits.enable")
    except Exception as e:  # non-Chromium / CDP unavailable
        raise RuntimeError(
            f"Cannot enable CDP Audits (Issues monitoring is an iron rule, "
            f"degrading or skipping is forbidden): {e}"
        )

    collected = []

    def _on_issue(event):
        issue = event.get("issue") or {}
        details = issue.get("details") or {}
        entry = {
            "code": issue.get("code"),
            "error_type": None,
            "source": None,
            "nodes": [],
            "frame_id": issue.get("frameId"),
            "raw": event,
        }
        for key, val in details.items():
            if isinstance(val, dict):
                entry["error_type"] = (
                    val.get("errorType")
                    or val.get("blockedFeature")
                    or val.get("labelCode")
                    or key
                )
                # Performance issues carry the concrete subtype under
                # ``performanceIssueType`` plus a ``sourceCodeLocation`` (url /
                # line / column). Field-tested via raw CDP dump (Chromium 151):
                # there is NO top-level ``type`` — guessing that key is what
                # produced "performance:None" in failure output.
                if key == "performanceIssueDetails":
                    loc = val.get("sourceCodeLocation") or {}
                    entry["error_type"] = (
                        f"performance:{val.get('performanceIssueType') or 'Unknown'}"
                    )
                    entry["source"] = val.get("url") or loc.get("url")
                    if loc.get("lineNumber") is not None:
                        entry["source"] = (
                            f"{entry['source']}:{loc['lineNumber']}"
                            f":{loc.get('columnNumber')}"
                        )
                src = val.get("source") or val.get("url")
                if src:
                    entry["source"] = src
                node_ids = [v for k in ("violatingNodeId", "node", "invalidLabelNodeId",
                                        "labelForNodeId", "idElementNodeId", "fieldNodeId")
                            if isinstance((v := val.get(k)), int)]
                if node_ids:
                    entry["nodes"] = _describe_nodes(cdp, node_ids)
        collected.append(entry)

    cdp.on("Audits.issueAdded", _on_issue)
    page._issues_cdp = cdp  # keep the session reference alive for the page lifetime
    return collected


def _describe_nodes(cdp, node_ids):
    out = []
    for nid in node_ids:
        try:
            desc = cdp.send("DOM.describeNode", {"backendNodeId": nid})
            node = desc.get("node") or {}
            attrs = node.get("attributes") or []
            # attributes come as a flat [name, value, name, value, ...] list;
            # keep id/for/name so a failure names the dangling association.
            pairs = dict(zip(attrs[0::2], attrs[1::2]))
            keep = {k: pairs[k] for k in ("id", "for", "name", "class") if k in pairs}
            label = node.get("nodeName", "")
            out.append(f"{label} {keep}".strip() if keep else label)
        except Exception:
            out.append(f"node:{nid}")
    return out


def selftest_issue_monitoring(browser):
    """Prove the Issues pipeline is LIVE in this browser launch mode.

    Opens a throwaway context with a known-dirty form; the monitor MUST see
    >=1 Audits.issueAdded event. Zero events means the launch mode silently
    disables issue reporting (old headless) — fake monitoring, refuse to run
    the suite at all rather than report false greens.
    """
    ctx = browser.new_context()
    page = ctx.new_page()
    try:
        monitor = create_issues_monitor(page)
        page.set_content(_PROBE_HTML)
        page.wait_for_timeout(1500)
        if not monitor:
            raise RuntimeError(
                "Issues monitor self-test failed: the known-dirty form probe "
                "produced no Audits.issueAdded events. Usual root cause = old "
                "headless mode reporting nothing. Launch with "
                "headless=False + args=['--headless=new'] or xvfb-run headed. "
                "Running tests with fake monitoring is forbidden."
            )
    finally:
        ctx.close()


def assert_no_browser_issues(issues, test_name=""):
    """Zero tolerance: any Issues-panel entry fails the test. No whitelist."""
    if not issues:
        return
    lines = []
    for i in issues:
        node = f" @ <{'>,<'.join(n for n in i['nodes'] if n)}>" if i["nodes"] else ""
        src = f" ({i['source']})" if i.get("source") else ""
        lines.append(f"- [{i['code']}] {i['error_type']}{src}{node}")
    raise AssertionError(
        f"Browser Issues panel reported {len(issues)} blocking/form-association "
        f"warnings, test failed [{test_name}]:\n" + "\n".join(lines)
    )
