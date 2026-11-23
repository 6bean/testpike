
from __future__ import annotations

TOLERANCE_MAX = 1.0


class VisualAssertViolation(ValueError):
    pass


def validate_visual_assert_step(step: dict) -> None:
    pass
    _check_baseline_key(step.get("baselineKey"))
    _check_tolerance(step.get("tolerance"))
    _check_region(step.get("region"))


def _check_baseline_key(value) -> None:
    if value is None:
        return
    if not isinstance(value, str) or not value.strip():
        raise VisualAssertViolation(
            "visual.assert 的 baselineKey 非法（给定时须为非空字符串基线对象键，"
            "缺省=按步骤稳定键自动定位当前生效基线）")


def _check_tolerance(value) -> None:
    if value is None:
        return
    if isinstance(value, bool) or not isinstance(value, (int, float)) \
            or value < 0 or value > TOLERANCE_MAX:
        raise VisualAssertViolation(
            f"visual.assert 的 tolerance 非法（{value}）——须为 0-1 之间的数值"
            "（差异像素占比，0=逐像素全等，0.02=容忍 2% 差异）")


def _check_region(region) -> None:
    if region is None:
        return
    if not isinstance(region, dict):
        raise VisualAssertViolation(_region_error(str(region)))
    for field in ("x", "y", "w", "h"):
        value = region.get(field)
        if isinstance(value, bool) or not isinstance(value, int):
            shown = "缺失" if value is None else value
            raise VisualAssertViolation(_region_error(f"{field}={shown}"))
    if region["x"] < 0 or region["y"] < 0 or region["w"] < 1 or region["h"] < 1:
        raise VisualAssertViolation(_region_error(
            f"x={region['x']},y={region['y']},w={region['w']},h={region['h']}"))


def _region_error(detail: str) -> str:
    return (f"visual.assert 的 region 非法（{detail}）——须为 {{x,y,w,h}} 四个整数字段"
            "（坐标非负、宽高至少 1 像素；缺省=全图比对）")
