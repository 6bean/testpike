
from __future__ import annotations

import importlib

_BUILTIN_LOADERS: dict = {
    "FAKE-L2": ("driver.mobile.stub_adapter", "StubEngineAdapter"),
    "MOBILEWRIGHT": ("driver.mobile.mobilewright_adapter", "MobilewrightAdapter"),
    "MINIUM": ("driver.mobile.minium_adapter", "MiniumAdapter"),
    "MINIPROGRAM_AUTOMATOR": ("driver.mobile.miniprogram_automator_adapter", "AutomatorAdapter"),
    "MAESTRO": ("driver.mobile.maestro_adapter", "MaestroAdapter"),
    "APPIUM": ("driver.mobile.appium_adapter", "AppiumAdapter"),
    "XCUITEST": ("driver.mobile.appium_adapter", "AppiumAdapter"),
    "HMNEXTAUTO": ("driver.mobile.harmony_adapter", "HarmonyAdapter"),
}


def mobile_engine_factory(descriptor: dict | None):
    pass
    if not descriptor:
        raise ValueError("引擎描述符缺失（派发信封未带 engine 键），无法装载适配器——"
                         "请检查 core 派发装配面")
    code = str(descriptor.get("code") or "").strip().upper()
    if int(descriptor.get("status", 1)) != 1:
        raise ValueError(f"引擎 {code or '（空）'} 已停用（描述符 status=0），"
                         "禁止装载——候补/下架引擎不出现在执行面")
    spec_module = str(descriptor.get("loaderModule") or "").strip()
    spec_class = str(descriptor.get("loaderClass") or "").strip()
    if spec_module and spec_class:
        return _load_class(spec_module, spec_class, code, descriptor)
    entry = _BUILTIN_LOADERS.get(code)
    if entry is None:
        raise ValueError(f"引擎 {code} 不在引擎装载面（适配器未落位或描述符装载规格缺失）"
                        "——六引擎适配器随各自任务包交付，请确认引擎已开通执行接入")
    return _load_class(entry[0], entry[1], code, descriptor)


def _load_class(module_name: str, class_name: str, code: str, descriptor: dict):
    try:
        module = importlib.import_module(module_name)
        cls = getattr(module, class_name)
    except (ImportError, AttributeError) as e:
        raise ValueError(f"引擎 {code} 适配器装载失败（{module_name}.{class_name}）: {e}") from e
    return cls(descriptor=descriptor) if _takes_descriptor(cls) else cls()


def _takes_descriptor(cls) -> bool:
    try:
        import inspect
        sig = inspect.signature(cls.__init__)
        return "descriptor" in sig.parameters
    except (TypeError, ValueError):
        return False
