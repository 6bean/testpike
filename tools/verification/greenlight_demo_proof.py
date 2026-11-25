# -*- coding: utf-8 -*-
"""Greenlight proof for a freshly installed stack (open-delivery batch T293d).

Promoted from the one-off scratch tool used in the image-window rounds. The
consumer artifact resolves its targets through the demo seed's own codes and
names — the scratch version pinned two numeric row ids from one slot's database
and broke the moment a clean install numbered rows differently. The operator password comes from ADMIN_INIT_PASSWORD only (never
inline, never echoed); the outcome prints one GREENLIGHT-PROOF anchor line so
window pollers can read a cell verdict without parsing prose.

Usage: ADMIN_INIT_PASSWORD=*** python3 \
    tools/verification/greenlight_demo_proof.py [--center-code demo-order]
"""
import os
import sys
import time

_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(_ROOT, "installkit"))

import httpx  # noqa: E402

import demo_seed  # noqa: E402  (single source for demo codes/names and envelope helpers)

_NEED = demo_seed._need
_ROWS = demo_seed._rows

# Executor capability modes declared by the caller (tier single source:
# installkit/resource_tiers.COMPONENTS["executor"]["tiers"]). "required" is
# the strict verdict used by L4/mainline; "standby-ok" is used by lightweight
# and standard clean-machine cells, which install without an executor, so a
# run that stays queued is reported as an honest standby verdict instead of
# burning the wait budget on a capability the tier never ships (r47 lesson).
EXECUTOR_REQUIRED = "required"
EXECUTOR_STANDBY_OK = "standby-ok"
STATUS_STANDBY = "STANDBY"
STANDBY_GUIDE = ("note: dispatch chain verified (the run is queued). Case execution is owned by the executor,"
                                  " which ships with the full-feature tier; once installed, the queued run reaches a terminal state on its own:"
                 "docker compose --profile executor up -d")


def login(c, user):
    pw = os.environ.get("ADMIN_INIT_PASSWORD")
    if not pw:
        raise SystemExit("missing env var ADMIN_INIT_PASSWORD (install-time admin initial password; injected from the environment only)")
    data = _NEED(c.post("/auth/login", json={"username": user, "password": pw}), "login")
    return {"Authorization": "Bearer " + data["accessToken"]}


def resolve_targets(c, h, center_code="demo-order"):
    """Walk projects→envs→collections→requests by demo names; return (request_id, env_id)."""
    spec = next((s for s in demo_seed.DEMO_CENTER_PROJECTS if s["code"] == center_code), None)
    if spec is None:
        raise SystemExit("unknown demo center code: %s (see the center project table in installkit/demo_seed.py)" % center_code)
    projects = _ROWS(_NEED(c.get("/projects", headers=h, params={"size": 100}), "project list"))
    proj = next((p for p in projects if p.get("code") == center_code), None)
    if proj is None:
        raise SystemExit("project %s is missing — run seed-demo first (installkit/demo_seed.py)" % center_code)
    envs = _ROWS(_NEED(c.get("/projects/%s/envs" % proj["id"], headers=h), "env list"))
    env = next((e for e in envs if e.get("name") == demo_seed.DEMO_ENV_NAME), None)
    if env is None:
        raise SystemExit("project %s has no demo env %s — seeding incomplete; re-run seed-demo before proving"
                         % (center_code, demo_seed.DEMO_ENV_NAME))
    colls = _ROWS(_NEED(c.get("/apitest/collections", headers=h,
                              params={"projectId": proj["id"]}), "collection list"))
    coll = next((x for x in colls if x.get("name") == demo_seed.DEMO_CENTER_COLLECTION_NAME), None)
    if coll is None:
        raise SystemExit("collection %s is missing — seeding incomplete; re-run seed-demo before proving"
                         % demo_seed.DEMO_CENTER_COLLECTION_NAME)
    reqs = _ROWS(_NEED(c.get("/apitest/requests", headers=h,
                            params={"collectionId": coll["id"]}), "request list"))
    want = demo_seed.center_request_plan(spec)["name"]
    req = next((r for r in reqs if r.get("name") == want), None)
    if req is None:
        raise SystemExit("demo request %s is missing — seeding incomplete; re-run seed-demo before proving" % want)
    return req["id"], env["id"]


def run_and_wait(c, h, request_id, env_id, max_wait_s=120,
                 executor=EXECUTOR_REQUIRED):
    """Trigger one fixture request run and wait for its terminal status.

    executor="standby-ok" (lightweight/standard cells): a run still queued
    after the wait budget is the expected standby verdict for a tier without
    an executor, reported as STATUS_STANDBY with the install-guide line.
    """
    data = _NEED(c.post("/apitest/runs", headers=h,
                        json={"requestId": request_id, "envId": env_id}), "start run")
    run_id = data.get("id") if isinstance(data, dict) else data
    # one authoritative status read before the wait budget, so a zero budget
    v = _NEED(c.get("/apitest/runs/%s" % run_id, headers=h), "run detail") or {}
    st = v.get("status")
    deadline = time.time() + max_wait_s
    while st not in (1, 2, 3) and time.time() < deadline:
        time.sleep(3)
        v = _NEED(c.get("/apitest/runs/%s" % run_id, headers=h), "run detail") or {}
        st = v.get("status")
        if st in (1, 2, 3):
            print("GREENLIGHT-PROOF run=%s status=%s duration_ms=%s"
                  % (run_id, st, v.get("durationMs")))
            if st != 1:
                print("GREENLIGHT-PROOF-ERROR-MSG= %s" % (v.get("errorMsg") or "")[:200])
            return st
    if executor == EXECUTOR_STANDBY_OK and st == 0:
        print("GREENLIGHT-PROOF run=%s status=%s" % (run_id, STATUS_STANDBY))
        print("GREENLIGHT-PROOF-STANDBY-GUIDE= %s" % STANDBY_GUIDE)
        return STATUS_STANDBY
    print("GREENLIGHT-PROOF run=%s status=TIMEOUT last=%s" % (run_id, st))
    return None


def main(argv=None):
    import argparse
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--base", default=os.environ.get("TP_ADMIN_BASE", "http://localhost:8481"))
    ap.add_argument("--user", default=os.environ.get("ADMIN_INIT_USER", "admin"))
    ap.add_argument("--center-code", default="demo-order")
    ap.add_argument("--max-wait-s", type=int, default=120)
    ap.add_argument("--executor", choices=(EXECUTOR_REQUIRED, EXECUTOR_STANDBY_OK),
                    default=EXECUTOR_REQUIRED,
                    help="tier executor capability (standby-ok = lightweight/standard cells)")
    args = ap.parse_args(argv)
    c = httpx.Client(base_url=args.base.rstrip("/") + "/api/v1", timeout=30)
    h = login(c, args.user)
    request_id, env_id = resolve_targets(c, h, args.center_code)
    print("GREENLIGHT-TARGETS request=%s env=%s" % (request_id, env_id))
    st = run_and_wait(c, h, request_id, env_id, args.max_wait_s,
                      executor=args.executor)
    return 0 if st in (1, STATUS_STANDBY) else 1


if __name__ == "__main__":
    sys.exit(main())
