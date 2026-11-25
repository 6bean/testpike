#!/usr/bin/env python3
"""Build-time manifest for backend jars (single source, R40).

Window preflight judged artifact freshness on two scales that can disagree:
file mtimes (a checkout or repackage rewrites them without any
content change) versus source history. A successful `mvn package` writes a
sidecar manifest recording HEAD, the build moment and the sha256 of the jar
bytes; readers then compare source changes against that recorded build
moment instead of trusting mtimes. A missing or byte-mismatched manifest
falls back to the conjunctive mtime judgement, always announced - never a
silent second scale.

Usage:
  jar_manifest.py write <repo_root> <module> [module ...]   # after mvn package rc=0
  jar_manifest.py check <repo_root> <module> <jar_path>     # MANIFEST=VALID built_at=N head=H
Exit codes for `check`: 0 valid, 2 no manifest file, 3 manifest does not match bytes.
"""
import hashlib
import json
import os
import subprocess
import sys
import time

SKIP_FRAGMENTS = ("original", "plain", "sources")


def sha256_of(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def git_head(repo_root):
    try:
        probe = subprocess.run(["git", "-C", repo_root, "rev-parse", "HEAD"],
                               capture_output=True, text=True, timeout=30)
        return probe.stdout.strip() or "unknown"
    except Exception:
        return "unknown"


def jar_for(repo_root, module):
    """The runnable artifact of a module: prefer testpike-<module>-*.jar."""
    base = os.path.join(repo_root, module.strip(), "target")
    if not os.path.isdir(base):
        return None
    names = [n for n in os.listdir(base)
             if n.endswith(".jar") and not any(f in n for f in SKIP_FRAGMENTS)]
    if not names:
        return None
    named = [n for n in names if n.startswith(f"testpike-{module}-")] or names
    newest = max(named, key=lambda n: os.stat(os.path.join(base, n)).st_mtime)
    return os.path.join(base, newest)


def record(repo_root, jar_path, module):
    """Write <jar>.manifest pinning HEAD x module x sha256 at this moment."""
    rec = {"module": module, "head": git_head(repo_root),
           "built_at": int(time.time()), "sha256": sha256_of(jar_path)}
    with open(jar_path + ".manifest", "w") as handle:
        json.dump(rec, handle, indent=1)
    return rec


def load(jar_path):
    """Return the manifest dict iff the file exists AND its sha256 matches
    the bytes on disk (a tampered/republished jar must read as absent,
    never as stale evidence). Otherwise None."""
    try:
        with open(jar_path + ".manifest") as handle:
            rec = json.load(handle)
    except Exception:
        return None
    if not isinstance(rec, dict) or not rec.get("sha256"):
        return None
    try:
        return rec if rec["sha256"] == sha256_of(jar_path) else None
    except Exception:
        return None


def main(argv):
    if len(argv) >= 3 and argv[1] == "write":
        repo_root = os.path.abspath(argv[2])
        rc = 0
        head_shown = False
        for module in argv[3:]:
            jar = jar_for(repo_root, module)
            if not jar:
                print(f"MANIFEST-SKIP {module}: no jar under {module}/target")
                rc = 1
                continue
            rec = record(repo_root, jar, module)
            print(f"MANIFEST-WROTE {module} head={rec['head'][:9]} "
                  f"built_at={rec['built_at']} jar={os.path.basename(jar)}")
            head_shown = True
        return rc if head_shown else 1
    if len(argv) >= 4 and argv[1] == "check":
        repo_root, module, jar_path = argv[2], argv[3], argv[4] if len(argv) > 4 else ""
        if not jar_path:
            jar_path = jar_for(repo_root, module) or ""
        if not jar_path or not os.path.exists(jar_path):
            print("MANIFEST=ABSENT no-artifact")
            return 2
        if not os.path.exists(jar_path + ".manifest"):
            print("MANIFEST=ABSENT no-manifest-file")
            return 2
        rec = load(jar_path)
        if rec is None:
            print("MANIFEST=INVALID bytes-or-json-mismatch")
            return 3
        if rec.get("module") != module:
            print(f"MANIFEST=INVALID module-claim {rec.get('module')}")
            return 3
        print(f"MANIFEST=VALID built_at={rec['built_at']} head={str(rec['head'])[:9]}")
        return 0
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
