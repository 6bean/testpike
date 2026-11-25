pass
import json
import re
from pathlib import Path

import pytest
import recipe as rp

MIG_DIR = Path(__file__).resolve().parents[2] / "db" / "migration"


def _merge_v178_upgrade_into_xcu():
    pass
    text = (MIG_DIR / "V173__appauto_engine_recipes.sql").read_text(encoding="utf-8")
    blocks = re.findall(r"UPDATE tp_appauto_engine\s+SET install_recipe = '(.*?)'::jsonb"
                        r"(.*?);", text, re.DOTALL)
    rec = next(json.loads(raw) for raw, tail in blocks if "'XCUITEST'" in tail)
    up = (MIG_DIR / "V178__command_upgrade_types_and_recipe.sql").read_text(encoding="utf-8")
    m = re.search(r"'\{hosts,macos,upgrade\}',\s*'(.*?)'::jsonb", up, re.DOTALL)
    rec["hosts"]["macos"]["upgrade"] = json.loads(m.group(1))
    return rec


V173_XCU_RECIPE = _merge_v178_upgrade_into_xcu()

UP_RECIPE = {
    "version_pin": "2.0.0",
    "hosts": {
        "linux": {
            "probe": ["demo --version"],
            "install": [{"cmd": "npm install -g demo@2.0.0", "mirror_rewrite": "registry"}],
            "uninstall": [{"cmd": "npm uninstall -g demo"}],
            "upgrade": [{"cmd": "demo teardown"},
                        {"cmd": "npm install -g demo@2.0.0", "mirror_rewrite": "registry"}],
        }
    },
}

FALLBACK_RECIPE = {
    "version_pin": "1.2.3",
    "hosts": {"linux": {"install": [{"cmd": "pip install demo==1.2.3",
                                     "mirror_rewrite": "index_url"}]}},
}


def test_upgrade_action_uses_explicit_upgrade_section():
    cmds = rp.commands_for(UP_RECIPE, "upgrade", host="linux")
    assert cmds[0] == "demo teardown", "显式 upgrade 段优先（升前拆摊子等人话语义位）"
    assert "npm install -g demo@2.0.0" in cmds[1]


def test_upgrade_falls_back_to_install_section():
    cmds = rp.commands_for(FALLBACK_RECIPE, "upgrade", host="linux")
    assert cmds == ["pip install demo==1.2.3"], "upgrade 段缺位=同构复用 install，禁凭空造命令"


def test_upgrade_mirror_rewrite_applies():
    cmds = rp.commands_for(FALLBACK_RECIPE, "upgrade", host="linux",
                           mirror_base_url="http://mirror.example.net/m")
    assert cmds[0].endswith(" -i http://mirror.example.net/m/simple"), "镜像改道与 install 同谱"


def test_target_pin_rewrites_pin_for_rollback():
    pass
    cmds = rp.commands_for(UP_RECIPE, "upgrade", host="linux", target_pin="1.9.0")
    assert cmds[1] == "npm install -g demo@1.9.0"
    assert "2.0.0" not in " ".join(cmds), "钉残留=回滚会装回新版"


def test_target_pin_only_touches_pinned_commands():
    cmds = rp.commands_for(UP_RECIPE, "upgrade", host="linux", target_pin="1.9.0")
    assert cmds[0] == "demo teardown", "不含钉的命令零改写"


def test_target_pin_invalid_without_recipe_pin_human():
    with pytest.raises(ValueError) as e:
        rp.commands_for({"hosts": {"linux": {"install": [{"cmd": "install.sh"}]}}},
                        "upgrade", host="linux", target_pin="1.0.0")
    assert "version pin" in str(e.value), \
        "reverse-pinning a pin-less recipe is refused in plain words (no fake rollback)"


def test_cli_upgrade_action_and_target_pin(tmp_path, capsys):
    f = tmp_path / "r.json"
    f.write_text(json.dumps(UP_RECIPE), encoding="utf-8")
    assert rp.main(["upgrade", "--recipe-file", str(f), "--host", "linux"]) == 0
    lines = capsys.readouterr().out.splitlines()
    assert lines[0] == "demo teardown"
    assert rp.main(["upgrade", "--recipe-file", str(f), "--host", "linux",
                    "--target-pin", "1.9.0"]) == 0
    assert "demo@1.9.0" in capsys.readouterr().out


def test_v178_check_covers_six_types_and_upgrade_seed():
    pass
    sql = MIG_DIR / "V178__command_upgrade_types_and_recipe.sql"
    assert sql.exists(), "V178 迁移文件必须存在"
    text = sql.read_text(encoding="utf-8")
    for t in ["engine-install", "engine-uninstall", "engine-upgrade",
              "engine-rollback", "scale-in", "node-remove"]:
        assert f"'{t}'" in text, f"CHECK 扩型缺 {t}"
    m = re.search(r"'\{hosts,macos,upgrade\}',\s*'(.*?)'::jsonb", text, re.DOTALL)
    assert m, "XCUITEST upgrade 段种子必须存在"
    upgrade = json.loads(m.group(1))
    assert upgrade[0]["cmd"] == "appium driver remove xcuitest", "换版驱动先摘后装（禁残留）"
    assert "xcuitest@12.15.0" in upgrade[1]["cmd"]
    assert rp.commands_for(V173_XCU_RECIPE, "upgrade", host="macos"), \
        "XCUITEST upgrade 段可被单源件消费"
    with pytest.raises(ValueError):
        rp.commands_for(V173_XCU_RECIPE, "upgrade", host="linux")
