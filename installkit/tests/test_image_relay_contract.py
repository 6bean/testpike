# -*- coding: utf-8 -*-
"""Contracts for the installer image relay (blocked-registry detour).

Why these exist: the shipped first-install path died on ``docker compose up``
with a bare socket timeout whenever a host could not reach the hub, and the
only recovery was a human typing mirror commands by hand. The relay makes that
decision itself, so its reference shapes, its knob surface and its refusal
wording are all pinned here — with the docker CLI faked at the subprocess
boundary, never at the decision inside the module.
"""
import json
import os
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "installkit"))

import failure_guide  # noqa: E402
import image_relay  # noqa: E402

COMPOSE = ROOT / "docker-compose.yml"

# Screen wording rule: internal bookkeeping and self-referential terms stay off
# anything the operator reads.
BANNED_ON_SCREEN = ("人话", "拒件", "裁决", "单源", "棘轮", "收口", "出账", "T7", "TC-")

HUB_TIMEOUT_OUTPUT = (
    ' ✘ seaweedfs-ui-auth Error failed to resolve reference '
    '"docker.io/library/nginx:1.27-alpine": failed to do request: '
    'Head "https://registry-1.docker.io/v2/library/nginx/manifests/1.27-alpine": '
    'dial tcp 128.242.250.157:443: i/o timeout\n'
    'Error response from daemon: failed to resolve reference '
    '"docker.io/library/nginx:1.27-alpine"')


# ------------------------------------------------------------------ references
def test_relay_reference_shapes():
    assert image_relay.relay_ref("nginx:1.27-alpine", "docker.m.daocloud.io") == \
        "docker.m.daocloud.io/library/nginx:1.27-alpine", \
        "official images live under library/ on a hub clone"
    assert image_relay.relay_ref("pgvector/pgvector:pg16", "https://docker.1ms.run/") == \
        "docker.1ms.run/pgvector/pgvector:pg16", "a configured scheme/trailing slash is tolerated"
    assert image_relay.relay_ref("redis", "m.local") == "m.local/library/redis:latest", \
        "an untagged reference keeps latest, the way docker resolves it"


def test_non_hub_registries_are_not_relayed():
    assert image_relay.on_docker_hub("chrislusf/seaweedfs:4.53")
    assert not image_relay.on_docker_hub("quay.io/prometheus/busybox:latest"), \
        "a third-party registry is already being contacted directly"
    assert image_relay.split_image("registry.internal:5000/team/app:1.2") == \
        ("registry.internal:5000", "team/app", "1.2")


# ------------------------------------------------------------------------ knobs
def test_knobs_ship_defaults_and_accept_hot_updates():
    assert image_relay.mirrors({}) == image_relay.SHIPPED_MIRRORS
    assert image_relay.mirrors({image_relay.MIRRORS_KEY: "a.corp, https://b.corp/"}) == \
        ("a.corp", "b.corp")
    assert image_relay.relay_enabled({}) is True
    assert image_relay.relay_enabled({image_relay.RELAY_KEY: "off"}) is False
    assert image_relay._seconds(image_relay.RELAY_TIMEOUT_KEY, 900,
                               {image_relay.RELAY_TIMEOUT_KEY: "42"}) == 42
    assert image_relay._seconds(image_relay.RELAY_TIMEOUT_KEY, 900,
                               {image_relay.RELAY_TIMEOUT_KEY: "nonsense"}) == 900, \
        "a bad value falls back to the shipped default instead of crashing the install"


# ------------------------------------------------------------ compose parsing
def test_middleware_images_match_the_compose_own_view():
    """The file parse and ``docker compose config --images`` must agree exactly."""
    import subprocess
    resolved = subprocess.run(["docker", "compose", "-f", str(COMPOSE), "config", "--images"],
                              capture_output=True, text=True, timeout=120)
    if resolved.returncode != 0:
        pytest.skip("no docker compose CLI on this host to cross-check against")
    expected = {line.strip() for line in resolved.stdout.splitlines() if line.strip()}
    assert set(image_relay.pullable_images(str(COMPOSE))) == expected


def test_build_backed_services_are_never_relayed():
    """App-tier images are built locally: a hub detour for them can only fail."""
    assert image_relay.pullable_images(str(COMPOSE), profiles=("app",)) == []
    observed = image_relay.pullable_images(str(COMPOSE), profiles=("observability",))
    assert "prom/prometheus:v2.53.2" in observed and "grafana/grafana:11.2.0" in observed


def test_compose_interpolation_is_resolved_like_compose(compose_file):
    path = compose_file('  ui:\n    image: "${SWF_UI_PROXY_IMAGE:-nginx:1.27-alpine}"\n')
    assert image_relay.pullable_images(str(path)) == ["nginx:1.27-alpine"]
    assert image_relay.pullable_images(str(path),
                                       environ={"SWF_UI_PROXY_IMAGE": "corp/nginx:1.9"}) == \
        ["corp/nginx:1.9"], "an install-time override must be relayed, not the shipped default"


@pytest.fixture
def compose_file(tmp_path):
    paths = []

    def make(body):
        path = tmp_path / ("compose-%d.yml" % (len(paths) + 1))
        path.write_text("services:\n" + body, encoding="utf-8")
        paths.append(path)
        return path
    return make


def test_profiles_scope_the_selection(compose_file):
    path = compose_file(
        "  plain:\n    image: redis:7.4\n"
        "  gated:\n    profiles: [\"app\"]\n    image: grafana/grafana:11.2.0\n"
        "  built:\n    image: me/app:local\n    build:\n      context: .\n")
    assert image_relay.pullable_images(str(path)) == ["redis:7.4"]
    assert image_relay.pullable_images(str(path), profiles=("app",)) == ["grafana/grafana:11.2.0"]


# ------------------------------------------------------------ daemon self-heal
def test_merge_only_adds_and_never_rewrites_existing_keys():
    existing = {"log-driver": "json-file", "live-restore": True,
                "registry-mirrors": ["https://docker.1ms.run"]}
    merged, added = image_relay.merge_registry_mirrors(existing,
                                                       ["docker.1ms.run", "docker.m.daocloud.io"])
    assert merged["log-driver"] == "json-file" and merged["live-restore"] is True
    assert added == ["https://docker.m.daocloud.io"], "an in-service entry is not re-added"
    assert merged["registry-mirrors"] == ["https://docker.1ms.run", "https://docker.m.daocloud.io"]
    again, added_again = image_relay.merge_registry_mirrors(merged, ["docker.m.daocloud.io"])
    assert added_again == [] and again == merged, "rerunning the step is a no-op"


def test_apply_daemon_mirrors_backs_up_before_touching(tmp_path, monkeypatch):
    daemon = tmp_path / "daemon.json"
    daemon.write_text(json.dumps({"log-driver": "journald"}), encoding="utf-8")
    monkeypatch.setattr(image_relay, "os", image_relay.os)
    monkeypatch.setattr(image_relay, "foreign_containers", lambda environ=None: [])
    monkeypatch.setattr(image_relay, "_restart_engine", lambda: True)
    monkeypatch.setattr(image_relay, "configured_mirrors",
                        lambda: ["https://docker.m.daocloud.io"])
    handled, note = image_relay.apply_daemon_mirrors(["docker.m.daocloud.io"],
                                                     daemon_path=str(daemon))
    assert handled, note
    written = json.loads(daemon.read_text(encoding="utf-8"))
    assert written["log-driver"] == "journald", "pre-existing keys survive the merge"
    assert written["registry-mirrors"] == ["https://docker.m.daocloud.io"]
    backups = list(tmp_path.glob("daemon.json.bak-*"))
    assert backups, "the original file is copied aside before any write"


def test_apply_refuses_to_restart_over_foreign_containers(tmp_path, monkeypatch):
    daemon = tmp_path / "daemon.json"
    daemon.write_text("{}", encoding="utf-8")
    monkeypatch.setattr(image_relay, "foreign_containers",
                        lambda environ=None: ["payment-gateway"])
    monkeypatch.setattr(image_relay, "_restart_engine",
                        lambda: pytest.fail("the engine must not be restarted over a foreign workload"))
    handled, note = image_relay.apply_daemon_mirrors(["docker.m.daocloud.io"],
                                                     daemon_path=str(daemon))
    assert not handled
    assert "payment-gateway" in note and "sudo systemctl restart docker" in note, \
        "the operator gets the named blocker and the literal command, not a silent skip"


def test_apply_refuses_an_unparsable_daemon_file(tmp_path, monkeypatch):
    daemon = tmp_path / "daemon.json"
    daemon.write_text("{ not json", encoding="utf-8")
    monkeypatch.setattr(image_relay, "_restart_engine", lambda: True)
    handled, note = image_relay.apply_daemon_mirrors(["docker.m.daocloud.io"],
                                                     daemon_path=str(daemon))
    assert not handled and "failed to parse" in note
    assert daemon.read_text(encoding="utf-8") == "{ not json", "a broken file is left untouched"


# ------------------------------------------------------------------ relay run
class DockerDouble:
    """Records docker CLI calls; answers pulls from a scripted outcome map."""

    def __init__(self, have=(), pull_fails=(), tag_fails=()):
        self.have = set(have)
        self.pull_fails = set(pull_fails)
        self.tag_fails = set(tag_fails)
        self.calls = []

    def __call__(self, args, timeout=None):
        self.calls.append(list(args))
        verb = args[0]
        if verb == "image":
            return (0, "", "") if args[-1] in self.have else (1, "", "Error: No such image")
        if verb == "pull":
            image = args[1]
            if image in self.pull_fails:
                return 1, "", "Error response from daemon: failed to resolve reference"
            self.have.add(image)
            return 0, "Downloaded newer image", ""
        if verb == "tag":
            src, dst = args[1], args[2]
            if src in self.tag_fails:
                return 1, "", "Error response from daemon: No such image"
            self.have.add(dst)
            return 0, "", ""
        if verb == "images":
            return 0, "\n".join(sorted(self.have)), ""
        if verb == "rmi":
            self.have.discard(args[1])
            return 0, "", ""
        return 0, "", ""

    def names(self, verb, position=1):
        """Argument ``position`` of every recorded ``verb`` call (1 = the image)."""
        return [c[position] for c in self.calls if c and c[0] == verb]


@pytest.fixture
def fake_docker(monkeypatch):
    def install(double):
        monkeypatch.setattr(image_relay, "docker_available", lambda: True)
        monkeypatch.setattr(image_relay, "_docker", double)
        monkeypatch.setattr(image_relay, "hub_reachable", lambda environ=None: False)
        monkeypatch.setattr(image_relay, "can_write_daemon",
                            lambda path=image_relay.DAEMON_JSON, environ=None: False)
        return double
    return install


def test_missing_images_are_relayed_and_retagged(fake_docker):
    double = fake_docker(DockerDouble(pull_fails={"redis:7.4", "nginx:1.27-alpine"}))
    lines = []
    report = image_relay.ensure_images(["redis:7.4", "nginx:1.27-alpine"],
                                       environ={}, printer=lines.append,
                                       candidates=["docker.m.daocloud.io"])
    assert not report["failed"], report
    assert report["relayed"] == ["redis:7.4", "nginx:1.27-alpine"]
    assert double.names("tag", position=2) == ["redis:7.4", "nginx:1.27-alpine"], \
        "the canonical name is what compose looks for"
    assert any("library/redis:7.4" in p for p in double.names("pull"))


def test_locally_present_images_are_never_touched(fake_docker):
    double = fake_docker(DockerDouble(have={"redis:7.4"}))
    report = image_relay.ensure_images(["redis:7.4"], environ={}, printer=lambda t: None,
                                       candidates=["docker.m.daocloud.io"])
    assert report["present"] == ["redis:7.4"] and not report["relayed"]
    assert double.names("pull") == [], "an idempotent rerun must not re-download"


def test_presence_check_dodges_the_flag_the_cli_rejects(fake_docker):
    """Regression from the live run: docker 29.x answers rc 125 to
    ``image inspect --type image``, so a fully seeded host looked empty and the
    whole stack got re-pulled on every rerun. Presence now comes from one
    ``docker images`` listing."""
    double = fake_docker(DockerDouble(have={"redis:7.4", "nginx:1.27-alpine"}))
    known = image_relay.local_image_tags()
    assert known == {"redis:7.4", "nginx:1.27-alpine"}
    assert image_relay.present_locally("redis:7.4", known=known)
    assert not image_relay.present_locally("redis:6", known=known)
    assert ["images", "--format", "{{.Repository}}:{{.Tag}}"] in double.calls
    assert not any("--type" in c for c in double.calls), \
        "no inspect call may carry the flag the CLI rejects"


def test_candidate_order_falls_over_until_one_works(fake_docker, monkeypatch):
    double = fake_docker(DockerDouble(pull_fails={"redis:7.4"}))
    real_relay = image_relay.relay_one

    def picky(image, mirror, direct_timeout, relay_timeout):
        if mirror == "docker.m.daocloud.io":
            real_relay(image, mirror, direct_timeout, relay_timeout)
            return False, "simulated dead mirror"
        return real_relay(image, mirror, direct_timeout, relay_timeout)

    monkeypatch.setattr(image_relay, "relay_one", picky)
    report = image_relay.ensure_images(["redis:7.4"], environ={}, printer=lambda t: None,
                                       candidates=["docker.m.daocloud.io", "docker.1ms.run"])
    assert not report["failed"] and report["relayed"] == ["redis:7.4"]
    assert "docker.1ms.run/library/redis:7.4" in double.names("pull"), \
        "the next candidate has to be tried, not given up on"


def test_all_candidates_dead_produces_the_ferry_recipe(fake_docker):
    fake_docker(DockerDouble(pull_fails={"redis:7.4", "docker.m.daocloud.io/library/redis:7.4",
                                         "docker.1ms.run/library/redis:7.4",
                                         "docker.1panel.live/library/redis:7.4"}))
    lines = []
    ok = image_relay.ensure_for_compose(str(_compose_needing("redis:7.4")), environ={},
                                         printer=lines.append)
    assert ok is False
    text = "\n".join(lines)
    assert "docker save -o testpike-images.tar redis:7.4" in text
    assert "docker load -i testpike-images.tar" in text
    assert image_relay.MIRRORS_KEY in text, "the intranet knob is named on the dead-end path too"
    for word in BANNED_ON_SCREEN:
        assert word not in text, "screen wording must stay operational"


def test_disabled_switch_makes_no_docker_calls(fake_docker, monkeypatch):
    double = fake_docker(DockerDouble())
    monkeypatch.setattr(image_relay, "present_locally",
                        lambda image: pytest.fail("the switch must short-circuit before any probe"))
    assert image_relay.ensure_for_compose(str(_compose_needing("redis:7.4")),
                                          environ={image_relay.RELAY_KEY: "off"},
                                          printer=lambda t: None) is True
    assert double.calls == []


def _compose_needing(image, body=None):
    """Smallest compose shape the relay has to work against (temp path is inert after exit)."""
    import tempfile
    fd, path = tempfile.mkstemp(suffix=".yml", text=True)
    with os.fdopen(fd, "w", encoding="utf-8") as fh:
        fh.write("services:\n  svc:\n    image: %s\n%s" % (image, body or ""))
    return Path(path)


def test_repair_scenario_consumes_the_relay():
    """The relay CLI must have a real consumer: `--scenario=repair` re-probes images.

    Pinned on the shell surface because an un-wired CLI is dead code: the same
    fix has to be reachable from the health-check path, not only first install.
    """
    text = (ROOT / "installkit" / "install.sh").read_text(encoding="utf-8")
    assert "repair)" in text
    # The scenario case is the last one (an earlier "repair)" belongs to the
    # argument validator's alias list, which must not be mistaken for wiring.
    repair_block = text.rsplit("repair)", 1)[1].split("esac", 1)[0]
    assert "image_relay.py" in repair_block and "ensure --compose-file" in repair_block
    assert "step:image-relay" not in text, \
        "a recorded step would let the second health-check silently skip the probe"


# ------------------------------------------------------------------- the note
def test_guide_names_the_image_and_every_way_out():
    note = failure_guide.note_for_failure(HUB_TIMEOUT_OUTPUT)
    assert note, "the real docker wording must classify, not degrade to silence"
    assert "nginx:1.27-alpine" in note, "the offending value is named"
    assert "docker.m.daocloud.io/library/nginx:1.27-alpine" in note
    assert "/etc/docker/daemon.json" in note and "registry-mirrors" in note
    assert "docker load -i testpike-images.tar" in note
    assert "install.sh" in note, "the note ends with the resume command"
    for word in BANNED_ON_SCREEN:
        assert word not in note, "screen wording must stay operational (%r)" % word


def test_guide_stays_silent_for_unrelated_output():
    assert failure_guide.note_for_failure("nothing interesting happened") is None
    assert failure_guide.note_for_failure("") is None


def test_image_scrape_skips_endpoint_noise():
    assert failure_guide.images_in_output(HUB_TIMEOUT_OUTPUT) == ["nginx:1.27-alpine"], \
        "a manifest URL must not be reported as an image the operator should pull"
