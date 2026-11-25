# -*- coding: utf-8 -*-
"""Failure-injection guidance for the installer (open-delivery batch T293d).

Turns raw failures captured from docker/maven/postgres output into a short
recovery note with three parts: the offending value named, a literal place to
fix it (file and key, or a probe command), and the exact rerun command that
resumes the interrupted scenario. Unknown output degrades to None so callers
keep their own generic fallback; nothing here ever suggests a manual refresh.
"""

import re

import image_relay  # accelerator candidates + reference shape live there (no second list)

RESUME_HINT = ("After the fix, re-run the exact same command to resume where it "
               "stopped: bash installkit/install.sh --scenario=<this scenario>")

# Tagged image references as docker prints them, with or without quotes:
# "docker.io/library/nginx:1.27-alpine" -> nginx:1.27-alpine.
_IMAGE_REF = re.compile(
    r"[\"']?((?:[a-z0-9][a-z0-9.\-]*\.[a-z]{2,}/)?[a-z0-9][a-z0-9.\-]*"
    r"(?:/[a-z0-9._\-]+)*:[A-Za-z0-9_][A-Za-z0-9.\-]*)[\"']?")


def canonical_image(token):
    """Strip what compose never writes: the ``docker.io/`` and ``library/`` prefixes."""
    named = token[len("docker.io/"):] if token.startswith("docker.io/") else token
    if named.startswith("library/"):
        named = named[len("library/"):]
    return named


_IPV4 = re.compile(r"^\d{1,3}(?:\.\d{1,3}){3}$")


def _is_endpoint(token):
    """True for ``128.242.250.157:443`` style noise in a dial-timeout sentence."""
    head = token.split("/")[0]
    return ":" in head and _IPV4.match(head.split(":")[0]) is not None and "/" not in token


def images_in_output(text):
    """Pull the offending image names out of raw docker output, deduplicated.

    URLs and endpoints are skipped so the note lists only images an operator
    can act on: a manifest URL never carries a ``:tag`` after its path, and a
    bare address:port is the socket that timed out, not something to pull.
    """
    out = []
    for token in _IMAGE_REF.findall(text or ""):
        if _is_endpoint(token):
            continue
        named = canonical_image(token)
        if named not in out:
            out.append(named)
    return out


def relay_example(image):
    """The detour reference for one image, through the first shipped accelerator."""
    return image_relay.relay_ref(canonical_image(image), image_relay.SHIPPED_MIRRORS[0])


def note_for_failure(text, **facts):
    """Classify raw output and build its recovery note in one call (None = unknown).

    Single entry so callers stop re-pairing classify+teach; explicit ``facts``
    win over anything scraped from the output itself.
    """
    kind = classify_failure(text)
    if not kind:
        return None
    if kind == "registry_blocked":
        facts.setdefault("output", text or "")
    elif kind == "port_conflict" and "port" not in facts:
        match = re.search(
            r"(?:address already in use|port is already allocated|bind for)[^0-9]*(\d{2,5})",
            (text or "").lower())
        if match:
            facts["port"] = match.group(1)
    return teach(kind, **facts)


# Lower-cased substrings grouped by failure family. Kept data-driven so new
# vendor message variants join as one entry instead of code changes.
_SIGNATURES = (
    ("disk_short", ("no space left", "disk quota exceeded")),
    ("pg_existing", ("already exists", "duplicate_database")),
    # Vendor wording for "the image never arrived". Kept ahead of egress_dead so
    # a blocked hub teaches the accelerator detour instead of a generic
    # "check your network" line; DNS-resolution wording still lands in egress_dead.
    ("registry_blocked", (
        "failed to resolve reference",
        "failed to do request",
        "i/o timeout",
        "tls handshake timeout",
        "toomanyrequests",
        "pull access denied",
        "repository does not exist",
        "error from registry: manifest unknown",
    )),
    ("egress_dead", (
        "temporary failure in name resolution",
        "name or service not known",
        "could not resolve host",
        "dial tcp: lookup",
        "connection timed out",
        "network is unreachable",
        "request canceled while waiting for connection",
    )),
    ("port_conflict", (
        "address already in use",
        "port is already allocated",
        "bind for",
    )),
)


def classify_failure(text):
    """Return the failure family for captured output text, or None if unknown."""
    if not text:
        return None
    low = text.lower()
    for kind, needles in _SIGNATURES:
        if any(n in low for n in needles):
            # "already exists" is broad: keep it bound to database wording so a
            # file/dir conflict is not mis-taught as a Postgres duplicate.
            if kind == "pg_existing" and "database" not in low:
                continue
            return kind
    return None


def teach(kind, **facts):
    """Build the recovery note for a classified failure; None for unknown."""
    if kind == "port_conflict":
        port = facts.get("port", "?")
        key = facts.get("key", "SERVER_PORT_OFFSET")
        return (
            "Port %s is already in use by another process on this machine.\n"
            "See who holds it: ss -ltnp | grep %s\n"
            "Two ways out: stop the old service, or set %s in .env to a free "
            "port (the whole host port band can also shift via "
            "SERVER_PORT_OFFSET).\n%s" % (port, port, key, RESUME_HINT)
        )
    if kind == "disk_short":
        need = facts.get("required_mb", "?")
        avail = facts.get("available_mb", "?")
        return (
            "Not enough disk space: this install profile needs about %s MB, "
            "the current partition has only %s MB left.\n"
            "Check the partitions first: df -h\n"
            "Possible cleanups (pick any; each has deletion impact, confirm the "
            "targets before running): docker system prune reclaims dangling "
            "images and build cache; or move the Docker data directory to a "
            "larger partition.\n%s"
            % (need, avail, RESUME_HINT)
        )
    if kind == "pg_existing":
        db = facts.get("db", "?")
        return (
            "Database %s already exists. If it is data kept from a previous "
            "install on this machine, just continue — the install steps are "
            "idempotent and never drop databases.\n"
            "To start a fresh environment instead, rename in .env: the "
            "application database inside DB_URL, the object-store meta "
            "database via SWF_META_DB_NAME — then re-run.\n"
            "Drop it by hand only when you are sure the data is unneeded, and "
            "back it up before deleting.\n%s"
            % (db, RESUME_HINT)
        )
    if kind == "registry_blocked":
        images = facts.get("images") or images_in_output(facts.get("output") or "")
        tried = facts.get("mirrors_tried") or list(image_relay.SHIPPED_MIRRORS)
        shown = ", ".join(images) if images else "see the image names in the docker output above"
        sample = images[0] if images else "nginx:1.27-alpine"
        relay = relay_example(sample)
        mirror_urls = ", ".join('"https://%s"' % m for m in image_relay.SHIPPED_MIRRORS)
        return (
            "Cannot pull these images: the direct route to Docker Hub (%s) is "
            "blocked or rate-limited from this machine: %s.\n"
            % (image_relay.HUB_HOST, shown)
            + ("Accelerators tried automatically: %s (none delivered everything).\n"
               % ", ".join(tried) if tried else "")
            + "Three ways out, cheapest first:\n"
              "  A no admin rights — pull through a mirror, then re-tag to the "
              "name this machine expects (swap in any reachable accelerator):\n"
              "     docker pull %s\n"
              "     docker tag  %s %s\n" % (relay, relay, sample)
            + '  B one-time engine config by an admin (keep the existing keys '
              'in /etc/docker/daemon.json, only add this section):\n'
              '     "registry-mirrors": [%s]\n' % mirror_urls
            + "     then run sudo systemctl restart docker — this restarts the "
              "container engine and interrupts other running containers on "
              "this host; do it only after confirming\n"
            + "  C fully offline host: on any machine that can pull the images "
              "run docker save -o testpike-images.tar %s, copy that file here, "
              "then docker load -i testpike-images.tar\n"
              % (" ".join(images) if images else "<image:tag>")
            + "To make the script use an intranet registry automatically from "
              "now on: export %s=<registry address> (may live in .env; "
              "comma-separated candidates), then re-run the same command.\n"
              % image_relay.MIRRORS_KEY
            + "%s" % RESUME_HINT
        )
    if kind == "egress_dead":
        target = facts.get("target", "the external image registry")
        return (
            "Cannot reach %s: this host has no egress or the site is "
            "temporarily down.\n"
            "Check the network/proxy and simply re-run to resume; if the "
            "platform mirrors images, pass --mirror-base-url=<registry "
            "address> to use the backup source.\n%s"
            % (target, RESUME_HINT)
        )
    return None
