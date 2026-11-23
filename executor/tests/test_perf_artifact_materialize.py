""" (A-10 executor side): materialize object-storage perf bundles.

Background: since the core adapters return a dispatch handle that is an
object-storage key of a zip bundle (k6 = script + ds_*.json sidecar at zip
root; Gatling = manifest.json + <name>.java + classes/**). The executor must
turn that key back into readable files before the engine subprocess starts:
grant via the api /internal channel (executor never talks to core directly),
download the bundle, extract into a temp dir, rebase manifest pointers.

Pinned spectra:
- k6 bundle: extracted .js path returned, sidecar lands next to the script
 (the k6 template opens dataset files relative to the script directory);
- Gatling bundle: manifest.json classesDir/sourceFile (zip-relative names)
 rewritten to absolute extracted paths, manifest path returned;
- compatibility read: a handle that is an existing local path passes through
 untouched with ZERO network calls (in-flight runs and dev stay unaffected);
- empty handle: passed through (the downstream engine-specific isfile check
 produces its own human-worded failure);
- grant / download failures: fail-closed with a human-worded error naming the
 object-storage stage — never a silent local fallback;
- object key without an injected api base (old runner payload): fail-closed
 pointing at the wiring gap;
- structurally invalid bundle (no script, no manifest): rejected.

mock scope: only the protocol grant channel and the httpx download are faked
(L2 infrastructure isolation); materialization/judgment logic is real code.
"""
from __future__ import annotations

import io
import json
import os
import zipfile

import pytest

import protocol
import httpx

# Captured before the autouse fixture swaps protocol.ExecutorProtocolClient for
# the fake — the wire-format test below pins the genuine client class.
REAL_PROTOCOL_CLIENT = protocol.ExecutorProtocolClient


def _make_zip(entries: dict[str, str]) -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        for name, text in entries.items():
            zf.writestr(name, text)
    return buf.getvalue()


class _FakeClient:
    """Stands in for ExecutorProtocolClient: records requests, returns canned data."""

    instances: list = []
    response: dict = {}
    error: Exception | None = None

    def __init__(self, api_base_url: str, timeout: float = 30.0) -> None:
        self.base = api_base_url
        self.executor_id = None
        self.requests: list[dict] = []
        _FakeClient.instances.append(self)

    def grant_perf_artifact_download(self, object_key: str) -> dict:
        self.requests.append({"objectKey": object_key})
        if _FakeClient.error is not None:
            raise _FakeClient.error
        return _FakeClient.response


class _FakeResponse:
    def __init__(self, content: bytes, status: int = 200) -> None:
        self.content = content
        self.status_code = status

    def raise_for_status(self) -> None:
        if self.status_code >= 400:
            raise httpx.HTTPStatusError("boom", request=None, response=None)  # type: ignore[arg-type]


@pytest.fixture(autouse=True)
def _reset_fakes(monkeypatch):
    _FakeClient.instances = []
    _FakeClient.response = {}
    _FakeClient.error = None
    monkeypatch.setattr(protocol, "ExecutorProtocolClient", _FakeClient)
    yield


@pytest.fixture()
def _no_download(monkeypatch):
    def _fail(*a, **k):
        raise AssertionError("download must not be attempted")

    monkeypatch.setattr(httpx, "get", _fail)


import perf_worker


K6_ZIP = _make_zip({
    "SmokeFlow.js": "export const options = {};\n// k6 script body\n",
    "ds_7.json": '{"columns":["a"],"rows":[["1"]]}',
})
GATLING_ZIP = _make_zip({
    "manifest.json": json.dumps({"fqcn": "com.sixbean.perf.SmokeSim",
                                 "classesDir": "classes",
                                 "sourceFile": "SmokeSim.java"}),
    "SmokeSim.java": "class SmokeSim {}",
    "classes/com/sixbean/perf/SmokeSim.class": "cafebabe",
    "classes/ds_7.csv": "a\n1\n",
})


class TestMaterializeK6Bundle:
    def test_object_key_yields_extracted_script_with_sidecar_beside(self, monkeypatch, tmp_path):
        monkeypatch.setattr(httpx, "get", lambda url, **kw: _FakeResponse(K6_ZIP))
        _FakeClient.response = {"objectKey": "p1/perf-artifact/smoke.zip",
                                "downloadUrl": "http://s3obj.local/presigned"}
        path, err = perf_worker.materialize_artifact(
            "p1/perf-artifact/202610/smoke.zip", "http://api.local", str(tmp_path))
        assert err is None
        assert path and os.path.isfile(path)
        assert path.endswith("SmokeFlow.js")
        # dataset sidecar must sit next to the script (k6 template relative-open contract)
        assert os.path.isfile(os.path.join(os.path.dirname(path), "ds_7.json"))
        # grant channel got the object key verbatim through the api base
        assert _FakeClient.instances[0].base == "http://api.local"
        assert _FakeClient.instances[0].requests == [{"objectKey": "p1/perf-artifact/202610/smoke.zip"}]


class TestMaterializeGatlingBundle:
    def test_manifest_pointers_rebased_to_absolute_extracted_paths(self, monkeypatch, tmp_path):
        monkeypatch.setattr(httpx, "get", lambda url, **kw: _FakeResponse(GATLING_ZIP))
        _FakeClient.response = {"downloadUrl": "http://s3obj.local/presigned"}
        path, err = perf_worker.materialize_artifact(
            "p1/perf-artifact/202610/sim.zip", "http://api.local", str(tmp_path))
        assert err is None
        assert path and os.path.basename(path) == "manifest.json"
        with open(path, encoding="utf-8") as f:
            manifest = json.load(f)
        assert manifest["fqcn"] == "com.sixbean.perf.SmokeSim"
        assert os.path.isabs(manifest["classesDir"])
        assert os.path.isdir(manifest["classesDir"])
        assert manifest["sourceFile"].endswith("SmokeSim.java")
        assert os.path.isabs(manifest["sourceFile"])
        assert os.path.isfile(os.path.join(manifest["classesDir"], "ds_7.csv"))


class TestCompatibilityRead:
    def test_local_existing_path_passes_through_zero_network(self, tmp_path, _no_download):
        local = tmp_path / "legacy-artifact.js"
        local.write_text("old handle")
        path, err = perf_worker.materialize_artifact(str(local), "http://api.local",
                                                     str(tmp_path))
        assert err is None
        assert path == str(local)
        assert _FakeClient.instances == []

    def test_empty_handle_passes_through_zero_network(self, tmp_path, _no_download):
        path, err = perf_worker.materialize_artifact("", "http://api.local", str(tmp_path))
        assert err is None
        assert path == ""
        assert _FakeClient.instances == []


class TestFailClosed:
    def test_grant_channel_error_named_human_worded(self, tmp_path, _no_download):
        _FakeClient.error = protocol.ProtocolError("业务错误 code=403 msg=越权")
        path, err = perf_worker.materialize_artifact(
            "p1/perf-artifact/202610/smoke.zip", "http://api.local", str(tmp_path))
        assert path is None
        assert err and "下载授权" in err and "smoke.zip" in err

    def test_missing_download_url_in_grant_response_is_rejected(self, tmp_path, _no_download):
        _FakeClient.response = {"objectKey": "p1/perf-artifact/202610/smoke.zip"}
        path, err = perf_worker.materialize_artifact(
            "p1/perf-artifact/202610/smoke.zip", "http://api.local", str(tmp_path))
        assert path is None
        assert err and "downloadUrl" in err

    def test_download_failure_names_object_storage_human_worded(self, monkeypatch, tmp_path):
        def _boom(url, **kw):
            raise httpx.ConnectError("connection refused")

        monkeypatch.setattr(httpx, "get", _boom)
        _FakeClient.response = {"downloadUrl": "http://s3obj.local/dead"}
        path, err = perf_worker.materialize_artifact(
            "p1/perf-artifact/202610/smoke.zip", "http://api.local", str(tmp_path))
        assert path is None
        assert err and "下载" in err

    def test_object_key_without_api_base_fails_closed_pointing_at_wiring(self, tmp_path, _no_download):
        path, err = perf_worker.materialize_artifact(
            "p1/perf-artifact/202610/smoke.zip", None, str(tmp_path))
        assert path is None
        assert err and "通道未接线" in err

    def test_bundle_without_recognisable_layout_is_rejected(self, monkeypatch, tmp_path):
        weird = _make_zip({"readme.txt": "no script no manifest"})
        monkeypatch.setattr(httpx, "get", lambda url, **kw: _FakeResponse(weird))
        _FakeClient.response = {"downloadUrl": "http://s3obj.local/presigned"}
        path, err = perf_worker.materialize_artifact(
            "p1/perf-artifact/202610/smoke.zip", "http://api.local", str(tmp_path))
        assert path is None
        assert err and "包内结构" in err


class TestGrantProtocolMessage:
    def test_client_posts_object_key_to_internal_grant_endpoint(self, monkeypatch):
        captured: dict = {}

        def _fake_post(self, path, payload):
            captured["path"] = path
            captured["payload"] = payload
            return {"downloadUrl": "u"}

        monkeypatch.setattr(REAL_PROTOCOL_CLIENT, "_post", _fake_post)
        client = REAL_PROTOCOL_CLIENT("http://api.local")
        data = client.grant_perf_artifact_download("p1/perf-artifact/202610/smoke.zip")
        assert captured["path"] == "/internal/perf/artifacts/download-grant"
        assert captured["payload"] == {"objectKey": "p1/perf-artifact/202610/smoke.zip"}
        assert data["downloadUrl"] == "u"
