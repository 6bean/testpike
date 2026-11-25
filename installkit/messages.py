#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Single source for installer-facing messages.

The install surface ships English only (international open-source delivery):
the installer runs once, so a bilingual wizard was judged not worth the
complexity. Callers keep using t(key, **fmt); adding a key here is the only
way to introduce a new operator-visible string in this surface.
"""

MESSAGES = {
    "host.not_in_domain":
        "Host profile \"{host}\" is outside the supported range; "
        "it must be one of {supported} (pass --host explicitly to override)",
    "recipe.empty_recipe":
        "This engine has no install recipe yet (empty recipe); refusing to "
        "guess install commands. Add the recipe in Engine Center or wait for "
        "a later release",
    "recipe.hosts_invalid":
        "Install recipe \"hosts\" section is malformed (expected a "
        "host-profile dictionary)",
    "recipe.host_not_covered":
        "The recipe provides no profile for this host (current host: {host}; "
        "covered: {covered})",
    "recipe.pin_missing":
        "Recipe has no version pin (version_pin); reverse pin rewrite has "
        "nothing to target, so rollback is refused (a fake rollback is worse). "
        "Add the version pin to the recipe first",
    "ledger.corrupt":
        "installed.json is unreadable (damaged ledger); uninstall can no "
        "longer guarantee \"only remove self-installed\". Rebuild the file "
        "by hand (run list for details), then re-run the health check",
    "ledger.report_missing":
        "Health-check report doctor-report.json is missing; the repair flow "
        "always checks first (run the repair scenario to probe), and the "
        "ledger refuses to guess a refill plan",
    "ledger.report_corrupt":
        "Health-check report doctor-report.json is damaged; re-run repair to "
        "refresh it",
    "ledger.engine_code_invalid":
        "Engine code \"{engine}\" is invalid: the key separator | is not "
        "allowed",
    "doctor.copy_hint":
        "Copy the health-check report doctor-report.json to your "
        "administrator, or open the Engine Center health panel (A1 "
        "env-doctor) for fix steps (the report passes a masking "
        "self-check; never paste raw logs containing plaintext keys)",
    "doctor.all_ok":
        "All engines present passed their probes "
        "(no missing components in this check)",
    "doctor.missing_line":
        "{count} component(s) missing; fix: install.sh --scenario=engines "
        "--engine=<code> --base-url=<api-address>, or re-run repair after the "
        "refill step lands",
    "doctor.runtime_missing":
        "Runtime node missing (required {required}, declared by the engine "
        "recipe); the repair step auto-installs it for this host profile, "
        "no manual action needed",
    "doctor.runtime_below":
        "Runtime node v{current} is below the required {required} (declared "
        "by the engine recipe); the repair step upgrades it automatically, "
        "no manual action needed",
    "doctor.mcp_ok":
        "MCP transport handshake OK ({base}: unauthenticated requests are "
        "refused as expected; detail: {detail})",
    "doctor.mcp_shape_gap":
        "MCP transport shape gap ({base}): {detail} — fix the api service "
        "auth gate before connecting external agents",
    "doctor.mcp_auth_gap":
        "MCP authenticated handshake failed ({base}): {detail} — check that "
        "TP_MCP_TOKEN holds a valid, enabled API token",
    "doctor.mcp_unreachable":
        "MCP endpoint unreachable ({base}): {detail} — verify the api service "
        "is running at this address",
    "fleet.identity_fail":
        "Node {name} has no valid enroll identity (one-time enrollment "
        "missing or the token expired); issue a new one-time token on the "
        "server and re-run the enroll flow on that node",
    "fleet.identity_unknown":
        "Enroll identity for node {name} is unavailable (column never "
        "reported); confirm the node is registered in the executor ledger, "
        "then re-run",
    "fleet.heartbeat_fail":
        "Node {name} last heartbeat was {age}s ago, past the {offline}s "
        "offline threshold; run systemctl status tp-service-agent on that "
        "node — the heartbeat recovers on its own once the agent is up",
    "fleet.heartbeat_unknown":
        "Node {name} has no heartbeat on record; install and start the "
        "tp-service-agent service on that node",
    "fleet.heartbeat_skew":
        "Node {name} heartbeat timestamp is {age}s ahead of now (clock "
        "skew); sync the node clock over NTP first, or the offline verdict "
        "cannot be trusted",
    "fleet.version_fail":
        "Node {name} runs version {version}, behind the gold version {gold}; "
        "start a rolling upgrade for it in the Upgrade Center",
    "fleet.version_unknown":
        "Node {name} cannot be version-compared (own version unreported or "
        "no gold baseline set); verify the agent component report and set "
        "the gold version in the Upgrade Center",
    "fleet.queue_fail":
        "Command queue for node {name} is unreachable under mutual "
        "authentication (no valid ack to a dispatched command); check the "
        "agent token has not been revoked and the route to the control "
        "address is open, then re-run",
    "fleet.queue_unknown":
        "Queue connectivity for node {name} was not probed; attach probe "
        "data and re-run",
    "fleet.capacity_full":
        "Node {name} has no concurrency headroom ({running}/{capacity} "
        "running); wait for in-flight tasks to finish or attach more nodes "
        "before dispatching",
    "fleet.capacity_unknown":
        "Node {name} reports no capacity/running counters; headroom and "
        "drain reachability cannot be judged — confirm the agent version "
        "reports capacity",
    "fleet.ledger_inconsistent":
        "Node {name} running count {running} is outside the 0..{capacity} "
        "window of its capacity; restart the agent to re-report truthfully, "
        "and do not drain this node until the ledger recovers",
}


def t(key, **fmt):
    """Render one operator-visible message by key (English-only surface)."""
    text = MESSAGES[key]
    return text.format(**fmt) if fmt else text
