#!/usr/bin/env python3
"""Vendor the REST-XML wire model of a botocore service for `RestXmlCodec`.

For every operation the output keeps its HTTP method and URI, its input and output shapes, the
input's payload member when it has one, and the XML root element name its body uses. For every
shape reachable from them it keeps what the codec needs to read and write XML: the type, each
member's shape, wire location (`uri`, `querystring`, `header`, `headers`, or the body),
`locationName`, `xmlAttribute` and `xmlNamespace`; a list's member shape, member `locationName`
and `flattened`; a map's key and value shapes and names; and enum values for documentation only.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from regen_partitions import resolve_botocore_data, strip_source
from request_shapes import REPO_ROOT, load_model

SERVICES = {"s3control": "s3control/2018-08-20"}


def build(model: dict, provenance: str) -> dict:
    shapes = model["shapes"]
    kept: dict[str, dict] = {}

    def keep(name: str) -> None:
        if name in kept:
            return
        shape = shapes[name]
        entry: dict = {"type": shape["type"]}
        kept[name] = entry
        if shape["type"] == "structure":
            members = {}
            for member, spec in shape.get("members", {}).items():
                m = {"shape": spec["shape"]}
                for field in ("location", "locationName", "xmlAttribute", "xmlNamespace"):
                    if field in spec:
                        m[field] = spec[field]
                members[member] = m
                keep(spec["shape"])
            entry["members"] = members
            if "required" in shape:
                entry["required"] = shape["required"]
            if shape.get("payload"):
                entry["payload"] = shape["payload"]
        elif shape["type"] == "list":
            entry["member"] = {k: v for k, v in shape["member"].items() if k in ("shape", "locationName")}
            if shape.get("flattened"):
                entry["flattened"] = True
            keep(shape["member"]["shape"])
        elif shape["type"] == "map":
            entry["key"] = {k: v for k, v in shape["key"].items() if k in ("shape", "locationName")}
            entry["value"] = {k: v for k, v in shape["value"].items() if k in ("shape", "locationName")}
            if shape.get("flattened"):
                entry["flattened"] = True
            keep(shape["key"]["shape"])
            keep(shape["value"]["shape"])
        elif shape["type"] == "timestamp" and "timestampFormat" in shape:
            entry["timestampFormat"] = shape["timestampFormat"]

    operations = {}
    for name, operation in sorted(model["operations"].items()):
        op = {"method": operation["http"]["method"], "uri": operation["http"]["requestUri"]}
        if "input" in operation:
            op["input"] = operation["input"]["shape"]
            if "locationName" in operation["input"]:
                op["inputRoot"] = operation["input"]["locationName"]
            keep(operation["input"]["shape"])
        if "output" in operation:
            op["output"] = operation["output"]["shape"]
            keep(operation["output"]["shape"])
        operations[name] = op

    return {
        "_source": {"generator": "tools/aws/regen_rest_xml_shapes.py", "botocore": provenance},
        "namespace": model["metadata"].get("xmlNamespace", {}).get("uri") if isinstance(
            model["metadata"].get("xmlNamespace"), dict) else model["metadata"].get("xmlNamespace"),
        "operations": operations,
        "shapes": dict(sorted(kept.items())),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--botocore-data", type=Path)
    args = parser.parse_args()
    data, provenance = resolve_botocore_data(args.botocore_data)
    stale = 0
    for service, path in SERVICES.items():
        fresh = json.dumps(build(load_model(data, Path(path)), provenance), indent=1) + "\n"
        target = REPO_ROOT / f"src/main/resources/aws/{service}-rest-xml.json"
        if args.check:
            stale |= not target.exists() or strip_source(target.read_text()) != strip_source(fresh)
        else:
            target.write_text(fresh)
    return int(stale)


if __name__ == "__main__":
    raise SystemExit(main())
