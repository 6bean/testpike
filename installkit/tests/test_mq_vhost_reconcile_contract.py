# -*- coding: utf-8 -*-
"""MQ vhost reconcile + app-profile-aware service guide contracts (T293d r45).

Fresh RabbitMQ ships only vhost "/". Any install whose app tier points at a
custom MQ_VHOST (chain slots, remapped installs — .env is the single source)
dies in a boot loop otherwise: AMQP "vhost not found" → Socket closed → the
actuator amqp indicator stays DOWN → the compose app tier never turns ready
(proven live in the alpine matrix cell window r45, 2026-11-22). So the
installer must reconcile the vhost right after middleware boot, idempotently
and owner-scoped, and the startup guide must tell the truth per tier
(compose app profile is the shipped delivery path, source-run is a developer
option).
"""
import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import mq_vhost  # noqa: E402
import s1_bootstrap  # noqa: E402


class FakeTransport:
    """Recording transport: canned GET payload + status for the two PUTs."""

    def __init__(self, vhosts, create_status=201, grant_status=204, boom=None):
        self.vhosts = vhosts
        self.create_status = create_status
        self.grant_status = grant_status
        self.boom = boom
        self.calls = []

    def __call__(self, method, path, body=None):
        self.calls.append((method, path))
        if self.boom:
            raise self.boom
        if method == "GET" and path == "/api/vhosts":
            return 200, json.dumps([{"name": v} for v in self.vhosts])
        if method == "PUT":
            return (self.create_status if path.startswith("/api/vhosts/")
                    else self.grant_status), ""
        raise AssertionError("unexpected call %s %s" % (method, path))


def test_missing_vhost_is_created_and_permissions_granted():
    t = FakeTransport(["/"])
    ok, notes = mq_vhost.ensure_vhost(transport=t, vhost="/tp-slot4", user="testpike")
    assert ok, notes
    puts = [p for m, p in t.calls if m == "PUT"]
    assert "/api/vhosts/%2Ftp-slot4" in puts, "自定义 vhost 缺失时必须 PUT 建（幂等通道）"
    assert any(p.startswith("/api/permissions/%2Ftp-slot4/") for p in puts), \
        "建 vhost 后必须给用户配 configure/write/read 权限"


def test_existing_vhost_reconcile_is_idempotent_no_create():
    t = FakeTransport(["/", "/tp-slot4"])
    ok, notes = mq_vhost.ensure_vhost(transport=t, vhost="/tp-slot4", user="testpike")
    assert ok, notes
    assert not [p for m, p in t.calls if m == "PUT" and p.startswith("/api/vhosts/")], \
        "已存在的 vhost 禁再发创建 PUT（重跑=零写入幂等）"


def test_management_unreachable_fails_loudly_with_named_exit_and_zero_credentials():
    t = FakeTransport([], boom=ConnectionError("refused"))
    monkey_password = "sup3r-s3cret-pw"
    ok, notes = mq_vhost.ensure_vhost(
        transport=t, vhost="/tp-slot4", user="testpike",
        password=monkey_password, tries=1, sleep=0)
    assert not ok, "管理台不可达禁假绿（应用档随后必 unhealthy，红必须落在装机步=断点续跑）"
    joined = " ".join(notes)
    assert "/tp-slot4" in joined, "指路必点名 vhost 值（R63 原因要素）"
    assert "15672" in joined or "management" in joined.lower(), \
        "指路必点名管理台入口（R63 修法要素）"
    assert "re-run" in joined, "指路必给可照做的出路命令（R63 假路即缺陷）"
    assert monkey_password not in joined, "红线4：凭据禁入屏显文案"


@pytest.mark.parametrize("boom_status", [401, 500])
def test_write_failure_status_is_not_silently_accepted(boom_status):
    t = FakeTransport(["/"], create_status=boom_status)
    ok, notes = mq_vhost.ensure_vhost(transport=t, vhost="/tp-slot4", user="testpike")
    assert not ok, "创建 PUT 返回非 2xx 必须判红（%s）" % boom_status


def test_cmd_compose_refuses_false_green_when_vhost_reconcile_fails(tmp_path, monkeypatch):
    pass
    monkeypatch.setattr(s1_bootstrap, "compose_stages",
                        lambda *a, **k: [{"phase": "middleware", "cmd": ["true"]}])
    monkeypatch.setattr(s1_bootstrap, "_run_stage", lambda cmd: (0, ""))
    monkeypatch.setattr(s1_bootstrap, "relay_stage_images", lambda *a, **k: True)
    monkeypatch.setattr(s1_bootstrap.mq_vhost, "ensure_vhost",
                        lambda **kw: (False, ["mock: management down"]))
    args = _compose_args(tmp_path)
    assert s1_bootstrap.cmd_compose(args) == 1


def test_cmd_compose_green_and_boot_order_reconcile_after_middleware(tmp_path, monkeypatch):
    order = []
    monkeypatch.setattr(s1_bootstrap, "compose_stages",
                        lambda *a, **k: [{"phase": "middleware", "cmd": ["true"]}])
    monkeypatch.setattr(s1_bootstrap, "_run_stage",
                        lambda cmd: order.append("middleware") or (0, ""))
    monkeypatch.setattr(s1_bootstrap, "relay_stage_images", lambda *a, **k: True)
    def fake_ensure(**kw):
        order.append("vhost")
        return True, []
    monkeypatch.setattr(s1_bootstrap.mq_vhost, "ensure_vhost", fake_ensure)
    monkeypatch.setattr(s1_bootstrap, "four_services_guide",
                        lambda tier=None: order.append("guide") or ["g"])
    assert s1_bootstrap.cmd_compose(_compose_args(tmp_path)) == 0
    assert order == ["middleware", "vhost", "guide"], \
        "对账必须落在中间件就绪之后、指引输出之前（应用档拉起前 vhost 必在位）"


def _compose_args(tmp_path):
    import argparse
    return argparse.Namespace(
        answers_file=str(tmp_path / "missing.json"), repo_root=str(s1_bootstrap.REPO_ROOT))


def test_guide_honestly_reports_app_already_booted_on_projected_tier():
    lines = s1_bootstrap.four_services_guide(tier="standard")
    joined = " ".join(lines)
    assert "with the compose app profile" in joined, \
        "档位已投影 app profile 时禁再首推源码跑法（=看着要手起、实际已起的错位）"
    assert "docker compose ps" in joined, "已拉起形态必给核对入口（R63 照做可达）"
    assert "Developer option" in joined, "source-run path is clearly downgraded to the developer option"


def test_slot_stack_boot_window_path_reconciles_vhost_before_first_service():
    """Every slot E2E/L3/L4 window boots through tools/ops/slot_stack_boot.sh.
    A recycled or freshly issued slot has its vhost deleted with the slot, so
    the boot script must run the reconciler BEFORE launching core: otherwise
    core dies in the AMQP close loop ("Socket closed"), health never turns
    green and the window burns its boot budget for nothing (observed 2026-11-22
    slot4 journey-pack window BOOT_FAIL). The compose installer path is
    already covered by the boot-order test above; this closes the window path."""
    boot_script = Path(__file__).resolve().parents[2] / "tools" / "ops" / "slot_stack_boot.sh"
    text = boot_script.read_text(encoding="utf-8")
    assert "ensure_slot_vhost.py" in text, (
        "slot_stack_boot.sh must call the vhost reconciler single source")
    assert text.index("ensure_slot_vhost.py") < text.index("tp_start_unit core"), (
        "reconcile must run before the first service launch")
    # the failure must abort the boot, not fall through (false-green ban)
    segment = text[text.index("ensure_slot_vhost.py"):text.index("tp_start_unit core")]
    assert "SLOT-BOOT-EXIT=1" in segment and "exit 1" in segment, (
        "reconcile failure must terminate the boot with the named anchor")


def test_guide_leads_factory_compose_path_on_default_tier():
    joined = " ".join(s1_bootstrap.four_services_guide())
    assert "docker compose --profile app up -d" in joined, \
        "默认档位净机指引首推=出厂 compose 交付路径（票⑭）"
    assert "Developer option" in joined and "java -jar" in joined, \
        "源码跑法保留但降级为开发者选项，不丢既有能力句"
    for banned in ("systemctl stop", "docker stop", "kill -9"):
        assert banned not in joined


def test_mq_vhost_source_ships_english_only():
    """The installer reconciler ships English-only operator text (no i18n track)."""
    src = Path(mq_vhost.__file__).read_text(encoding="utf-8")
    offenders = [line for line in src.splitlines()
                 if any("\u4e00" <= ch <= "\u9fff" for ch in line)]
    assert not offenders, (
        "install surface must not carry CJK operator text; offending lines: %s"
        % offenders[:3])


def test_auth_refusal_is_taught_as_credential_mismatch_not_ports():
    # A broker that answers 401 is reachable; the old single message sent
    # operators hunting ports instead of the .env pair (dead pointer = defect).
    for code in (401, 403):
        transport = lambda m, p, body=None: (code, "User denied access")
        ok, notes = mq_vhost.ensure_vhost(
            transport=transport, vhost="/tp-slot9", user="ops",
            password="sup3r-s3cret-pw", tries=2, sleep=0)
        assert not ok
        joined = " ".join(notes)
        assert "RABBITMQ_PASSWORD" in joined and ".env" in joined, joined
        assert "down -v" in joined and "re-run" in joined, joined
        assert "taken by another service" not in joined, joined
        assert "sup3r-s3cret-pw" not in joined, "red line 4: values never on screen"


def test_non_auth_status_still_reports_unreachable_with_detail():
    # The other branch keeps its hit case: an HTTP 500 answer must not be
    # mistaken for a credential fault, and must carry the observed status.
    transport = lambda m, p, body=None: (500, "Internal Server Error")
    ok, notes = mq_vhost.ensure_vhost(
        transport=transport, vhost="/tp-slot9", user="ops",
        password="x", tries=1, sleep=0)
    assert not ok
    joined = " ".join(notes)
    assert "last HTTP status 500" in joined, joined
    assert "refused the management credentials" not in joined, joined
