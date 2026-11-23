#!/usr/bin/env python3
"""CPU OCR worker shipped with the repo .

Contract: one JSON object on stdin {"image_base64": "<png/jpeg bytes b64>",
"red_min_area_px": <optional int>} -> one JSON object on stdout
{"lines":[{"box":[[x,y] x4],"text":str,"score":str,"annotated":bool}]}.
``annotated`` (piece C) marks lines covered by a user red-pen annotation:
HSV red blocks at or above the area floor are found with plain OpenCV (zero
model calls), and a line is annotated when its box centre falls inside such a
block - so a row-shaped box marks one row and a column-shaped box marks the
cells in its x-band. Any failure exits non-zero with a one-line reason on
stderr, including the red pass: folding a detector failure into "no
annotations" would silently un-check what the user marked. Screenshot bytes
never leave this process over the network and are never logged (credential
screenshots are parsed in-memory then dropped, piece E governance red line).

Engine = RapidOCR (Apache-2.0) on the onnxruntime CPU backend; bundled model
weights originate from the official PaddleOCR releases (Apache-2.0, attribution
in docs/19 open-source ledger). Install: pip3 install --user
rapidocr_onnxruntime==1.2.3 (cp314 wheels verified 2026-11-14). The red pass
reuses the cv2/numpy stack that dependency already pulls in - no second image
pipeline elsewhere (R40 single source).
"""
import base64
import json
import sys
import tempfile

DEFAULT_RED_MIN_AREA_PX = 800

# Pen red sits in the two hue bands around 0/180; the saturation and value
# floors keep grey UI pixels and dark text out of the mask. Standard red-domain
# definition, not a per-install tuning knob (the area floor is, via the envelope).
_RED_HUE_BANDS = ((0, 70, 70, 10, 255, 255), (170, 70, 70, 180, 255, 255))
_CLOSE_KERNEL_PX = 9


def red_block_rects(image_bytes, min_area_px=DEFAULT_RED_MIN_AREA_PX):
    """Bounding rects (x0, y0, x1, y1) of annotation-sized red blocks.

    A morphological close welds dashed strokes and white glyph holes into one
    component per drawn shape; RETR_EXTERNAL then reports the outline, whose
    bounding rectangle is the annotation area.
    """
    import cv2
    import numpy as np

    image = cv2.imdecode(np.frombuffer(image_bytes, dtype=np.uint8), cv2.IMREAD_COLOR)
    if image is None:
        raise ValueError("red pass could not decode the screenshot")
    hsv = cv2.cvtColor(image, cv2.COLOR_BGR2HSV)
    mask = cv2.bitwise_or(
        cv2.inRange(hsv,
                    np.array(_RED_HUE_BANDS[0][:3], dtype=np.uint8),
                    np.array(_RED_HUE_BANDS[0][3:], dtype=np.uint8)),
        cv2.inRange(hsv,
                    np.array(_RED_HUE_BANDS[1][:3], dtype=np.uint8),
                    np.array(_RED_HUE_BANDS[1][3:], dtype=np.uint8)))
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE,
                            np.ones((_CLOSE_KERNEL_PX, _CLOSE_KERNEL_PX), np.uint8))
    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    rects = []
    for contour in contours:
        x, y, w, h = cv2.boundingRect(contour)
        if w * h >= min_area_px:
            rects.append((x, y, x + w, y + h))
    return rects


def annotate_lines(lines, rects):
    """Per-line flags: box centre inside any red block rect (row/column boxes).

    A line without usable geometry is reported unmarked; it never crashes the
    pass and never guesses a mark.
    """
    flags = []
    for line in lines:
        points = line.get("box") or []
        if not points:
            flags.append(False)
            continue
        xs = [point[0] for point in points]
        ys = [point[1] for point in points]
        centre_x = (min(xs) + max(xs)) / 2.0
        centre_y = (min(ys) + max(ys)) / 2.0
        flags.append(any(x0 <= centre_x <= x1 and y0 <= centre_y <= y1
                         for x0, y0, x1, y1 in rects))
    return flags


def _red_min_area(payload) -> int:
    """Install-tunable area floor from the envelope; garbage falls back to the default."""
    try:
        value = int(payload.get("red_min_area_px"))
    except (TypeError, ValueError):
        return DEFAULT_RED_MIN_AREA_PX
    return value if value > 0 else DEFAULT_RED_MIN_AREA_PX


def main() -> int:
    try:
        payload = json.loads(sys.stdin.read() or "{}")
        image_b64 = payload["image_base64"]
        raw = base64.b64decode(image_b64)
    except Exception as exc:  # bad envelope is a caller bug, fail loudly
        print("bad request envelope: %s" % exc, file=sys.stderr)
        return 2

    fd, tmp_path = tempfile.mkstemp(suffix=".png")
    try:
        with open(fd, "wb") as handle:
            handle.write(raw)
        from rapidocr_onnxruntime import RapidOCR

        result, _ = RapidOCR()(tmp_path)
        lines = [
            {"box": [[float(x), float(y)] for x, y in box], "text": text, "score": str(score)}
            for box, text, score in (result or [])
        ]
        rects = red_block_rects(raw, _red_min_area(payload))
        for line, flag in zip(lines, annotate_lines(lines, rects)):
            line["annotated"] = bool(flag)
        json.dump({"lines": lines}, sys.stdout)
        sys.stdout.flush()
        return 0
    except Exception as exc:
        print("ocr inference failed: %s" % exc, file=sys.stderr)
        return 1
    finally:
        # R57: the temp image is a credential carrier; terminal-state delete
        # covers success and failure branches alike.
        try:
            import os

            os.unlink(tmp_path)
        except OSError:
            pass


if __name__ == "__main__":
    sys.exit(main())
