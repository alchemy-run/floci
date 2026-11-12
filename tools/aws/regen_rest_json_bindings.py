#!/usr/bin/env python3
"""Vendor the REST JSON HTTP bindings of the services `RestJsonDispatcher` routes, from botocore's models.

For each operation this records what a REST JSON request and response carry where: its method, request URI and
response code, each input member bound to the URI, the query string, a header or a header prefix, or the body
(only a payload member, or an idempotency token, is recorded: the rest of a structure body is read as it is), and
each output member bound to a header, a header prefix, the status code or a payload. A model whose body members go
by other names on the wire (`flowArn` for `FlowArn`) also records its shapes, each structure's members with their
wire names, so the dispatcher hands handlers member names and answers with wire names. Members carry their type when the binding needs it to read a string (`integer`, `long`, `boolean`,
`double`, `float`, `timestamp`, `list`, `blob`, `structure`). botocore's models are Apache-2.0
(https://github.com/boto/botocore), read at their latest API version as `regen_query_services.py` reads them.

Run from anywhere in the repo:
    python3 tools/aws/regen_rest_json_bindings.py            # rewrite the vendored files in place
    python3 tools/aws/regen_rest_json_bindings.py --check    # exit 1 when a vendored file is stale
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from regen_partitions import resolve_botocore_data, strip_source
from regen_query_services import latest_model_dir, load_model

REPO_ROOT = Path(__file__).resolve().parents[2]
OUTPUT = REPO_ROOT / "src/main/resources/aws/rest-json"

# botocore model -> the vendored file's name.
MODELS = {
    "datazone": "datazone",
    "deadline": "deadline",
    "devops-guru": "devopsguru",
    "docdb-elastic": "docdbelastic",
    "emr-containers": "emrcontainers",
    "entityresolution": "entityresolution",
    "mediaconnect": "mediaconnect",
    "mediaconvert": "mediaconvert",
    "medialive": "medialive",
    "mediapackagev2": "mediapackagev2",
    "mediatailor": "mediatailor",
    "medical-imaging": "medicalimaging",
    "neptune-graph": "neptunegraph",
    "notifications": "notifications",
    "notificationscontacts": "notificationscontacts",
    "osis": "osis",
    "observabilityadmin": "observabilityadmin",
    "omics": "omics",
    "personalize-runtime": "personalizeruntime",
    "personalize-events": "personalizeevents",
    "qbusiness": "qbusiness",
    "qapps": "qapps",
    "rbin": "rbin",
    "repostspace": "repostspace",
    "rolesanywhere": "rolesanywhere",
    "route53profiles": "route53profiles",
    "s3files": "s3files",
    "ssm-incidents": "ssmincidents",
    "schemas": "schemas",
    "securitylake": "securitylake",
}

TYPED = {"integer", "long", "boolean", "double", "float", "timestamp", "list", "map", "blob", "structure"}


def member(model: dict, spec: dict) -> dict:
    entry = {"location": spec.get("location", "body")}
    if spec.get("locationName"):
        entry["name"] = spec["locationName"]
    shape = model["shapes"][spec["shape"]]
    if shape["type"] in TYPED:
        entry["type"] = shape["type"]
    if shape["type"] == "list":
        inner = model["shapes"][shape["member"]["shape"]]["type"]
        if inner in TYPED:
            entry["item"] = inner
    if shape["type"] == "timestamp" and shape.get("timestampFormat"):
        entry["format"] = shape["timestampFormat"]
    elif shape["type"] == "timestamp" and spec.get("timestampFormat"):
        entry["format"] = spec["timestampFormat"]
    return entry


def timestamps(model: dict, shape_name: str, iso_only: bool, path: str = "", stack: tuple = ()) -> list[str]:
    """The paths of the timestamp members under a shape: `a.b`, `[]` for a list element, `{}` for a map value.

    Input paths list every timestamp (the dispatcher reads each to epoch seconds); output paths only those the model
    formats as ISO 8601 (the dispatcher writes those as text, the rest stay epoch seconds).
    """
    if shape_name in stack or len(stack) > 12:
        return []
    shape = model["shapes"][shape_name]
    found: list[str] = []
    if shape["type"] == "structure":
        for name, spec in shape.get("members", {}).items():
            if spec.get("location") and spec["location"] != "body":
                continue
            wire = spec.get("jsonName", name) if not spec.get("locationName") else spec["locationName"]
            target = model["shapes"][spec["shape"]]
            member_path = f"{path}.{name}" if path else name
            if target["type"] == "timestamp":
                fmt = spec.get("timestampFormat") or target.get("timestampFormat") or "unixTimestamp"
                if not iso_only or fmt == "iso8601":
                    found.append(member_path)
            else:
                found += timestamps(model, spec["shape"], iso_only, member_path, stack + (shape_name,))
    elif shape["type"] == "list":
        found += timestamps(model, shape["member"]["shape"], iso_only, path + "[]", stack + (shape_name,))
    elif shape["type"] == "map":
        found += timestamps(model, shape["value"]["shape"], iso_only, path + "{}", stack + (shape_name,))
    return found


def bindings(model: dict, shape_name: str | None, payload_location: str, iso_only: bool) -> dict:
    if not shape_name:
        return {"members": {}}
    shape = model["shapes"][shape_name]
    members = {}
    for name, spec in shape.get("members", {}).items():
        entry = member(model, spec)
        if shape.get("payload") == name:
            entry["location"] = payload_location
        if entry["location"] == "body" and "name" not in entry and spec.get("jsonName"):
            entry["name"] = spec["jsonName"]
        if spec.get("idempotencyToken"):
            entry["idempotencyToken"] = True
        if entry["location"] == "body" and not entry.get("idempotencyToken"):
            # A structure body is read and written as it is; only the members bound elsewhere are vendored.
            continue
        members[name] = entry
    result: dict = {"members": members}
    found = timestamps(model, shape_name, iso_only)
    if found:
        result["timestamps"] = found
    return result


def wire_name(spec: dict, name: str) -> str:
    return spec.get("locationName") or spec.get("jsonName") or name


def renames_members(model: dict) -> bool:
    """Whether any body member of a structure goes by another name on the wire."""
    for shape in model["shapes"].values():
        if shape["type"] != "structure":
            continue
        for name, spec in shape.get("members", {}).items():
            if spec.get("location") in (None, "body") and wire_name(spec, name) != name:
                return True
    return False


def wire_shapes(model: dict) -> dict:
    """Each aggregate shape: a structure's body members with their wire names and shapes, a list's or map's element."""
    aggregates = {}
    for shape_name in sorted(model["shapes"]):
        shape = model["shapes"][shape_name]
        if shape["type"] == "structure":
            members = {}
            for name, spec in shape.get("members", {}).items():
                if spec.get("location") not in (None, "body"):
                    continue
                entry = {}
                if wire_name(spec, name) != name:
                    entry["name"] = wire_name(spec, name)
                if model["shapes"][spec["shape"]]["type"] in ("structure", "list", "map"):
                    entry["shape"] = spec["shape"]
                members[name] = entry
            aggregates[shape_name] = {"members": members}
        elif shape["type"] == "list":
            aggregates[shape_name] = {"list": shape["member"]["shape"]}
        elif shape["type"] == "map":
            aggregates[shape_name] = {"map": shape["value"]["shape"]}
    return aggregates


def build(name: str, model: dict, provenance: str) -> dict:
    renamed = renames_members(model)
    operations = {}
    for operation in sorted(model["operations"]):
        op = model["operations"][operation]
        http = op["http"]
        operations[operation] = {
            "method": http["method"],
            "requestUri": http["requestUri"],
            "responseCode": http.get("responseCode", 200),
            "input": bindings(model, op.get("input", {}).get("shape"), "payload", False),
            "output": bindings(model, op.get("output", {}).get("shape"), "payload", True),
        }
        if renamed:
            for side in ("input", "output"):
                if op.get(side, {}).get("shape"):
                    operations[operation][side]["shape"] = op[side]["shape"]
                    members = model["shapes"][op[side]["shape"]].get("members", {})
                    for member_name, entry in operations[operation][side]["members"].items():
                        if entry["location"] == "payload" and entry.get("type") == "structure":
                            entry["shape"] = members[member_name]["shape"]
    built = {
        "_source": {"generator": "tools/aws/regen_rest_json_bindings.py", "botocore": provenance},
        "service": name,
        "apiVersion": model["metadata"]["apiVersion"],
        "operations": operations,
    }
    if renamed:
        built["shapes"] = wire_shapes(model)
    return built


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--botocore-data", type=Path)
    args = parser.parse_args()
    data, provenance = resolve_botocore_data(args.botocore_data)
    stale = 0
    OUTPUT.mkdir(parents=True, exist_ok=True)
    for model_name, output_name in MODELS.items():
        model = load_model(latest_model_dir(data, model_name))
        if model["metadata"].get("protocol") != "rest-json":
            raise SystemExit(f"error: {model_name} is not a REST JSON model")
        fresh = json.dumps(build(output_name, model, provenance), indent=1, sort_keys=False) + "\n"
        target = OUTPUT / f"{output_name}.json"
        if args.check:
            stale |= not target.exists() or strip_source(target.read_text()) != strip_source(fresh)
        else:
            target.write_text(fresh)
    return int(stale)


if __name__ == "__main__":
    raise SystemExit(main())
