#!/usr/bin/env python3
"""Vendor Amazon ECR Public request constraints from botocore's Apache-2.0 service models."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from regen_partitions import resolve_botocore_data, strip_source
from request_shapes import REPO_ROOT, build_constraints, load_model, widen_partition_and_region

SERVICES = {"ecrpublic": "ecr-public/2020-10-30"}


def output(service: str) -> Path:
    return REPO_ROOT / f"src/main/resources/aws/{service}-request-shapes.json"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--botocore-data", type=Path)
    args = parser.parse_args()
    data, provenance = resolve_botocore_data(args.botocore_data)
    stale = 0
    for service, path in SERVICES.items():
        model = widen_partition_and_region(load_model(data, Path(path)))
        operations = tuple(sorted(name for name, op in model["operations"].items() if "input" in op))
        doc = build_constraints(model, operations, provenance, "tools/aws/regen_ecrpublic_shapes.py")
        fresh = json.dumps(doc, indent=2) + "\n"
        target = output(service)
        if args.check:
            stale |= not target.exists() or strip_source(target.read_text()) != strip_source(fresh)
        else:
            target.write_text(fresh)
    return int(stale)


if __name__ == "__main__":
    raise SystemExit(main())
