"""Contracts for the executor dependency split (image build root cause).

Why these tests exist: a clean `pip install -r executor/requirements.txt`
is structurally impossible while the mini-program engine client (minium,
needs websocket-client<1.4) shares one flat list with the WEB_DRIVER
clients (selenium>=4.27 needs websocket-client>=1.8). The executor image
build and the README quickstart both consumed that single list, so both
hit pip ResolutionImpossible. The shipped-image boundary (see
Dockerfile.executor header) is honest degradation: API cases run out of
the box, device/web engines report a clear runtime error. The device
engine clients therefore live in their own install surface, documented
for the per-engine venv layout installkit uses on real hosts.
"""

from pathlib import Path

import re

REPO = Path(__file__).resolve().parents[2]
REQUIREMENTS = REPO / "executor" / "requirements.txt"
REQUIREMENTS_ENGINES = REPO / "executor" / "requirements-engines.txt"
DOCKERFILE_EXECUTOR = REPO / "deploy" / "docker" / "Dockerfile.executor"

# Packages whose websocket-client ranges are mutually exclusive when
# flattened into one environment (proven by the failed image build).
CONFLICTING_ENGINE_PACKAGES = ("minium", "selenium", "Appium-Python-Client")


def _pinned_names(text: str):
    names = set()
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        m = re.match(r"^([A-Za-z0-9_.\-]+)", line)
        if m:
            names.add(m.group(1).lower())
    return names


def test_core_requirements_excludes_conflicting_engine_clients():
    """requirements.txt must be installable as one flat environment."""
    names = _pinned_names(REQUIREMENTS.read_text(encoding="utf-8"))
    for pkg in CONFLICTING_ENGINE_PACKAGES:
        assert pkg.lower() not in names, (
            f"{pkg} conflicts with the rest of the flat install set "
            "(websocket-client range collision); move it to "
            "requirements-engines.txt"
        )


def test_engine_requirements_file_carries_the_device_engine_clients():
    """The excluded engine clients must stay declared, not silently dropped."""
    assert REQUIREMENTS_ENGINES.exists(), (
        "device engine clients need an install surface of their own"
    )
    names = _pinned_names(REQUIREMENTS_ENGINES.read_text(encoding="utf-8"))
    for pkg in CONFLICTING_ENGINE_PACKAGES:
        assert pkg.lower() in names, f"requirements-engines.txt lost {pkg}"


def test_engine_requirements_documents_the_separate_venv_boundary():
    text = REQUIREMENTS_ENGINES.read_text(encoding="utf-8")
    assert "venv" in text.lower(), (
        "the file must explain that these clients install into a "
        "per-engine venv, never the shared one"
    )
    core = REQUIREMENTS.read_text(encoding="utf-8")
    assert "requirements-engines.txt" in core, (
        "requirements.txt must point readers to the engine overlay file"
    )


def test_executor_image_installs_only_the_resolvable_core_set():
    """The delivered image stays API-capable and honest about boundaries."""
    df = DOCKERFILE_EXECUTOR.read_text(encoding="utf-8")
    assert "requirements.txt" in df
    assert "requirements-engines.txt" not in df, (
        "baking conflicting engine clients into one image layer is "
        "structurally impossible and outside the documented boundary"
    )
