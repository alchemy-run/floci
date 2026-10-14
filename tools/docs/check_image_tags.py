#!/usr/bin/env python3
"""Reject undocumented JVM tags except Alchemy's published release images."""
from __future__ import annotations

import re
import sys
from pathlib import Path

# alchemy-release.yml publishes this suffix; upstream release.yml does not.
ALCHEMY_JVM_IMAGE_RE = re.compile(
    r"(?<![\w./-])ghcr\.io/alchemy-run/floci:"
    r"(?:[0-9]+\.[0-9]+\.[0-9]+-alchemy\.[0-9]+|<tag>)-jvm"
    r"(?=$|[\s`\"'|),;])"
)


def invalid_jvm_tag_lines(text: str) -> list[tuple[int, str]]:
    """Keep unrelated references visible even beside a valid fork image."""
    return [
        (number, line)
        for number, line in enumerate(text.splitlines(), 1)
        if "-jvm" in ALCHEMY_JVM_IMAGE_RE.sub("", line)
    ]


def main() -> int:
    root = Path(__file__).resolve().parent.parent.parent
    paths = sorted(path for path in (root / "docs").rglob("*") if path.is_file())
    paths.extend(root / name for name in ("README.md", "CONTRIBUTING.md"))
    invalid = False
    for path in paths:
        for number, line in invalid_jvm_tag_lines(path.read_text(encoding="utf-8", errors="replace")):
            print(f"{path.relative_to(root)}:{number}:{line}", file=sys.stderr)
            invalid = True
    if invalid:
        print(
            "error: unsupported '-jvm' reference; only fully qualified Alchemy release "
            "images published by alchemy-release.yml may use this suffix.",
            file=sys.stderr,
        )
    return int(invalid)


if __name__ == "__main__":
    sys.exit(main())
