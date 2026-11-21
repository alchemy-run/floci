#!/usr/bin/env python3
"""Vendor the DICOM data dictionary (tag, VR, VM, keyword) from pydicom's MIT-licensed copy of DICOM PS3.6.

HealthImaging reads implicit VR DICOM files, whose elements carry no VR, so Floci needs the dictionary to decode
them and the keywords to name attributes in image set metadata. Run with a Python that has pydicom installed:

    python3 -m pip install pydicom && python3 tools/aws/regen_dicom_dictionary.py [--check]
"""
from __future__ import annotations

import argparse
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
OUTPUT = REPO_ROOT / "src/main/resources/aws/medical-imaging/dicom-dictionary.tsv"


def render() -> str:
    import pydicom
    from pydicom.datadict import DicomDictionary

    lines = [
        f"# DICOM PS3.6 data dictionary from pydicom {pydicom.__version__} (MIT License, Copyright (c) 2008-2020"
        " Darcy Mason and pydicom contributors).",
        "# Regenerate with tools/aws/regen_dicom_dictionary.py. Columns: tag, VR, VM, keyword.",
    ]
    for tag in sorted(DicomDictionary):
        vr, vm, _name, _retired, keyword = DicomDictionary[tag]
        if not keyword:
            continue
        lines.append(f"{tag:08X}\t{vr.split(' ')[0]}\t{vm}\t{keyword}")
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    fresh = render()
    if args.check:
        return int(not OUTPUT.exists() or OUTPUT.read_text() != fresh)
    OUTPUT.write_text(fresh)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
