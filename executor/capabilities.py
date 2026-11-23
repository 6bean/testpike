
from __future__ import annotations

import os
import platform
import shutil
import subprocess
from typing import Callable, Optional

ENGINE_REQUIREMENTS: dict = {
    "MOBILEWRIGHT": {"tools": ["mobilecli"], "runtimes": {"node": "22.12"}},
    "MAESTRO": {"tools": ["maestro"], "runtimes": {"java": "17"}},
    "APPIUM": {"tools": ["appium"], "runtimes": {"node": "20"}},
    "XCUITEST": {"tools": ["appium", "xcrun"], "runtimes": {"node": "20"},
                 "host_os": "macos"},
    "MINIPROGRAM_AUTOMATOR": {"tools": ["wx_devtools_cli"], "runtimes": {"node": "18"}},
    "MINIUM": {"python_imports": ["minium"]},
    "HMNEXTAUTO": {"tools": ["hdc"], "python_imports": ["hmdriver2"]},
    # Load engines (process boundary, AGPL bodies are user-installed): the
    # prerequisites mirror exactly what perf_worker consumes at run time —
    # K6 needs the k6 binary on PATH; GATLING needs java>=17 plus GATLING_LIB
    # pointing at an existing engine-jars directory. Missing either = engine
    # stays unreported; a false claim would dispatch a run onto a machine
    # that can only fail it.
    "K6": {"tools": ["k6"]},
    "GATLING": {"env_dirs": ["GATLING_LIB"], "runtimes": {"java": "17"}},
    # JMETER (Apache-2.0, user-installed binary, process boundary): the worker
    # consumes nothing beyond the `jmeter` launcher, which brings its own JVM
    # discovery — declaring only the real runtime fact keeps the report honest.
    "JMETER": {"tools": ["jmeter"]},
}


def _run_version_cmd(exe: str, flag: str = "--version") -> Optional[str]:
    pass
    try:
        out = subprocess.run([exe, flag], capture_output=True, text=True,
                             timeout=5)
        if out.returncode != 0:
            return None
        return (out.stdout or out.stderr)[:200]
    except (OSError, subprocess.SubprocessError):
        return None


def _java_version_text(exe: str) -> Optional[str]:
    pass
    try:
        out = subprocess.run([exe, "-version"], capture_output=True, text=True,
                             timeout=5)
        if out.returncode != 0:
            return None
        return (out.stdout or "") + (out.stderr or "")
    except (OSError, subprocess.SubprocessError):
        return None


def _first_version(text: Optional[str]) -> Optional[str]:
    pass
    if not text:
        return None
    import re
    m = re.search(r"(\d+(?:\.\d+){1,2})", text)
    return m.group(1) if m else None


def _version_ge(actual: Optional[str], required: str) -> bool:
    pass
    if not actual:
        return False

    def parts(v: str) -> list:
        seg = v.split("-")[0].split(".")
        out = []
        for s in seg:
            try:
                out.append(int(s))
            except ValueError:
                out.append(0)
        return out

    a, r = parts(actual), parts(required)
    n = max(len(a), len(r))
    a += [0] * (n - len(a))
    r += [0] * (n - len(r))
    return a >= r


def _import_ok(name: str) -> bool:
    import importlib.util
    try:
        return importlib.util.find_spec(name) is not None
    except (ImportError, ValueError):
        return False


def _wx_devtools_cli() -> Optional[str]:
    pass
    p = os.environ.get("WX_DEVTOOLS_CLI_PATH", "").strip()
    return p if p and os.path.exists(p) else None


def kvm_usable(dev_path: str = "/dev/kvm") -> bool:
    """True only when the KVM node exists AND this user can read/write it.

    Bare existence overstates the emulator capability: the node is mode
    660 root:kvm, so a user outside the kvm group gets EACCES on open and
    the host can never boot an AVD. Declaring it would let the scheduler
    dispatch emulator work here and fail every lease — the honest-absence
    posture of this module (probe failure = false, never a lie) applies.
    """
    return (os.path.exists(dev_path)
            and os.access(dev_path, os.R_OK | os.W_OK))


def host_os_name() -> str:
    s = platform.system().lower()
    return {"darwin": "macos", "windows": "windows"}.get(s, s or "unknown")


def probe_capabilities(which: Callable = shutil.which,
                       run_version: Callable = _run_version_cmd,
                       host_os: Optional[str] = None) -> dict:
    pass
    os_name = host_os or host_os_name()
    tool_names = ["adb", "mobilecli", "maestro", "appium", "hdc", "xcrun", "k6"]
    # Probe every tool that any engine prerequisite declares (single source =
    # ENGINE_REQUIREMENTS); a hand-list that drifts from the requirement map
    # silently hides installed engines — the JMETER probe-row miss of the
    # third load engine was exactly this defect.
    for _req in ENGINE_REQUIREMENTS.values():
        for _t in _req.get("tools", []):
            if _t not in tool_names:
                tool_names.append(_t)
    found = {t: (which(t) is not None) for t in tool_names}
    wx_cli = _wx_devtools_cli()
    found["wx_devtools_cli"] = wx_cli is not None

    runtimes = {}
    for rt, exe in (("node", "node"), ("python", "python3")):
        runtimes[rt] = _first_version(run_version(exe)) if which(exe) else None
    runtimes["java"] = _first_version(_java_version_text("java")) \
        if which("java") else None

    kvm_present = kvm_usable()
    engines = []
    for code, req in ENGINE_REQUIREMENTS.items():
        if req.get("host_os") and req["host_os"] != os_name:
            continue
        if any(not found.get(t, False) for t in req.get("tools", [])):
            continue
        if any(not _import_ok(m) for m in req.get("python_imports", [])):
            continue
        # env_dirs = install-time environment declarations (e.g. GATLING_LIB):
        # unset value or missing directory = prerequisite unmet, honest absence
        # (same posture as wx_devtools_cli above).
        if any(not (os.environ.get(d) or "").strip()
               or not os.path.isdir((os.environ.get(d) or "").strip())
               for d in req.get("env_dirs", [])):
            continue
        ok = True
        for rt, min_v in (req.get("runtimes") or {}).items():
            if not _version_ge(runtimes.get(rt), min_v):
                ok = False
                break
        if ok:
            engines.append(code)

    platforms_ = []
    if found["adb"] or "MOBILEWRIGHT" in engines or "MAESTRO" in engines:
        platforms_.append("ANDROID")
    if os_name == "macos":
        platforms_.append("IOS")
    if found["wx_devtools_cli"]:
        platforms_.append("MP")
    if found["hdc"]:
        platforms_.append("HARMONY_NEXT")
    if found["adb"]:
        platforms_.append("QUICKAPP")

    device_kinds = []
    if found["adb"] or kvm_present:
        device_kinds.append("EMULATOR")
    if os_name == "macos":
        device_kinds.append("SIMULATOR")
    if found["adb"]:
        device_kinds.append("REAL")
    if found["wx_devtools_cli"]:
        device_kinds.append("DEVTOOLS_IDE")

    return {
        "hostOs": os_name,
        "tools": found,
        "runtimes": runtimes,
        "engines": sorted(engines),
        "platforms": platforms_,
        "deviceKinds": device_kinds,
        "kvm": kvm_present,
    }



_COMPONENT_TOOL_FAMILIES = [
    ("engine-runtime.node", "node", "--version"),
    ("engine-runtime.mobilecli", "mobilecli", "--version"),
    ("engine-runtime.maestro", "maestro", "--version"),
    ("engine-runtime.appium", "appium", "--version"),
    ("engine-runtime.hdc", "hdc", "-v"),
    ("engine-runtime.k6", "k6", "--version"),
]

_COMPONENT_PKG_FAMILIES = [
    ("engine-runtime.minium", "minium"),
]

# Gatling engine-jars directory variable (perf_worker consumes the same key —
# single source, no second vocabulary).
GATLING_LIB_ENV = "GATLING_LIB"
_GATLING_APP_JAR_PREFIX = "gatling-app-"


def _gatling_lib_version() -> Optional[str]:
    """Gatling version stamped in the gatling-app-<ver>.jar filename inside
    GATLING_LIB (the shipped jar name is the only trustworthy version tag;
    missing directory or jar = None, never guess)."""
    lib = (os.environ.get(GATLING_LIB_ENV) or "").strip()
    if not lib or not os.path.isdir(lib):
        return None
    try:
        for entry in os.scandir(lib):
            name = os.path.basename(entry.path)
            if name.startswith(_GATLING_APP_JAR_PREFIX) and name.endswith(".jar"):
                return _first_version(name[len(_GATLING_APP_JAR_PREFIX):])
    except OSError:
        return None
    return None


def _pkg_version(name: str) -> Optional[str]:
    pass
    from importlib import metadata
    try:
        return metadata.version(name)
    except Exception:
        return None


def collect_components(which: Callable = shutil.which,
                       run_version: Callable = _run_version_cmd,
                       pkg_version: Callable = _pkg_version,
                       executor_version: Optional[str] = None) -> list:
    pass
    rows = []

    def add(family: str, version: Optional[str]) -> None:
        if version:
            rows.append({"family": family, "currentVersion": version})

    for family, exe, flag in _COMPONENT_TOOL_FAMILIES:
        if which(exe) is None:
            continue
        add(family, _first_version(run_version(exe, flag)))

    for family, pkg in _COMPONENT_PKG_FAMILIES:
        if not _import_ok(pkg):
            continue
        add(family, pkg_version(pkg))

    # Gatling has no version CLI (jars-directory form) — read the stamp off
    # the gatling-app jar filename instead.
    add("engine-runtime.gatling", _gatling_lib_version())

    add("executor-agent", _first_version(executor_version))
    return rows
