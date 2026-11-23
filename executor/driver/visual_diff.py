
from __future__ import annotations

import io

from PIL import Image

_REGION_FIELDS = ("x", "y", "w", "h")


def _decode(png_bytes: bytes) -> Image.Image:
    return Image.open(io.BytesIO(png_bytes)).convert("RGBA")


def _crop(img: Image.Image, region: dict | None) -> Image.Image:
    if region is None:
        return img
    x, y = region["x"], region["y"]
    return img.crop((x, y, x + region["w"], y + region["h"]))


def diff_ratio(baseline_png: bytes, current_png: bytes,
               region: dict | None = None) -> float:
    pass
    base = _decode(baseline_png)
    current = _decode(current_png)
    if base.size != current.size:
        raise ValueError(
            f"截图尺寸与基线不一致（现状 {current.size[0]}×{current.size[1]}，"
            f"基线 {base.size[0]}×{base.size[1]}）——无法逐像素比对，"
            "请确认页面视口/布局未改版或重新确认基线")
    base = _crop(base, region)
    current = _crop(current, region)
    total = base.width * base.height
    if total <= 0:
        raise ValueError("比对区域像素数为 0（region 越界？）——拒绝空区域假通过")
    ba, bb = base.tobytes(), current.tobytes()
    diff = sum(1 for i in range(0, total * 4, 4) if ba[i:i + 4] != bb[i:i + 4])
    return diff / total


def overlay_diff_png(baseline_png: bytes, current_png: bytes) -> bytes:
    pass
    base = _decode(baseline_png)
    current = _decode(current_png)
    if base.size != current.size:
        raise ValueError("两图尺寸不一致，无法生成差异叠加图")
    out = Image.new("RGBA", base.size)
    ba, bb = base.tobytes(), current.tobytes()
    data = bytearray()
    for i in range(0, base.width * base.height * 4, 4):
        data += _blend(ba[i:i + 4], bb[i:i + 4])
    out.frombytes(bytes(data))
    buf = io.BytesIO()
    out.save(buf, format="PNG")
    return buf.getvalue()


def _blend(a: bytes, b: bytes) -> bytes:
    if a == b:
        return bytes((60, 60, 60, 255))
    a_blank, b_blank = a[3] == 0, b[3] == 0
    if b_blank and not a_blank:
        return bytes((255, 0, 0, 255))
    if a_blank and not b_blank:
        return bytes((0, 255, 0, 255))
    return bytes((255, 255, 0, 255))
