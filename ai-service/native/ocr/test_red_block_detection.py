#!/usr/bin/env python3
"""Behaviour fixtures for red-annotation detection on panel screenshots.

Part of the OCR worker contract : the user circles the rows to
register in red inside a screenshot; the worker must flag every OCR line whose
box centre falls inside a red annotation block. Pure image processing, no
model calls.

Run directly (no pytest required):
 python3 test_red_block_detection.py
Exit 0 = all cases pass. Bidirectional by design: every positive form must
produce a hit, and the no-annotation negative must return exactly zero rects
(a detector that silently fails would look identical to "user marked
nothing", which must never happen unchecked).

# deadcode-keep: standalone behaviour fixture for the ocr_worker red pass;
# entry point is __main__ and it is invoked directly by the verification
# windows (no in-repo importer by design, same shape as scripts/selftest_*).
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import cv2
import numpy as np

from ocr_worker import DEFAULT_RED_MIN_AREA_PX, annotate_lines, red_block_rects

RED_BGR = (40, 40, 235)  # marker-pen red, same family Chrome annotators draw


def _canvas(width=900, height=500):
    return np.full((height, width, 3), 245, dtype=np.uint8)


def _png(image):
    ok, buf = cv2.imencode(".png", image)
    assert ok, "fixture canvas must encode to png"
    return buf.tobytes()


def _line(x0, y0, x1, y1, text="cell"):
    return {"box": [[x0, y0], [x1, y0], [x1, y1], [x0, y1]], "text": text, "score": "0.99"}


def case_row_box():
    """A horizontal box around one table row marks that row only."""
    image = _canvas()
    cv2.rectangle(image, (40, 200), (860, 240), RED_BGR, 4)
    rects = red_block_rects(_png(image), DEFAULT_RED_MIN_AREA_PX)
    assert len(rects) == 1, "row box must yield exactly one red block, got %r" % (rects,)
    flags = annotate_lines([_line(50, 205, 300, 235), _line(50, 260, 300, 290)], rects)
    assert flags == [True, False], "inside-row cell marked, below-row cell not, got %r" % (flags,)


def case_column_box():
    """A tall box around one column marks only cells in that x-band."""
    image = _canvas()
    cv2.rectangle(image, (400, 100), (500, 420), RED_BGR, 4)
    rects = red_block_rects(_png(image), DEFAULT_RED_MIN_AREA_PX)
    assert len(rects) == 1, "column box must yield exactly one red block, got %r" % (rects,)
    flags = annotate_lines(
        [_line(410, 120, 490, 150, "in-top"), _line(600, 120, 700, 150, "out-x"),
         _line(410, 450, 490, 470, "out-y")],
        rects)
    assert flags == [True, False, False], "column band marks its own cells only, got %r" % (flags,)


def case_red_fill_white_text():
    """Red background behind white text is an annotation block, holes and all."""
    image = _canvas()
    cv2.rectangle(image, (40, 100), (400, 140), RED_BGR, -1)
    cv2.putText(image, "SESSION", (60, 130), cv2.FONT_HERSHEY_SIMPLEX, 0.9, (255, 255, 255), 2)
    rects = red_block_rects(_png(image), DEFAULT_RED_MIN_AREA_PX)
    assert len(rects) == 1, "filled red band must yield one block despite white glyphs, got %r" % (rects,)
    flags = annotate_lines([_line(55, 108, 240, 134)], rects)
    assert flags == [True], "cell on red background must be marked, got %r" % (flags,)


def case_no_red_negative():
    """A clean panel (plus sub-threshold red specks) must report exactly zero blocks."""
    image = _canvas()
    cv2.line(image, (40, 60), (860, 60), (180, 180, 180), 1)   # table grid line
    cv2.rectangle(image, (100, 300), (120, 320), RED_BGR, -1)  # 20x20 speck, area < default floor
    rects = red_block_rects(_png(image), DEFAULT_RED_MIN_AREA_PX)
    assert rects == [], "negative fixture must produce zero red blocks, got %r" % (rects,)
    flags = annotate_lines([_line(400, 50, 700, 70), _line(90, 290, 130, 330)], rects)
    assert flags == [False, False], "nothing may be marked without a block, got %r" % (flags,)


def case_missing_geometry_is_unsafe_false():
    """A line without usable geometry is reported unmarked, never crash-true."""
    assert annotate_lines([{"box": [], "text": "x"}], [(0, 0, 900, 500)]) == [False]


def main() -> int:
    cases = [case_row_box, case_column_box, case_red_fill_white_text,
             case_no_red_negative, case_missing_geometry_is_unsafe_false]
    for case in cases:
        case()
        print("PASS %s" % case.__name__)
    print("red block detection fixtures: %d/%d cases passed" % (len(cases), len(cases)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
