"""Bilateral key contract gate: elementAnchors envelope, Java ⇄ Python.

The dispatch envelope crosses a language boundary (core serialises Java
records, the executor consumes dicts). Field renames on either side used to
hide in prose (the gen-dialect ledger lesson) — this test reads the actual
record component names out of the Java sources and asserts they equal the
constants the executor looks up, in both directions (rename = red here, not
a silent zero-hit at runtime).
"""
from __future__ import annotations

import re
from pathlib import Path

from driver.webdriver import WebDriverExecutor

REPO_ROOT = Path(__file__).resolve().parents[2]
JAVA_DT = REPO_ROOT / "core/src/main/java/com/sixbean/testpike/core/webelement/api/dto"


def _record_components(java_file: Path) -> set[str]:
    """Extract the compact-constructor component names of a Java record.

    Generic types like ``Map<String, List<ElementSnapshot>>`` contain commas,
    so the parameter block is split at depth 0 over <>/() only.
    """
    text = java_file.read_text(encoding="utf-8")
    match = re.search(r"public record \w+\((.*?)\)\s*\{", text, re.DOTALL)
    assert match, f"no record header found in {java_file.name}"
    segment, depth, params = [], 0, []
    for ch in match.group(1):
        if ch in "<(":
            depth += 1
        elif ch in ">)":
            depth -= 1
        if ch == "," and depth == 0:
            params.append("".join(segment))
            segment = []
        else:
            segment.append(ch)
    params.append("".join(segment))
    names = set()
    for param in params:
        tokens = param.strip().split()
        if tokens:
            names.add(tokens[-1])
    return names


def test_envelope_binding_keys_match():
    assert set(WebDriverExecutor.ENVELOPE_KEYS) == _record_components(
        JAVA_DT / "ElementAnchorsClaimBinding.java")


def test_snapshot_row_keys_match():
    assert set(WebDriverExecutor.SNAPSHOT_KEYS) == _record_components(
        JAVA_DT / "ElementSnapshot.java")


def test_anchor_keys_match():
    assert set(WebDriverExecutor.ANCHOR_KEYS) == _record_components(
        JAVA_DT / "WebElementAnchorInput.java")


def test_anchor_hit_row_keys_match():
    """WE 3b: the result-side hit row key set ⇄ Java input record.

    The executor reports one row per anchor attempt on the result envelope
    (elementAnchorHits); core deserializes them into WebElementAnchorHitInput.
    A rename on either side must turn this gate red, not silently drop the
    column at runtime (gen-dialect ledger lesson, bilateral pin).
    """
    assert set(WebDriverExecutor.ANCHOR_HIT_ROW_KEYS) == _record_components(
        JAVA_DT / "WebElementAnchorHitInput.java")


def test_heal_evidence_row_keys_match():
    """Result-side self-heal evidence rows ⇄ core writeback record.

    The executor appends one row per direct-heal event (03 protocol 2.4
    trailing key stepHealEvidence, built by driver.re_anchor.build_evidence);
    core deserializes them into StepHealEvidenceInput before storing the
    evidence plane. A rename on either side must turn this gate red instead
    of silently dropping the column at runtime (bilateral pin precedent —
    the executor constant is the only key source, no second copy).
    """
    from driver.re_anchor import HEAL_EVIDENCE_ROW_KEYS
    assert set(HEAL_EVIDENCE_ROW_KEYS) == _record_components(
        REPO_ROOT / "core/src/main/java/com/sixbean/testpike/core/"
                    "webelement/api/dto/StepHealEvidenceInput.java")


def test_anchor_hit_rows_emit_contract_keys():
    """_record_anchor_hit output keys equal the pinned contract set exactly."""
    ex = WebDriverExecutor.__new__(WebDriverExecutor)
    ex._element_anchor_hits = []
    ex._VIEWPORT_LABEL = "1440x900"
    ex._ANCHOR_HIT_REPORT_MAX = 200
    WebDriverExecutor._record_anchor_hit(
        ex, {"elementId": 7}, "登录按钮", "css", hit=True, tried=True)
    assert set(ex._element_anchor_hits[0]) == set(
        WebDriverExecutor.ANCHOR_HIT_ROW_KEYS)


def test_anchor_type_whitelist_matches_service_constant():
    """V544 value domain ⇄ service whitelist ⇄ executor ANCHOR_TYPES."""
    service = (REPO_ROOT / "core/src/main/java/com/sixbean/testpike/core/"
               "webelement/domain/service/impl/WebElementServiceImpl.java"
               ).read_text(encoding="utf-8")
    match = re.search(r"Set\.of\((.*?)\);", service, re.DOTALL)
    assert match, "anchor-type whitelist constant not found in WebElementServiceImpl"
    java_types = set(re.findall(r'"([a-z]+)"', match.group(1)))
    assert java_types == set(WebDriverExecutor.ANCHOR_TYPES)


def test_role_and_label_anchor_types_registered_on_both_sides():
    """ (design-drill correction): role/label are the most stable anchor
 classes Playwright recommends; the ledger value domain must accept them on
 the core whitelist AND the executor side. New types enter, old types drift
 zero (css/xpath/text/placeholder/alt/testid/rel stay)."""
    service = (REPO_ROOT / "core/src/main/java/com/sixbean/testpike/core/"
               "webelement/domain/service/impl/WebElementServiceImpl.java"
               ).read_text(encoding="utf-8")
    match = re.search(r"Set\.of\((.*?)\);", service, re.DOTALL)
    java_types = set(re.findall(r'"([a-z]+)"', match.group(1)))
    assert {"role", "label"} <= java_types
    assert {"role", "label"} <= set(WebDriverExecutor.ANCHOR_TYPES)
    assert {"css", "xpath", "text", "placeholder", "alt", "testid",
            "rel"} <= java_types  # old-domain zero drift


def test_structural_prior_order_matches_revised_design():
    """Prior table equals the revised ordering (testid≈role ≥ label≈
    placeholder/text > css > xpath > rel), read from the single Java source."""
    snapshot = (REPO_ROOT / "core/src/main/java/com/sixbean/testpike/core/"
                "webelement/domain/component/WebElementDispatchSnapshot.java"
                ).read_text(encoding="utf-8")
    block = re.search(r"Map\.of\((.*?)\);", snapshot, re.DOTALL)
    assert block, "TYPE_PRIOR map not found in WebElementDispatchSnapshot"
    priors = {k: float(v) for k, v in
              re.findall(r'"(\w+)",\s*([0-9.]+)', block.group(1))}
    for needed in ("testid", "role", "label", "placeholder", "text",
                   "css", "xpath", "rel"):
        assert needed in priors, f"prior entry missing for {needed}"
    assert priors["testid"] == priors["role"]
    assert priors["role"] >= priors["label"]
    assert priors["label"] >= max(priors["placeholder"], priors["text"])
    assert min(priors["label"], priors["placeholder"], priors["text"]) > \
        priors["css"] > priors["xpath"] > priors["rel"]


def test_ledger_resolver_routes_role_and_label():
    """Envelope anchor resolution grows get_by_role / get_by_label branches
    (native Playwright, no new wheel); role values carry the accessible name
    after a colon, matching the inline target role:NAME form."""
    calls = []

    class _StubPage:
        def get_by_role(self, role, name=None):
            calls.append(("role", role, name))
            return "role-locator"

        def get_by_label(self, text):
            calls.append(("label", text))
            return "label-locator"

    ex = WebDriverExecutor.__new__(WebDriverExecutor)
    page = _StubPage()
    assert WebDriverExecutor._anchor_locator(ex, page, "role", "button:登录按钮") \
        == "role-locator"
    assert WebDriverExecutor._anchor_locator(ex, page, "label", "用户名") \
        == "label-locator"
    assert calls == [("role", "button", "登录按钮"), ("label", "用户名")]


# ---------------------------------------------------------------------------
# Re-anchor evidence-grant channel (piece 2b): the compare port crosses four
# surfaces — core controller mapping, api passthrough client, api protocol
# entrance and the executor protocol literal — plus one injection point in
# the per-task worker. A path drift or a missing wiring used to surface only
# as a runtime zero-hit (the heal silently degraded); these contracts pin the
# literals and the port behaviour bilaterally instead.

API_CTRL = (REPO_ROOT / "api/src/main/java/com/sixbean/testpike/api/executor/"
            "api/controller/ExecutorProtocolController.java")
API_REMOTE = (REPO_ROOT / "api/src/main/java/com/sixbean/testpike/api/executor/"
              "infrastructure/remote/client/RestCoreExecutorClient.java")
CORE_CTRL = (REPO_ROOT / "core/src/main/java/com/sixbean/testpike/core/"
             "webelement/api/controller/WebElementInternalController.java")
PROTO_PY = REPO_ROOT / "executor/protocol.py"
GRANT_LITERALS = ("found", "screenshotKey", "url", "revision")


def _class_base(java_file: Path) -> str:
    text = java_file.read_text(encoding="utf-8")
    match = re.search(r'@RequestMapping\("([^"]+)"\)', text)
    assert match, f"no class @RequestMapping in {java_file.name}"
    return match.group(1)


def _post_literal(java_file: Path, marker: str) -> str:
    text = java_file.read_text(encoding="utf-8")
    assert marker in text, f"{marker!r} absent from {java_file.name}"
    before = text[:text.index(marker)]
    matches = re.findall(r'@PostMapping\("([^"]+)"\)', before)
    assert matches, f"no @PostMapping above {marker!r} in {java_file.name}"
    return matches[-1]


def test_reanchor_grant_path_aligned_across_surfaces():
    """protocol.py literal == api entrance mapping == core full path."""
    proto = PROTO_PY.read_text(encoding="utf-8")
    match = re.search(
        r"def get_reanchor_baseline\(.*?self\._post\(\"([^\"]+)\"",
        proto, re.DOTALL)
    assert match, "get_reanchor_baseline / _post literal missing in protocol.py"
    protocol_path = match.group(1)

    api_path = _class_base(API_CTRL) + _post_literal(
        API_CTRL, "elementEvidenceGrant")
    assert protocol_path == api_path, (
        f"executor path {protocol_path!r} drifted from api entrance {api_path!r}")

    core_path = _class_base(CORE_CTRL) + _post_literal(
        CORE_CTRL, "evidenceGrant(")
    remote = API_REMOTE.read_text(encoding="utf-8")
    assert f'"{core_path}"' in remote, (
        f"api passthrough client does not forward core path {core_path!r}")


def test_grant_view_components_stay_channel_keys():
    """The port consumes exactly the record's data-plane key set."""
    grant_view = (JAVA_DT / "ElementEvidenceGrantView.java")
    assert set(GRANT_LITERALS) == _record_components(grant_view)


def test_visual_compare_port_degrades_to_unprovable(tmp_path, monkeypatch):
    """found=false / url=null / download failure / diff error => None.

    None means "unprovable" — the total-miss gate then files a proposal
    instead of a silent direct heal (red line 1: no implicit pass).
    """
    import task_worker

    class _Client:
        def __init__(self, grant):
            self.grant = grant

        def get_reanchor_baseline(self, element_id):
            return self.grant

    class _Ex:
        def _download_baseline(self, url, key):
            return b"baseline-bytes"

    ok_grant = {"found": True, "screenshotKey": "k1",
                "url": "http://minio/obj", "revision": 3}
    monkeypatch.setattr(task_worker, "diff_ratio",
                        lambda b, c: 0.25, raising=False)
    port = task_worker.build_visual_compare_port(_Ex(), _Client(ok_grant))
    assert port(7, b"shot") == 0.75

    assert task_worker.build_visual_compare_port(
        _Ex(), _Client({"found": False})) (7, b"shot") is None
    assert task_worker.build_visual_compare_port(
        _Ex(), _Client({"found": True, "url": None})) (7, b"shot") is None

    class _BoomDownload(_Ex):
        def _download_baseline(self, url, key):
            raise RuntimeError("presign expired")
    assert task_worker.build_visual_compare_port(
        _BoomDownload(), _Client(ok_grant)) (7, b"shot") is None

    def _size_mismatch(baseline, current):
        raise ValueError("size mismatch")
    monkeypatch.setattr(task_worker, "diff_ratio", _size_mismatch)
    assert task_worker.build_visual_compare_port(
        _Ex(), _Client(ok_grant)) (7, b"shot") is None


def test_protocol_grant_business_error_surfaces_as_none(monkeypatch):
    """ProtocolError from the channel is unprovable, never a crash of the run."""
    import task_worker
    from protocol import ProtocolError

    class _BoomClient:
        def get_reanchor_baseline(self, element_id):
            raise ProtocolError("service down")

    port = task_worker.build_visual_compare_port(_DummyEx(), _BoomClient())
    assert port(7, b"shot") is None


class _DummyEx:
    def _download_baseline(self, url, key):
        return b""


def test_task_worker_injects_port_after_construction():
    """The worker wires the closure post-construction (channel keys are
    construction-time baselines; the port needs the live protocol client)."""
    source = (REPO_ROOT / "executor/task_worker.py").read_text(encoding="utf-8")
    assert "executor._visual_compare = build_visual_compare_port(" in source


# ---------------------------------------------------------------------------
# WE-S4 P2c: survey viewport-tier field across the Java ⇄ executor boundary
# ---------------------------------------------------------------------------

JAVA_TIERS = (REPO_ROOT / "core/src/main/java/com/sixbean/testpike/core"
              "/webelement/domain/component/SurveyEnvelopeTiers.java")


def test_survey_tiers_field_key_matches():
    """Core attach constant ⇄ executor step key (rename = red here, both sides)."""
    from driver.element_candidates import VIEWPORT_CLASSES_STEP_KEY

    text = JAVA_TIERS.read_text(encoding="utf-8")
    match = re.search(r'ENVELOPE_TIERS_FIELD\s*=\s*"([^"]+)"', text)
    assert match, f"ENVELOPE_TIERS_FIELD constant missing in {JAVA_TIERS.name}"
    assert match.group(1) == VIEWPORT_CLASSES_STEP_KEY


def test_survey_tiers_config_key_matches_dispatch_side():
    """Install key literal ⇄ dispatch goal-declaration consumer (single source)."""
    text = JAVA_TIERS.read_text(encoding="utf-8")
    match = re.search(r'VIEWPORT_CLASSES_KEY\s*=\s*"([^"]+)"', text)
    assert match, f"VIEWPORT_CLASSES_KEY constant missing in {JAVA_TIERS.name}"
    dispatch = (REPO_ROOT / "core/src/main/java/com/sixbean/testpike/core"
                "/webelement/domain/component/WebElementSurveyDispatch.java")
    assert "SurveyEnvelopeTiers.VIEWPORT_CLASSES_KEY" in dispatch.read_text(encoding="utf-8")


def test_explore_worker_forwards_tiers_via_shared_key():
    """The transfer point must consume the driver constant, never a literal copy."""
    source = (REPO_ROOT / "executor/explore_worker.py").read_text(encoding="utf-8")
    assert "VIEWPORT_CLASSES_STEP_KEY" in source
