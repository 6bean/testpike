#!/usr/bin/env python3
"""Tool-surface coverage reconciliation gate (AO batch, dialog five-way gate stage 2).

Purpose (single place, honest ledger):
  Every user-visible capability leaf in the menu is a promise the dialog can
  keep only if a callable tool exists for it. This scanner reconciles:

    menuStructure.ts leaves  x  page-level primary actions (data-test anchors)
    <-->  tool registry (source-seeded built-ins + declared dispositions)
    <-->  live DB: published tp_tool rows and enabled project bindings (--db)

  Anything on either side that is not accounted for is pointed by name; the
  known-gap disposition file (the local dispositions ledger) is the
  debt ledger — every entry must carry status=debt|exempt|mapped with a written
  reason, so gaps surface per batch instead of sinking (same discipline as the
  other static scanners).

  The evolution negative list (four forbidden classes from the dialog ruling:
  self-code/build edits, gate/K8-key edits, signing confirmations on behalf of
  a human, auto-publish without review) is declared here as a pattern set. The
  static phase asserts no registered/seeded tool ever matches; the runtime
  refuse-at-assembly assertion lands with the evolution stage — this gate owns
  the pattern set so both phases read one source.

Exit codes: 0 = ledger reconciled, 1 = unpointed gaps or negative hits.
Modes:
  --check      (default) reconcile against dispositions, print named gaps
  --emit       print the live ledger JSON (no disposition logic)
  --bootstrap  (re)generate the disposition file skeleton from the live ledger,
               preserving existing entries for leaves that still exist
  --selftest   two-phase proof: dirty sample must be pointed, clean must be
               quiet (an inert gate is worse than no gate)
  --db         additionally reconcile against the live PostgreSQL ledger
"""
import argparse
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MENU_TS = os.path.join(ROOT, "frontend", "src", "config", "menuStructure.ts")
ROUTES_TS = os.path.join(ROOT, "frontend", "src", "router", "routes.ts")
GENKIT_SEED = os.path.join(
    ROOT, "core", "src", "main", "java", "com", "sixbean", "testpike", "core",
    "tool", "domain", "component", "GenkitToolSeedInitializer.java")
CASEWRITE_SEED = os.path.join(
    ROOT, "core", "src", "main", "java", "com", "sixbean", "testpike", "core",
    "tool", "domain", "component", "CaseWriteToolSeedInitializer.java")
TAGWRITE_SEED = os.path.join(
    ROOT, "core", "src", "main", "java", "com", "sixbean", "testpike", "core",
    "tool", "domain", "component", "TagWriteToolSeedInitializer.java")
PPTWRITE_SEED = os.path.join(
    ROOT, "core", "src", "main", "java", "com", "sixbean", "testpike", "core",
    "tool", "domain", "component", "PromptTplWriteToolSeedInitializer.java")
SKILLWRITE_SEED = os.path.join(
    ROOT, "core", "src", "main", "java", "com", "sixbean", "testpike", "core",
    "tool", "domain", "component", "SkillWriteToolSeedInitializer.java")
REVIEWWRITE_SEED = os.path.join(
    ROOT, "core", "src", "main", "java", "com", "sixbean", "testpike", "core",
    "tool", "domain", "component", "CaseReviewToolSeedInitializer.java")
PLANWRITE_SEED = os.path.join(
    ROOT, "core", "src", "main", "java", "com", "sixbean", "testpike", "core",
    "tool", "domain", "component", "PlanCreateToolSeedInitializer.java")
BATCHWRITE_SEED = os.path.join(
    ROOT, "core", "src", "main", "java", "com", "sixbean", "testpike", "core",
    "tool", "domain", "component", "BatchCreateToolSeedInitializer.java")
REPORTWRITE_SEED = os.path.join(
    ROOT, "core", "src", "main", "java", "com", "sixbean", "testpike", "core",
    "tool", "domain", "component", "ReportGenerateToolSeedInitializer.java")
APITESTWRITE_SEED = os.path.join(
    ROOT, "core", "src", "main", "java", "com", "sixbean", "testpike", "core",
    "tool", "domain", "component", "ApiTestDefaultsToolSeedInitializer.java")
APISPECWRITE_SEED = os.path.join(
    ROOT, "core", "src", "main", "java", "com", "sixbean", "testpike", "core",
    "tool", "domain", "component", "ApiSpecPullToolSeedInitializer.java")
PERFLAUNCH_SEED = os.path.join(
    ROOT, "core", "src", "main", "java", "com", "sixbean", "testpike", "core",
    "tool", "domain", "component", "PerfLaunchToolSeedInitializer.java")
RESERVATION_SEED = os.path.join(
    ROOT, "core", "src", "main", "java", "com", "sixbean", "testpike", "core",
    "tool", "domain", "component", "ReservationCreateToolSeedInitializer.java")
POPUP_RULE_SEED = os.path.join(
    ROOT, "core", "src", "main", "java", "com", "sixbean", "testpike", "core",
    "tool", "domain", "component", "PopupRuleLearnToolSeedInitializer.java")
MOCK_SPACE_SEED = os.path.join(
    ROOT, "core", "src", "main", "java", "com", "sixbean", "testpike", "core",
    "tool", "domain", "component", "MockSpaceCreateToolSeedInitializer.java")
MOCK_STUB_SEED = os.path.join(
    ROOT, "core", "src", "main", "java", "com", "sixbean", "testpike", "core",
    "tool", "domain", "component", "MockStubWriteToolSeedInitializer.java")
AIGEN_BATCH_SEED = os.path.join(
    ROOT, "core", "src", "main", "java", "com", "sixbean", "testpike", "core",
    "tool", "domain", "component", "AiGenBatchSubmitToolSeedInitializer.java")
DEFECT_SEED = os.path.join(
    ROOT, "core", "src", "main", "java", "com", "sixbean", "testpike", "core",
    "tool", "domain", "component", "DefectWriteToolSeedInitializer.java")
DATA_FACTORY_SEED = os.path.join(
    ROOT, "core", "src", "main", "java", "com", "sixbean", "testpike", "core",
    "tool", "domain", "component", "DataFactoryWriteToolSeedInitializer.java")
EXPLORE_SEED = os.path.join(
    ROOT, "core", "src", "main", "java", "com", "sixbean", "testpike", "core",
    "tool", "domain", "component", "ExploreCreateToolSeedInitializer.java")
DISPOSITIONS = os.path.join(ROOT, "scripts", ".tool_coverage_dispositions.json")
# In-loop built-in meta-tools (AP-5 catalog search , externalized-result
# retrieval : not menu-leaf callables, adjudicated by name here so
# the ledger carries every tool the dialog can invoke, not only seeded ones.
META_TOOL_SOURCES = [
    "ai-service/src/main/java/com/sixbean/testpike/aiservice/tool/domain/component/FcToolCatalog.java",
    "ai-service/src/main/java/com/sixbean/testpike/aiservice/tool/domain/component/ToolResultRetrievalTool.java",
]

# Primary-action anchors: buttons whose data-test ends with one of these verb
# suffixes are page-level write entry points per the three-band layout ruling.
ACTION_SUFFIX_RE = re.compile(
    r"(create|new|add|import|launch|run|start|send|approve|submit|generate|export)$")

# Four forbidden classes of the evolution negative list (fail-closed, no escape
# hatch — keep names free of bookkeeping codes per the identifier ruling).
NEGATIVE_PATTERNS = [
    ("self-code-edit", re.compile(r"(service\.rewrite|source\.edit|build\.modify|jar\.|deploy\.self)", re.I)),
    ("gate-or-key-edit", re.compile(r"(config\.(set|write|delete)|k8\.key|sysconfig\.(set|update)|ai\.(chat|ctx|gen)\.[a-z-]+\.(set))", re.I)),
    ("confirm-sign-on-behalf", re.compile(r"(confirm\.(auto|sign|bypass)|token\.(issue-self|mint)|approval\.self)", re.I)),
    ("auto-publish", re.compile(r"(publish\.auto|release\.direct|approve\.self)", re.I)),
]


# ---- MCP transport tool-shape four-element check ( close-out; family of the
# shape gate). Every MCP-transport tool in McpToolCatalog must carry:
#   1 scope-declaration    — requiredScope resolves to an "mcp." literal
#   2 write-idempotency    — write tools name the Idempotency-Key obligation in
#                            their outward description (the handler enforces from
#                            the same catalog write flag — one source, no second list)
#   3 audit-trail          — the dispatch surface records every call (ok AND fail
#                            branches) into the tp_tool_invocation ledger
#   4 non-blocking-receipt — async trigger tools answer with task handles and name
#                            their poll partner; terminal cancel answers {cancelled,state}
#   5 session-confirm-closure — a write tool that hands the human a one-shot
#                            confirm ref must name the in-session confirm tool in
#                            its outward description, that tool must exist in the
#                            catalog, the gateway receipt must carry the
#                            confirm_ref handle, and no outward description may
#                            send the user to the web console to confirm (standing
#                            ruling: confirmation closes inside the agent session;
#                            the platform draft card stays as a parallel fallback).
# The document trio (doc.upload / doc_task.poll / doc_task.cancel) is demanded by
# name: a missing tool is itself a finding. --selftest plants one violation per
# element (R43: a detector without a hit-case is an inert detector).
MCP_SRC = os.path.join(ROOT, "api", "src", "main", "java", "com",
                       "sixbean", "testpike", "api", "mcp")
MCP_CATALOG = os.path.join(MCP_SRC, "domain", "component", "McpToolCatalog.java")
MCP_HANDLER = os.path.join(MCP_SRC, "domain", "component", "McpServerHandler.java")
MCP_GATEWAY = os.path.join(MCP_SRC, "infrastructure", "remote",
                           "RestMcpToolGateway.java")
DOC_TRIO = ("doc.upload", "doc_task.poll", "doc_task.cancel")
ASYNC_FAMILIES = {"doc.upload": "doc_task.poll"}
RECEIPT_HANDLES = {"doc.upload": ("document_id", "task_id")}
TERMINAL_CANCEL_KEYS = ("cancelled", "state")
_IDEMPOTENCY_OBLIGATION = "Idempotency-Key"
# Outward sentence every write tool must carry (single source with McpToolCatalog: the plant
# below deletes exactly this text, so a reworded catalog sentence must be reworded here too).
IDEMPOTENCY_OBLIGATION_SENTENCE = ("写操作须携带幂等键参数 idempotency_key（不能自定义请求头的客户端用它即可，"
            "等价通道=HTTP 头 Idempotency-Key），同键重复调用返回首次结果、不重复执行。")
# Element 5: the in-session confirm tool, and the write tools whose receipt owes
# it a one-shot confirm ref. A name listed here that no longer exists in the
# catalog is itself a finding (stale declaration).
CONFIRM_TOOL = "mcp.review.confirm"
CONFIRM_FAMILIES = {"script.writeback": CONFIRM_TOOL}
CONFIRM_RECEIPT_KEYS = ("confirm_ref",)
# Outward wording that would send the human to the web console to confirm.
LOGIN_DEMAND = re.compile(r"(登录平台[^。；]{0,8}确认|到平台界面确认|请在网页确认|"
                          r"log\s+in\s+(?:to|into)\s+(?:the\s+)?(?:platform|web|console)[^.]{0,20}confirm)")
CONFIRM_EXEMPT_MARK = "无需登录"


def parse_mcp_catalog(text):
    """Return (scope-constant map, {tool: description/write/scopeConst})."""
    consts = dict(re.findall(r'String (SCOPE_\w+)\s*=\s*"([^"]+)"', text))
    entries = {}
    for m in re.finditer(
            r'new McpToolDefinition\(\s*"([\w.]+)"\s*,\s*(.*?)\s*,\s*(true|false)'
            r'\s*,\s*(SCOPE_\w+)\s*,', text, re.S):
        entries[m.group(1)] = {"description": m.group(2),
                               "write": m.group(3) == "true",
                               "scopeConst": m.group(4)}
    return consts, entries


def mcp_shape_findings(catalog_text, gateway_text, handler_text):
    """Four-element verdict per catalog tool; [] means every shape element holds."""
    findings = []
    consts, entries = parse_mcp_catalog(catalog_text)
    for name in DOC_TRIO:
        if name not in entries:
            findings.append(f"{name}: document-channel tool missing from the MCP catalog")
    audit_wired = ("recordClient.record(" in handler_text
                   and "McpInvocationRemoteInput.ok(" in handler_text
                   and "McpInvocationRemoteInput.fail(" in handler_text)
    for name, e in sorted(entries.items()):
        literal = consts.get(e["scopeConst"])
        if not literal or not literal.startswith("mcp."):
            findings.append(f"{name}: shape element 1 scope-declaration missing "
                            f"(constant {e['scopeConst']} resolves to {literal!r})")
        if e["write"] and _IDEMPOTENCY_OBLIGATION not in e["description"]:
            findings.append(f"{name}: shape element 2 write tool without the "
                            f"Idempotency-Key obligation in its outward description")
        if not audit_wired:
            findings.append(f"{name}: shape element 3 dispatch surface records no "
                            f"invocation audit row (ok/fail branches)")
        partner = ASYNC_FAMILIES.get(name)
        if partner:
            if partner not in entries:
                findings.append(f"{name}: shape element 4 async trigger without "
                                f"poll partner {partner} in the catalog")
            elif partner not in e["description"]:
                findings.append(f"{name}: shape element 4 receipt description does "
                                f"not name the poll partner {partner}")
        for key in RECEIPT_HANDLES.get(name, ()):
            if f'"{key}"' not in gateway_text:
                findings.append(f"{name}: shape element 4 non-blocking receipt "
                                f"missing handle key {key} on the gateway")
        if name == "doc_task.cancel":
            for key in TERMINAL_CANCEL_KEYS:
                if f'"{key}"' not in gateway_text:
                    findings.append(f"{name}: terminal receipt does not carry {key}")
        if name in CONFIRM_FAMILIES:
            partner = CONFIRM_FAMILIES[name]
            if partner not in entries:
                findings.append(f"{name}: shape element 5 confirm-gated write tool "
                                f"without the in-session confirm tool {partner} in the catalog")
            elif partner not in e["description"]:
                findings.append(f"{name}: shape element 5 receipt description does not "
                                f"name the in-session confirm tool {partner}")
            for key in CONFIRM_RECEIPT_KEYS:
                if f'"{key}"' not in gateway_text:
                    findings.append(f"{name}: shape element 5 confirm receipt missing "
                                    f"handle key {key} on the gateway")
        if e["write"] and LOGIN_DEMAND.search(e["description"]) \
                and CONFIRM_EXEMPT_MARK not in e["description"]:
            findings.append(f"{name}: shape element 5 outward wording sends the user to "
                            f"the web console to confirm; confirmation must close inside "
                            f"the agent session (mcp.review.confirm)")
    for declared in sorted(CONFIRM_FAMILIES):
        if declared not in entries:
            findings.append(f"{declared}: shape element 5 declared confirm-gated tool is "
                            f"absent from the catalog (stale gate declaration)")
    return findings


def leaves_from_menu():
    """Parse every MenuLeaf literal: path + data-test + optional perm."""
    text = open(MENU_TS, encoding="utf-8").read()
    leaves = []
    for m in re.finditer(
            r"\{\s*path:\s*'([^']+)'[^}]*?dataTest:\s*'([^']+)'[^}]*?\}", text):
        path, data_test = m.group(1), m.group(2)
        block = m.group(0)
        perm = re.search(r"perm:\s*'([^']+)'", block)
        leaves.append({"path": path, "dataTest": data_test,
                       "perm": perm.group(1) if perm else None})
    return leaves


def routes_view_map():
    """Parse routes.ts into leaf path -> view component file (relative to repo).

    Route entries pair 'path: ...' with the next 'component: () => import(...)';
    redirect-only entries have no component and are skipped (retired leaves).
    """
    lines = open(ROUTES_TS, encoding="utf-8").read().splitlines()
    mapping, pending_path = {}, None
    for ln in lines:
        pm = re.search(r"path:\s*'(/?[^']+)'", ln)
        if pm and "redirect" not in ln:
            pending_path = pm.group(1)
            continue
        cm = re.search(r"component:\s*\(\)\s*=>\s*import\('(@?/[^']+\.vue)'\)", ln)
        if cm and pending_path:
            file = cm.group(1).replace("@/", "frontend/src/")
            mapping[pending_path.lstrip("/")] = file
            pending_path = None
    return mapping


def primary_actions(view_file):
    """data-test anchors in the leaf view that name a page-level write entry."""
    path = os.path.join(ROOT, view_file)
    if not os.path.isfile(path):
        return None
    found = []
    for m in re.finditer(r'data-test="([^"]+)"', open(path, encoding="utf-8").read()):
        anchor = m.group(1)
        if ACTION_SUFFIX_RE.search(anchor):
            found.append(anchor)
    return sorted(set(found))


def seeded_builtin_tools():
    """Tool names registered by the seed initializers (source of truth, live DB
    rows may be transiently wiped by test truncations — see PROGRESS ledger)."""
    tools = []
    gen = open(GENKIT_SEED, encoding="utf-8").read()
    m = re.search(r"TOP10_FUNCTIONS\s*=\s*List\.of\(([^)]*)\)", gen, re.S)
    if m:
        for name in re.findall(r'"([^"]+)"', m.group(1)):
            tools.append({"name": "genkit:" + name, "kind": "GENKIT", "origin": "GenkitToolSeedInitializer"})
    cw = open(CASEWRITE_SEED, encoding="utf-8").read()
    for intent in re.findall(r'TOOL_(?:CASE_(?:CREATE|UPDATE))\s*=\s*"([^"]+)"', cw):
        tools.append({"name": intent, "kind": "CASE_WRITE", "origin": "CaseWriteToolSeedInitializer"})
    tw = open(TAGWRITE_SEED, encoding="utf-8").read()
    for intent in re.findall(r'TOOL_TAG_[A-Z]+\s*=\s*"([^"]+)"', tw):
        tools.append({"name": intent, "kind": "TAG_WRITE", "origin": "TagWriteToolSeedInitializer"})
    pw = open(PPTWRITE_SEED, encoding="utf-8").read()
    for intent in re.findall(r'TOOL_PROMPTTPL_[A-Z]+\s*=\s*"([^"]+)"', pw):
        tools.append({"name": intent, "kind": "PROMPT_TPL_WRITE",
                      "origin": "PromptTplWriteToolSeedInitializer"})
    sw = open(SKILLWRITE_SEED, encoding="utf-8").read()
    for intent in re.findall(r'TOOL_SKILL_[A-Z]+\s*=\s*"([^"]+)"', sw):
        tools.append({"name": intent, "kind": "SKILL_WRITE",
                      "origin": "SkillWriteToolSeedInitializer"})
    rw = open(REVIEWWRITE_SEED, encoding="utf-8").read()
    for intent in re.findall(r'TOOL_CASE_REVIEW\s*=\s*"([^"]+)"', rw):
        tools.append({"name": intent, "kind": "CASE_REVIEW",
                      "origin": "CaseReviewToolSeedInitializer"})
    pw = open(PLANWRITE_SEED, encoding="utf-8").read()
    for intent in re.findall(r'TOOL_PLAN_[A-Z]+\s*=\s*"([^"]+)"', pw):
        tools.append({"name": intent, "kind": "PLAN_WRITE",
                      "origin": "PlanCreateToolSeedInitializer"})
    bw = open(BATCHWRITE_SEED, encoding="utf-8").read()
    for intent in re.findall(r'TOOL_BATCH_[A-Z]+\s*=\s*"([^"]+)"', bw):
        tools.append({"name": intent, "kind": "BATCH_WRITE",
                      "origin": "BatchCreateToolSeedInitializer"})
    rw = open(REPORTWRITE_SEED, encoding="utf-8").read()
    for intent in re.findall(r'TOOL_REPORT_[A-Z]+\s*=\s*"([^"]+)"', rw):
        tools.append({"name": intent, "kind": "REPORT_WRITE",
                      "origin": "ReportGenerateToolSeedInitializer"})
    aw = open(APITESTWRITE_SEED, encoding="utf-8").read()
    for intent in re.findall(r'TOOL_APITEST_[A-Z_]+\s*=\s*"([^"]+)"', aw):
        tools.append({"name": intent, "kind": "APITEST_WRITE",
                      "origin": "ApiTestDefaultsToolSeedInitializer"})
    sw = open(APISPECWRITE_SEED, encoding="utf-8").read()
    for intent in re.findall(r'TOOL_APISPEC_[A-Z_]+\s*=\s*"([^"]+)"', sw):
        tools.append({"name": intent, "kind": "APISPEC_WRITE",
                      "origin": "ApiSpecPullToolSeedInitializer"})
    pl = open(PERFLAUNCH_SEED, encoding="utf-8").read()
    for intent in re.findall(r'TOOL_PERF_[A-Z_]+\s*=\s*"([^"]+)"', pl):
        tools.append({"name": intent, "kind": "PERF_LAUNCH",
                      "origin": "PerfLaunchToolSeedInitializer"})
    rv = open(RESERVATION_SEED, encoding="utf-8").read()
    for intent in re.findall(r'TOOL_RESERVATION_[A-Z_]+\s*=\s*"([^"]+)"', rv):
        tools.append({"name": intent, "kind": "RESERVATION_WRITE",
                      "origin": "ReservationCreateToolSeedInitializer"})
    pr = open(POPUP_RULE_SEED, encoding="utf-8").read()
    for intent in re.findall(r'TOOL_POPUP_RULE_[A-Z_]+\s*=\s*"([^"]+)"', pr):
        tools.append({"name": intent, "kind": "POPUP_RULE_LEARN",
                      "origin": "PopupRuleLearnToolSeedInitializer"})
    ms = open(MOCK_SPACE_SEED, encoding="utf-8").read()
    for intent in re.findall(r'TOOL_MOCK_SPACE_[A-Z_]+\s*=\s*"([^"]+)"', ms):
        tools.append({"name": intent, "kind": "MOCK_SPACE_WRITE",
                      "origin": "MockSpaceCreateToolSeedInitializer"})
    mu = open(MOCK_STUB_SEED, encoding="utf-8").read()
    for intent in re.findall(r'TOOL_MOCK_STUB_[A-Z_]+\s*=\s*"([^"]+)"', mu):
        tools.append({"name": intent, "kind": "MOCK_STUB_WRITE",
                      "origin": "MockStubWriteToolSeedInitializer"})
    ab = open(AIGEN_BATCH_SEED, encoding="utf-8").read()
    for intent in re.findall(r'TOOL_AIGEN_BATCH_[A-Z_]+\s*=\s*"([^"]+)"', ab):
        tools.append({"name": intent, "kind": "AIGEN_BATCH_WRITE",
                      "origin": "AiGenBatchSubmitToolSeedInitializer"})
    df = open(DEFECT_SEED, encoding="utf-8").read()
    for intent in re.findall(r'TOOL_DEFECT_[A-Z_]+\s*=\s*"([^"]+)"', df):
        tools.append({"name": intent, "kind": "DEFECT_WRITE",
                      "origin": "DefectWriteToolSeedInitializer"})
    dfc = open(DATA_FACTORY_SEED, encoding="utf-8").read()
    for intent in re.findall(r'TOOL_DATA_FACTORY_[A-Z_]+\s*=\s*"([^"]+)"', dfc):
        tools.append({"name": intent, "kind": "DATA_FACTORY_WRITE",
                      "origin": "DataFactoryWriteToolSeedInitializer"})
    ex = open(EXPLORE_SEED, encoding="utf-8").read()
    for intent in re.findall(r'TOOL_EXPLORE_[A-Z_]+\s*=\s*"([^"]+)"', ex):
        tools.append({"name": intent, "kind": "EXPLORE_CREATE",
                      "origin": "ExploreCreateToolSeedInitializer"})
    return tools


def meta_tool_names():
    """Parse the NAME constants of the in-loop built-in meta-tools from source.

    Reading the Java constant (instead of hard-coding a string here) keeps the
    ledger and the runtime one-sourced: if a meta-tool is renamed or retired,
    reconcile_meta_tools points the drift by name.
    """
    names = []
    for rel in META_TOOL_SOURCES:
        src = os.path.join(ROOT, rel)
        m = re.search(r"public static final String NAME = \"([^\"]+)\"",
                      open(src, encoding="utf-8").read())
        names.append(m.group(1) if m else None)
    return names


def reconcile_meta_tools(ledger):
    """Reconcile the loop meta-tools ledger against source and the seed face.

    Meta-tools extend what the dialog can DO inside the ReAct loop (catalog
    search, result retrieval) without becoming callable write surfaces, so they
    must (a) exist in source under the declared names, (b) never match the
    evolution negative list, and (c) never be seeded as tp_tool menu leaves —
    a seeded meta-tool would put a governance meta-surface on the callable map.
    Returns a list of named problems (empty = reconciled).
    """
    problems = []
    names = meta_tool_names()
    for rel, name in zip(META_TOOL_SOURCES, names):
        if name is None:
            problems.append(f"{rel}: ledger expects a NAME constant but none parsed")
    declared = {n for n in names if n}
    if len(declared) != len(names):
        problems.append(f"meta-tool NAME collision: {names}")
    for hit in scan_negative(names):
        problems.append(f"meta-tool {hit['name']} matches negative class {hit['class']}")
    seeded = {t["name"] for t in ledger["builtinTools"]}
    for name in sorted(declared):
        if name in seeded:
            problems.append(f"meta-tool '{name}' also appears in the seeded tp_tool face — a loop meta-tool must stay built-in, not a menu-leaf callable")
    return problems


def scan_negative(names):
    hits = []
    for label, pat in NEGATIVE_PATTERNS:
        for n in names:
            if pat.search(n):
                hits.append({"class": label, "name": n})
    return hits


def build_ledger():
    leaves = leaves_from_menu()
    views = routes_view_map()
    for lf in leaves:
        key = lf["path"].lstrip("/")
        vf = views.get(key)
        lf["view"] = vf
        lf["actions"] = primary_actions(vf) if vf else None
    return {"leaves": leaves, "builtinTools": seeded_builtin_tools()}


def db_reconcile():
    """Optional live ledger: published tools + enabled project bindings."""
    try:
        import psycopg2  # noqa: F401
    except ImportError:
        return {"error": "psycopg2 unavailable"}
    env = {}
    env_path = os.path.join(ROOT, ".env")
    if os.path.isfile(env_path):
        for ln in open(env_path, encoding="utf-8"):
            m = re.match(r"(DB_URL|DB_USERNAME|DB_PASSWORD)=(.*)", ln.strip())
            if m:
                env[m.group(1)] = m.group(2)
    if "DB_URL" not in env:
        return {"error": "DB_URL missing from .env"}
    m = re.match(r"jdbc:postgresql://([^:/]+):(\d+)/(\w+)", env["DB_URL"])
    if not m:
        return {"error": "unparsed DB_URL"}
    conn = psycopg2.connect(host=m.group(1), port=int(m.group(2)), dbname=m.group(3),
                            user=env["DB_USERNAME"], password=env["DB_PASSWORD"])
    try:
        cur = conn.cursor()
        cur.execute("SELECT count(*) FROM tp_tool WHERE status = 3")
        published = cur.fetchone()[0]
        cur.execute("SELECT count(*) FROM tp_project_tool WHERE enabled = 1")
        enabled_bindings = cur.fetchone()[0]
        return {"publishedTools": published, "enabledProjectBindings": enabled_bindings}
    finally:
        conn.close()


def load_dispositions():
    if not os.path.isfile(DISPOSITIONS):
        return {}
    data = json.load(open(DISPOSITIONS, encoding="utf-8"))
    return {e["leaf"]: e for e in data.get("entries", [])}


def check(ledger, dispositions):
    gaps, unaccounted = [], []
    leaves = ledger["leaves"]
    builtin = {t["name"] for t in ledger["builtinTools"]}
    mapped_tools = {e.get("tool") for e in dispositions.values() if e.get("tool")}
    for lf in leaves:
        d = dispositions.get(lf["path"])
        if d is None:
            unaccounted.append(lf["path"])
            continue
        if d["status"] == "mapped" and not (set(d.get("tools", [])) <= (builtin | mapped_tools)):
            unknown = sorted(set(d.get("tools", [])) - (builtin | mapped_tools))
            gaps.append(f"{lf['path']}: disposition names unknown tool(s) {unknown}")
    stale = sorted(set(dispositions) - {lf["path"] for lf in leaves})
    for p in stale:
        gaps.append(f"{p}: disposition exists but the menu leaf retired")
    return gaps, unaccounted


def planted_shape_phases(catalog_text, gateway_text, handler_text):
    """One planted violation per MCP tool-shape element, each paired with the tool
    name its verdict must point at. Shared by --shape-selftest and --selftest so
    the two phases cannot drift apart (one source, no second list)."""
    return {
        "scope": (mcp_shape_findings(
            catalog_text.replace('SCOPE_DOC = "mcp.doc"', 'SCOPE_DOC = "doc"'),
            gateway_text, handler_text), "doc.upload"),
        "idempotency": (mcp_shape_findings(
            catalog_text.replace(IDEMPOTENCY_OBLIGATION_SENTENCE, "。"),
            gateway_text, handler_text), "doc.upload"),
        "audit": (mcp_shape_findings(
            catalog_text, gateway_text,
            handler_text.replace("recordClient.record(", "recordClient.RECORD(")),
            "doc.upload"),
        "receipt": (mcp_shape_findings(
            catalog_text, gateway_text.replace('"task_id"', '"job_id"'),
            handler_text), "doc.upload"),
        "partner": (mcp_shape_findings(
            catalog_text.replace("提取进度用 doc_task.poll 查询", "提取进度另行查询"),
            gateway_text, handler_text), "doc.upload"),
        "confirm-tool": (mcp_shape_findings(
            catalog_text.replace('"mcp.review.confirm",', '"review.confirm",'),
            gateway_text, handler_text), "script.writeback"),
        "confirm-handle": (mcp_shape_findings(
            catalog_text, gateway_text.replace('"confirm_ref"', '"confirm_token"'),
            handler_text), "script.writeback"),
        "login-demand": (mcp_shape_findings(
            catalog_text.replace("全程无需登录平台界面（平台草案卡", "请登录平台界面确认（平台草案卡"),
            gateway_text, handler_text), "script.writeback"),
    }


def shape_selftest():
    """MCP tool-shape elements only: planted violations must be pointed by name,
    live sources must be quiet. Split from --selftest so this package's verdict is
    provable while the pre-existing disposition ledger debt stays on its owner."""
    try:
        live_catalog = open(MCP_CATALOG, encoding="utf-8").read()
        live_gateway = open(MCP_GATEWAY, encoding="utf-8").read()
        live_handler = open(MCP_HANDLER, encoding="utf-8").read()
    except OSError as ex:
        print("SHAPE-SELFTEST FAIL: MCP sources unreadable:", ex)
        return 1
    planted = planted_shape_phases(live_catalog, live_gateway, live_handler)
    clean = mcp_shape_findings(live_catalog, live_gateway, live_handler)
    rc = 0
    for element, (findings, expect) in planted.items():
        hit = any(f.startswith(f"{expect}:") for f in findings)
        print(f"shape-selftest planted[{element}]: findings={len(findings)} "
              f"pointed={'yes' if hit else 'NO — INERT'}")
        if not hit:
            rc = 1
    print(f"shape-selftest live-clean: findings={len(clean)}")
    for c in clean:
        print("  live:", c)
        rc = 1
    if rc == 0:
        print(f"SHAPE-SELFTEST PASS: {len(planted)} planted violations all pointed, live quiet")
    return rc


def selftest():
    """Two-phase proof: a dirty sample is pointed, the clean run is quiet."""
    ledger = build_ledger()
    dispositions = load_dispositions()
    # Phase 1 (dirty): invent a leaf that has no disposition — must be pointed.
    dirty = dict(ledger)
    dirty["leaves"] = ledger["leaves"] + [
        {"path": "/__selftest_ghost__", "dataTest": "menu-selftest", "perm": None,
         "view": None, "actions": None}]
    gaps, unaccounted = check(dirty, dispositions)
    pointed = "/__selftest_ghost__" in unaccounted
    # The gate must also refuse to pass an inert negative list: known-bad name.
    neg = scan_negative(["config.set", "confirm.auto", "publish.auto"])
    dirty_ok = pointed and len(neg) == 3
    # The meta-tool reconciliation must also be non-inert: a ledger name that
    # shows up on the seeded tp_tool face (simulated here) must be pointed.
    fake = dict(ledger)
    fake["builtinTools"] = ledger["builtinTools"] + [
        {"name": "search_tools", "kind": "FAKE", "origin": "selftest"}]
    meta_dirty = any("search_tools" in p for p in reconcile_meta_tools(fake))
    dirty_ok = dirty_ok and meta_dirty
    # MCP-shape phase : each element gets one planted
    # violation that MUST be pointed by tool name, and the live sources must be
    # quiet — an element no fixture ever trips is an inert detector (R43).
    try:
        live_catalog = open(MCP_CATALOG, encoding="utf-8").read()
        live_gateway = open(MCP_GATEWAY, encoding="utf-8").read()
        live_handler = open(MCP_HANDLER, encoding="utf-8").read()
    except OSError as ex:
        print("SELFTEST FAIL: MCP sources unreadable:", ex)
        return 1
    planted = planted_shape_phases(live_catalog, live_gateway, live_handler)
    shape_clean = mcp_shape_findings(live_catalog, live_gateway, live_handler)
    shape_dirty_ok = all(any(f.startswith(f"{expect}:") for f in findings)
                         for findings, expect in planted.values())
    dirty_ok = dirty_ok and shape_dirty_ok and not shape_clean
    # Phase 2 (clean): live ledger must be fully adjudicated (same rc semantics
    # as --check: gaps, unaccounted leaves, and un-adjudicated TODO rows).
    gaps2, unacc2 = check(ledger, dispositions)
    meta2 = reconcile_meta_tools(ledger)
    todo2 = sum(1 for e in dispositions.values() if e["reason"].startswith("TODO"))
    clean_ok = not gaps2 and not unacc2 and not todo2 and not meta2
    print(f"selftest dirty-phase pointed={pointed} negative-hits={len(neg)} "
          f"meta-dirty-pointed={meta_dirty}")
    print(f"selftest clean-phase gaps={len(gaps2)} unaccounted={len(unacc2)} "
          f"todo={todo2} meta-problems={len(meta2)}")
    for g in gaps2[:10]:
        print("  clean gap:", g)
    for p in unacc2[:10]:
        print("  unaccounted:", p)
    if not dirty_ok:
        print("SELFTEST FAIL: gate is inert — dirty sample not pointed")
        return 1
    if not clean_ok:
        print("SELFTEST FAIL: clean ledger not reconciled (fix dispositions first)")
        return 1
    print("SELFTEST PASS: dirty pointed + clean quiet")
    return 0


def bootstrap(ledger):
    """Write the disposition skeleton from the live ledger, keeping existing rows."""
    old = load_dispositions()
    entries = []
    for lf in ledger["leaves"]:
        prev = old.get(lf["path"])
        if prev:
            entries.append(prev)
            continue
        entries.append({
            "leaf": lf["path"],
            "dataTest": lf["dataTest"],
            "actions": lf["actions"] or [],
            "status": "debt",
            "tool": None,
            "reason": "TODO: adjudicate — map to a callable tool / debt with a named clearing batch / exempt with a written reason",
        })
    payload = {"_comment": "Coverage ledger dispositions (tool_coverage_gate.py). Every menu leaf needs one row: status=mapped (tools covered by registry names), debt (registered repayment with reason), exempt (why no callable tool belongs here, e.g. read-only surface).",
               "entries": entries}
    with open(DISPOSITIONS, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, ensure_ascii=False, indent=2)
        fh.write("\n")
    print(f"bootstrap wrote {len(entries)} dispositions -> {os.path.relpath(DISPOSITIONS, ROOT)}")
    return 0


def main():
    ap = argparse.ArgumentParser(
        description="Tool-surface coverage reconciliation gate (see module docstring)")
    ap.add_argument("--check", action="store_true", help="default mode")
    ap.add_argument("--emit", action="store_true")
    ap.add_argument("--bootstrap", action="store_true")
    ap.add_argument("--selftest", action="store_true")
    ap.add_argument("--shape-selftest", action="store_true",
                    help="two-phase proof for the MCP four-element shape check only")
    ap.add_argument("--db", action="store_true")
    args = ap.parse_args()

    ledger = build_ledger()
    if args.emit:
        out = dict(ledger)
        if args.db:
            out["db"] = db_reconcile()
        print(json.dumps(out, ensure_ascii=False, indent=2))
        return 0
    if args.bootstrap:
        return bootstrap(ledger)
    if args.selftest:
        return selftest()
    if args.shape_selftest:
        return shape_selftest()

    dispositions = load_dispositions()
    if not dispositions:
        print("FAIL: no disposition ledger — run --bootstrap then adjudicate every row")
        return 1
    gaps, unaccounted = check(ledger, dispositions)
    meta_problems = reconcile_meta_tools(ledger)
    shape = []
    try:
        shape = mcp_shape_findings(
            open(MCP_CATALOG, encoding="utf-8").read(),
            open(MCP_GATEWAY, encoding="utf-8").read(),
            open(MCP_HANDLER, encoding="utf-8").read())
    except OSError as ex:
        shape = [f"MCP sources unreadable ({ex}) — shape verdict is UNKNOWN, not green"]
    neg = scan_negative([t["name"] for t in ledger["builtinTools"]]
                        + [e.get("tool") or "" for e in dispositions.values()])
    stats = {
        "leaves": len(ledger["leaves"]),
        "leavesWithoutView": sum(1 for l in ledger["leaves"] if not l["view"]),
        "leafsWithActions": sum(1 for l in ledger["leaves"] if l["actions"]),
        "builtinSeeds": len(ledger["builtinTools"]),
        "dispositions": len(dispositions),
        "mapped": sum(1 for e in dispositions.values() if e["status"] == "mapped"),
        "debt": sum(1 for e in dispositions.values() if e["status"] == "debt"),
        "exempt": sum(1 for e in dispositions.values() if e["status"] == "exempt"),
        "todoReason": sum(1 for e in dispositions.values() if e["reason"].startswith("TODO")),
        "metaTools": len([n for n in meta_tool_names() if n]),
        "mcpShapeFindings": len(shape),
    }
    print("tool-coverage-gate " + json.dumps(stats))
    if args.db:
        print("db-ledger " + json.dumps(db_reconcile()))
    rc = 0
    if meta_problems:
        rc = 1
        print(f"RED: {len(meta_problems)} meta-tool ledger problems:")
        for p in meta_problems:
            print("  meta:", p)
    if unaccounted:
        rc = 1
        print(f"RED: {len(unaccounted)} menu leaf(ves) without disposition:")
        for p in unaccounted:
            print("  unaccounted:", p)
    if gaps:
        rc = 1
        print(f"RED: {len(gaps)} disposition inconsistencies:")
        for g in gaps:
            print("  gap:", g)
    if neg:
        rc = 1
        print(f"RED: evolution negative-list hits ({len(neg)}):")
        for h in neg:
            print("  negative:", h["class"], "<-", h["name"])
    if stats["todoReason"]:
        rc = 1
        print(f"RED: {stats['todoReason']} dispositions still carry the bootstrap TODO reason — adjudicate each")
    if shape:
        rc = 1
        print(f"RED: {len(shape)} MCP tool-shape findings (four-element contract):")
        for s in shape:
            print("  shape:", s)
    if rc == 0:
        print("PASS: every leaf has an adjudicated disposition; negative list clean")
    return rc


if __name__ == "__main__":
    sys.exit(main())
