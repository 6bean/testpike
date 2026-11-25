# -*- coding: utf-8 -*-
"""MQ vhost reconciler — installer delivery face, single source (R40).

A fresh RabbitMQ only knows vhost "/". Installs that point the app tier at a
custom MQ_VHOST (chain slots, remapped installs — .env is the single source
for user-changeable values) otherwise die in a boot loop: AMQP "vhost not
found" closes the socket, the actuator amqp indicator stays DOWN and the
compose app tier never turns ready (proven live in the alpine matrix cell
window r45, 2026-11-22). The installer therefore reconciles the vhost right
after middleware boot, before any startup guide or seeding is claimed green.

Owner-scoped (R47): touches only the vhost named by MQ_VHOST, never deletes.
Idempotent (R28 kin): a PUT on an existing vhost is skipped entirely; the
permissions PUT is a no-op replay when already granted. Credentials are read
via demo_seed.env_val (env first, then project .env) and never echoed.

the internal slot vhost recipe (dev window recipe) consumes this module;
no second implementation of this logic may appear (R40).
"""
import base64
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request

DEFAULT_VHOST = "/"
DEFAULT_MGMT_PORT = "15672"
WAIT_TRIES_ENV_KEY = "MQ_VHOST_WAIT_TRIES"
WAIT_TRIES_DEFAULT = 30
SLEEP_SECONDS_ENV_KEY = "MQ_VHOST_WAIT_SECONDS"
SLEEP_SECONDS_DEFAULT = 2


def _env_val(key, default):
    pass
    try:
        import demo_seed
        value = demo_seed.env_val(key, default=default)
    except ImportError:
        value = os.environ.get(key) or default
    return (value or default).strip()


def resolve_vhost():
    return _env_val("MQ_VHOST", DEFAULT_VHOST) or DEFAULT_VHOST


def management_base():
    port = _env_val("MQ_MGMT_HOST_PORT", DEFAULT_MGMT_PORT) or DEFAULT_MGMT_PORT
    return "http://localhost:%s" % port


def urlopen_transport(base, user, password):
    pass
    auth = base64.b64encode(("%s:%s" % (user, password)).encode()).decode()

    def call(method, path, body=None):
        req = urllib.request.Request(base + path, method=method)
        req.add_header("Authorization", "Basic " + auth)
        data = None
        if body is not None:
            req.add_header("Content-Type", "application/json")
            data = json.dumps(body).encode()
        try:
            with urllib.request.urlopen(req, data, timeout=5) as resp:
                return resp.status, resp.read().decode()
        except urllib.error.HTTPError as exc:
            return exc.code, exc.read().decode()
    return call


def ensure_vhost(transport=None, vhost=None, user=None, password=None,
                 tries=None, sleep=None, mgmt_url=None):
    pass
    vhost = vhost if vhost is not None else resolve_vhost()
    user = user if user is not None else _env_val("RABBITMQ_USERNAME", "testpike")
    password = password if password is not None else _env_val("RABBITMQ_PASSWORD", None)
    mgmt_url = mgmt_url or management_base()
    if tries is None:
        tries = int(os.environ.get(WAIT_TRIES_ENV_KEY, WAIT_TRIES_DEFAULT))
    if sleep is None:
        sleep = float(os.environ.get(SLEEP_SECONDS_ENV_KEY, SLEEP_SECONDS_DEFAULT))
    if transport is None:
        if not password:
            return False, ["MQ password is not configured — set RABBITMQ_PASSWORD "
                           "in the project .env (or export RABBITMQ_PASSWORD "
                           "first), then re-run this command to resume from the "
                           "failed step"]
        transport = urlopen_transport(mgmt_url, user, password)
    quoted = urllib.parse.quote(vhost, safe="")
    listed = None
    for attempt in range(max(1, tries)):
        try:
            status, text = transport("GET", "/api/vhosts")
            if status == 200:
                listed = [v.get("name") for v in json.loads(text)]
                break
        except Exception:
            pass
        if attempt + 1 < tries and sleep:
            time.sleep(sleep)
    if listed is None:
        return False, ["RabbitMQ management API is unreachable at %s: the target "
                       "vhost \"%s\" cannot be reconciled. Fix: confirm the "
                       "middleware is running and the management port is not "
                       "taken by another service, then re-run this command to "
                       "resume from the failed step"
                       % (mgmt_url, vhost)]
    notes = []
    created = False
    if vhost not in listed:
        status, _ = transport("PUT", "/api/vhosts/%s" % quoted, body={})
        if status not in (200, 201, 204):
            return False, ["Failed to create MQ vhost \"%s\" (management API "
                           "returned %s) — fix: open %s, add a vhost with the "
                           "same name manually and grant user \"%s\" the "
                           "configure/write/read permissions, or repair the "
                           "port mapping, then re-run this command to resume "
                           "from the failed step"
                           % (vhost, status, mgmt_url, user)]
        created = True
        notes.append("MQ vhost \"%s\" created" % vhost)
    else:
        notes.append("MQ vhost \"%s\" already present (creation skipped, "
                     "idempotent)" % vhost)
    status, _ = transport("PUT", "/api/permissions/%s/%s"
                          % (quoted, urllib.parse.quote(user, safe="")),
                          body={"configure": ".*", "write": ".*", "read": ".*"})
    if status not in (200, 201, 204):
        return False, ["Failed to grant user \"%s\" access to vhost \"%s\" "
                       "(management API returned %s) — fix: open the "
                       "Permissions page of the %s console, grant "
                       "configure/write/read manually, then re-run this "
                       "command to resume from the failed step"
                       % (user, vhost, status, mgmt_url)]
    notes.append("User \"%s\" configure/write/read permissions on vhost "
                 "\"%s\" confirmed" % (user, vhost))
    return True, notes
