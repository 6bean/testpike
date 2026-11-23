
from __future__ import annotations

import json
import os
import re
from typing import Optional

from actions_common import IR_VERBS

MP_DOUBLE_TAP_INTERVAL_ENV_KEY = "APPAUTO_MP_DOUBLE_TAP_INTERVAL_MS"
MP_DOUBLE_TAP_INTERVAL_DEFAULT_MS = 200

MP_DEFAULT_AUTO_PORT = 9420

#

WEBVIEW_HYBRID_CAP = "webview-hybrid"
WEBVIEW_STEP_FLAG = "webview"
_WEBVIEW_SUGGEST_ENGINES = "Appium(WEBVIEW context)/automator(web 节点)/Mobilewright(无障碍树穿透)"


def capabilities_of(descriptor: dict) -> dict:
    pass
    caps = (descriptor or {}).get("capabilities")
    if isinstance(caps, str):
        try:
            caps = json.loads(caps)
        except json.JSONDecodeError:
            return {}
    return caps if isinstance(caps, dict) else {}


def step_requests_webview(step: dict) -> bool:
    pass
    return isinstance(step, dict) and step.get(WEBVIEW_STEP_FLAG) is True


def is_hybrid_carrier(env_profile: dict) -> bool:
    pass
    return isinstance(env_profile, dict) and env_profile.get("hybrid") is True


def webview_step_violation(descriptor: dict, step: dict,
                           engine_implemented: bool = True) -> dict | None:
    pass
    if not step_requests_webview(step):
        return None
    caps = capabilities_of(descriptor)
    code = str((descriptor or {}).get("code") or "（未知引擎）").strip()
    if caps.get(WEBVIEW_HYBRID_CAP) is not True:
        return {"result": "fail", "errorType": 1,
                "detail": f"引擎 {code} 不支持混合 WebView 定位——该步标记落 WebView 区域"
                          "（webview 旗标）但引擎描述符未声明 webview-hybrid 能力位；"
                          f"建议换 {_WEBVIEW_SUGGEST_ENGINES} 任一支持混合载体的引擎执行"
                          "（禁静默回落原生层冒充命中）"}
    if not engine_implemented:
        return {"result": "fail", "errorType": 1,
                "detail": f"引擎 {code} 描述符已声明 webview-hybrid 能力位，但适配器 "
                          "WebView 落区面未实现（两侧漂移——请核对引擎版本与适配器装载面，"
                          "或换已接通 WebView 面的引擎执行该步）"}
    return None



def verb_capability_violation(descriptor: dict, verb: str) -> dict | None:
    pass
    caps = capabilities_of(descriptor)
    if verb not in caps or caps.get(verb) is not False:
        return None
    code = str((descriptor or {}).get("code") or "（未知引擎）").strip()
    return {"result": "fail", "errorType": 1,
            "detail": f"该引擎不支持 {verb}——引擎 {code} 描述符能力位明确未声明"
                      f" {verb}（尽力位宁缺毋滥，冻结 §13.1）；请换已声明该能力的"
                      "引擎执行该步（IR 动词不变，换引擎零改用例），或调整用例不走该步"}


def require_step_param(step: dict, key: str, verb: str) -> str:
    pass
    val = str(step.get(key) or "").strip()
    if not val:
        raise ValueError(f"{verb} 缺 {key} 参数（步骤信封为空）——请修正脚本步参数")
    return val


def mp_double_tap_interval_ms() -> float:
    pass
    raw = os.environ.get(MP_DOUBLE_TAP_INTERVAL_ENV_KEY, "").strip()
    try:
        return max(float(raw) if raw else MP_DOUBLE_TAP_INTERVAL_DEFAULT_MS, 1.0)
    except ValueError:
        return MP_DOUBLE_TAP_INTERVAL_DEFAULT_MS


def parse_collect_frame(res):
    pass
    if not isinstance(res, dict):
        return None
    arts = res.get("artifacts")
    if isinstance(arts, list):
        out = [a for a in arts if isinstance(a, dict) and a.get("localPath")]
        return out or None
    return res if res.get("localPath") else None


#

MONITOR_FRAME_MAX_WIDTH_ENV_KEY = "APPAUTO_MONITOR_FRAME_MAX_WIDTH"
MONITOR_FRAME_MAX_WIDTH_DEFAULT = 480

_CRED_REF = "${CRED"
_CRED_REF_NEW = "{{CRED:"
_CRED_REF_FORMS = (_CRED_REF, _CRED_REF_NEW)
_CRED_PLACEHOLDER = re.compile(r"\{\{CRED:([^{}]*)\}\}")


def unresolved_cred_placeholders(step) -> list:
    pass
    if not isinstance(step, dict):
        return []
    try:
        dumped = json.dumps(step, ensure_ascii=False)
    except Exception:
        return []
    return _CRED_PLACEHOLDER.findall(dumped)


def monitor_frame_max_width() -> int:
    pass
    raw = os.environ.get(MONITOR_FRAME_MAX_WIDTH_ENV_KEY, "").strip()
    try:
        return max(int(raw) if raw else MONITOR_FRAME_MAX_WIDTH_DEFAULT, 160)
    except ValueError:
        return MONITOR_FRAME_MAX_WIDTH_DEFAULT


def downsample_to_frame(local_path, max_width=None):
    pass
    if max_width is None:
        max_width = monitor_frame_max_width()
    try:
        import base64
        import io

        from PIL import Image

        with Image.open(local_path) as img:
            img = img.convert("RGB")
            if img.width > max_width:
                h = max(1, round(img.height * max_width / img.width))
                img = img.resize((max_width, h))
            buf = io.BytesIO()
            img.save(buf, format="JPEG", quality=70)
        return base64.b64encode(buf.getvalue()).decode("ascii")
    except Exception:
        return None


def monitor_frame_from_artifacts(items, max_width=None):
    pass
    src = [items] if isinstance(items, dict) else (items if isinstance(items, list) else [])
    shot = next((a for a in src
                 if isinstance(a, dict) and a.get("kind") == "screenshot" and a.get("localPath")),
                None)
    if shot is None:
        return None
    image = downsample_to_frame(shot["localPath"], max_width=max_width)
    return {"image": image} if image else None


def step_references_credential(step) -> bool:
    pass
    if not isinstance(step, dict):
        return False
    try:
        dumped = json.dumps(step, ensure_ascii=False)
    except Exception:
        return False
    return any(form in dumped for form in _CRED_REF_FORMS)


def bridge_watch_frame(prepared, bridge, artifact_dir, max_width=None):
    pass
    if not prepared or bridge is None:
        return None
    try:
        res = bridge.call("collect", {"stepNo": 0, "artifactDir": artifact_dir})
    except Exception:
        return None
    return monitor_frame_from_artifacts(parse_collect_frame(res), max_width=max_width)


def capability_violations(adapter, descriptor: dict) -> Optional[str]:
    pass
    if adapter is None or not descriptor:
        return "适配器或描述符缺失，无法对账"
    platforms = tuple(getattr(adapter, "SUPPORTED_PLATFORMS", ()))
    kinds = tuple(getattr(adapter, "SUPPORTED_DEVICE_KINDS", ()))
    caps = tuple(getattr(adapter, "CAPABILITIES", IR_VERBS))
    if not platforms or not kinds:
        return (f"引擎 {descriptor.get('code')} 适配器自述不完整"
                f"（平台={platforms or '缺'} 设备型={kinds or '缺'}）")
    desc_platforms = set(descriptor.get("platformCodes") or [])
    desc_kinds = set(descriptor.get("deviceKindCodes") or [])
    over_platforms = [p for p in platforms if p not in desc_platforms]
    over_kinds = [k for k in kinds if k not in desc_kinds]
    if over_platforms or over_kinds:
        return (f"引擎 {descriptor.get('code')} 适配器声明超出描述符面："
                f"平台 {over_platforms or '无'} / 设备型 {over_kinds or '无'}"
                "（描述符目录=能力权威单源，两侧漂移请同批改描述符种子与适配器）")
    extra_caps = [c for c in caps if c not in set(IR_VERBS)]
    if extra_caps:
        return (f"引擎 {descriptor.get('code')} 适配器声明了 IR 基线外动词 "
                f"{extra_caps}（词表单源=actions_common.IR_VERBS，禁两侧漂移）")
    return None
