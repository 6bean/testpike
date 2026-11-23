"""Contract tests for installkit/distro_profile.py (T294b gap 3: the unit
hosting layer must stop pretending every Linux is the same).

Profiles under test: WSL, CentOS/RHEL family, Ubuntu/Debian family, plus a
conservative "other". Every host fact is injectable (flag, then env test
double, then the real file) so the suite is deterministic on any machine.
"""
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODULE = ROOT / "distro_profile.py"

UBUNTU_OS = "ID=ubuntu\nID_LIKE=debian\nNAME=\"Ubuntu\"\nVERSION_ID=\"22.04\"\n"
CENTOS_OS = "ID=centos\nNAME=\"CentOS Stream\"\nVERSION_ID=\"9\"\n"
ARCH_OS = "ID=arch\nNAME=\"Arch Linux\"\n"
WSL_VERSION = ("Linux version 5.15.153.1-microsoft-standard-WSL2 "
               "(oe-user@oe-host) (x86_64-wsl-gcc) #1 SMP")
PLAIN_VERSION = "Linux version 6.2.0-39-generic (buildd@lcy02-amd64-062)"


def _files(tmp_path, os_release=UBUNTU_OS, version=PLAIN_VERSION, pid1="systemd"):
    os_p = tmp_path / "os-release"
    os_p.write_text(os_release)
    ver_p = tmp_path / "version"
    ver_p.write_text(version)
    pid_p = tmp_path / "pid1"
    pid_p.write_text(pid1)
    return os_p, ver_p, pid_p


def _run(args, env=None):
    full = {"PATH": "/usr/bin:/bin", **(env or {})}
    return subprocess.run([sys.executable, str(MODULE), *args],
                          capture_output=True, text=True, env=full)


def _detect(tmp_path, **kw):
    os_p, ver_p, pid_p = _files(tmp_path, **kw)
    res = _run(["detect", "--os-release", str(os_p),
                "--proc-version", str(ver_p), "--pid1-comm", str(pid_p)])
    assert res.returncode == 0, res.stderr
    return dict(line.split("=", 1) for line in res.stdout.strip().splitlines())


# --- family detection: each of the three profiles hits once ------------------

def test_detect_ubuntu_maps_to_debian_family(tmp_path):
    facts = _detect(tmp_path)
    assert facts["family"] == "debian"
    assert facts["distro_id"] == "ubuntu"


def test_detect_centos_maps_to_rhel_family(tmp_path):
    os_p, ver_p, pid_p = _files(tmp_path, os_release=CENTOS_OS)
    res = _run(["detect", "--os-release", str(os_p),
                "--proc-version", str(ver_p), "--pid1-comm", str(pid_p)])
    facts = dict(line.split("=", 1) for line in res.stdout.strip().splitlines())
    assert facts["family"] == "rhel"


def test_detect_wsl_kernel_beats_distro_id(tmp_path):
    # A WSL guest is Ubuntu by os-release; the microsoft kernel line is the
    # deciding fact because the boot/lingering semantics differ there.
    facts = _detect(tmp_path, version=WSL_VERSION)
    assert facts["family"] == "wsl"


def test_detect_unknown_release_is_conservative_other(tmp_path):
    os_p, ver_p, pid_p = _files(tmp_path, os_release=ARCH_OS)
    res = _run(["detect", "--os-release", str(os_p),
                "--proc-version", str(ver_p), "--pid1-comm", str(pid_p)])
    facts = dict(line.split("=", 1) for line in res.stdout.strip().splitlines())
    assert facts["family"] == "other"
    assert facts["distro_id"] == "arch"


def test_detect_family_env_override_is_the_test_double_channel(tmp_path):
    os_p, ver_p, pid_p = _files(tmp_path)
    res = _run(["detect", "--os-release", str(os_p), "--proc-version", str(ver_p),
                "--pid1-comm", str(pid_p)],
               env={"INSTALLKIT_DISTRO_FAMILY": "wsl"})
    facts = dict(line.split("=", 1) for line in res.stdout.strip().splitlines())
    assert facts["family"] == "wsl"


# --- render: domain is the only knob that changes bytes ----------------------

def _template(tmp_path):
    src = tmp_path / "unit.in"
    src.write_text(
        "[Unit]\nDescription=x\nAfter=network-online.target\n\n"
        "[Service]\nExecStart=__PYTHON__ __AGENT_ENTRY__ run\n"
        "EnvironmentFile=__ENV_FILE__\nRestart=always\n\n"
        "[Install]\nWantedBy=multi-user.target\n")
    return src


def test_render_user_domain_rewrites_wanted_by(tmp_path):
    src = _template(tmp_path)
    out = tmp_path / "units" / "tp-service-agent.service"
    res = _run(["render", "--template", str(src), "--out", str(out),
                "--domain", "user", "--python", "/usr/bin/python3",
                "--agent-entry", "/opt/e/agent.py", "--env-file", "/e.env"])
    assert res.returncode == 0, res.stderr
    text = out.read_text()
    assert "WantedBy=default.target" in text
    assert "__" not in text and "/usr/bin/python3 /opt/e/agent.py" in text


def test_render_system_domain_keeps_multi_user_target(tmp_path):
    src = _template(tmp_path)
    out = tmp_path / "units" / "tp-service-agent.service"
    res = _run(["render", "--template", str(src), "--out", str(out),
                "--domain", "system", "--python", "/usr/bin/python3",
                "--agent-entry", "/opt/e/agent.py", "--env-file", "/e.env"])
    assert res.returncode == 0, res.stderr
    assert "WantedBy=multi-user.target" in out.read_text()


def test_render_refuses_unknown_domain_with_a_guide_line(tmp_path):
    src = _template(tmp_path)
    out = tmp_path / "u.service"
    res = _run(["render", "--template", str(src), "--out", str(out),
                "--domain", "solar", "--python", "p", "--agent-entry", "a",
                "--env-file", "e"])
    assert res.returncode == 2
    assert "user" in res.stderr and "system" in res.stderr
    assert not out.exists()


# --- preflight: honest refusal naming the real fix path (R63) ----------------

def test_preflight_ok_when_systemd_is_pid1(tmp_path):
    os_p, ver_p, pid_p = _files(tmp_path)
    res = _run(["preflight", "--domain", "system", "--os-release", str(os_p),
                "--proc-version", str(ver_p), "--pid1-comm", str(pid_p)])
    assert res.returncode == 0, res.stderr


def test_preflight_wsl_without_systemd_names_wsl_conf(tmp_path):
    os_p, ver_p, pid_p = _files(tmp_path, version=WSL_VERSION, pid1="init")
    res = _run(["preflight", "--domain", "system", "--os-release", str(os_p),
                "--proc-version", str(ver_p), "--pid1-comm", str(pid_p)])
    assert res.returncode == 1
    assert "/etc/wsl.conf" in res.stderr and "systemd=true" in res.stderr


def test_preflight_non_wsl_without_systemd_points_at_k8s_form(tmp_path):
    os_p, ver_p, pid_p = _files(tmp_path, pid1="systemd-shim")
    res = _run(["preflight", "--domain", "system", "--os-release", str(os_p),
                "--proc-version", str(ver_p), "--pid1-comm", str(pid_p)])
    assert res.returncode == 1
    # Fix path must be real: the Kubernetes form exists in this repo.
    assert "deploy/fleet/k8s" in res.stderr


def test_preflight_user_domain_requires_linger_and_names_the_command(tmp_path):
    os_p, ver_p, pid_p = _files(tmp_path)
    fake = tmp_path / "loginctl"
    fake.write_text("#!/usr/bin/env bash\n"
                    'if [ "$1" = show-user ]; then echo Linger=no; exit 0; fi\n'
                    "exit 0\n")
    fake.chmod(0o755)
    res = _run(["preflight", "--domain", "user", "--os-release", str(os_p),
                "--proc-version", str(ver_p), "--pid1-comm", str(pid_p),
                "--loginctl-bin", str(fake)],
               env={"USER": "tester"})
    assert res.returncode == 1
    assert "loginctl enable-linger" in res.stderr


def test_preflight_user_domain_passes_when_linger_enabled(tmp_path):
    os_p, ver_p, pid_p = _files(tmp_path)
    fake = tmp_path / "loginctl"
    fake.write_text("#!/usr/bin/env bash\n"
                    'if [ "$1" = show-user ]; then echo Linger=yes; exit 0; fi\n'
                    "exit 0\n")
    fake.chmod(0o755)
    res = _run(["preflight", "--domain", "user", "--os-release", str(os_p),
                "--proc-version", str(ver_p), "--pid1-comm", str(pid_p),
                "--loginctl-bin", str(fake)],
               env={"USER": "tester"})
    assert res.returncode == 0, res.stderr
