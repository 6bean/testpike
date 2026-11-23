"""Shared collection bootstrap for the executor test spectra.

Makes the executor root importable (``capture``/``protocol`` packages) from
any working directory, so every test file consumes this single source instead
of repeating the sys.path block (R40).
"""
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
