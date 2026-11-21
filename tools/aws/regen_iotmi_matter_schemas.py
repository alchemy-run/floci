#!/usr/bin/env python3
"""Regenerates the Matter capability catalog Managed integrations for AWS IoT Device Management serves.

Managed integrations publishes a capability for every Matter cluster, baselined at Matter 1.4 (``matter.OnOff@1.4``),
and a definition for every Matter data type (``matter.uint16@1.4``). This script builds both from the Matter 1.4 data
model the Connectivity Standards Alliance publishes in connectedhomeip (Apache-2.0), at
``src/app/zap-templates/zcl/data-model/chip`` of tag ``v1.4.0.0``:

    git clone --depth 1 --filter=blob:none --sparse --branch v1.4.0.0 \\
        https://github.com/project-chip/connectedhomeip /tmp/chip
    git -C /tmp/chip sparse-checkout set src/app/zap-templates/zcl/data-model/chip
    python3 tools/aws/regen_iotmi_matter_schemas.py --source-dir /tmp/chip/src/app/zap-templates/zcl/data-model/chip

Each cluster becomes a capability document in the managed integrations data model: ``$id``, ``name``,
``extrinsicId`` (the cluster ID), ``extrinsicVersion`` (the cluster revision), ``properties`` from its attributes,
``actions`` from its client commands (with their responses) and ``events``, with the cluster's enums, bitmaps and
structs under ``$defs``. ``--check`` compares against the committed file.
"""

import argparse
import json
import re
import sys
import xml.etree.ElementTree as ElementTree
from pathlib import Path

OUTPUT = Path(__file__).resolve().parents[2] / "src/main/resources/aws/iotmanagedintegrations-matter-schemas.json"
VERSION = "1.4"

# Matter's base data types and their JSON schema; every other type is an enum, bitmap or struct of the cluster.
BASE = {
    "boolean": {"type": "boolean"},
    "single": {"type": "number"},
    "double": {"type": "number"},
    "char_string": {"type": "string", "maxLength": 255},
    "long_char_string": {"type": "string", "maxLength": 65535},
    "octet_string": {"type": "string", "contentEncoding": "base64"},
    "long_octet_string": {"type": "string", "contentEncoding": "base64"},
}
for bits in (8, 16, 24, 32, 40, 48, 56, 64):
    BASE[f"int{bits}u"] = {"type": "integer", "minimum": 0, "maximum": 2 ** bits - 1}
    BASE[f"int{bits}s"] = {"type": "integer", "minimum": -(2 ** (bits - 1)), "maximum": 2 ** (bits - 1) - 1}
for bits in (8, 16, 32, 64):
    BASE[f"enum{bits}"] = {"type": "integer", "minimum": 0, "maximum": 2 ** bits - 1}
    BASE[f"bitmap{bits}"] = {"type": "integer", "minimum": 0, "maximum": 2 ** bits - 1}
UNSIGNED_ALIASES = {
    "epoch_s": 32, "epoch_us": 64, "utc": 32, "elapsed_s": 32, "systime_ms": 64, "systime_us": 64, "posix_ms": 64,
    "fabric_idx": 8, "node_id": 64, "endpoint_no": 16, "cluster_id": 32, "attrib_id": 32, "command_id": 32,
    "event_id": 32, "vendor_id": 16, "devtype_id": 32, "group_id": 16, "action_id": 8, "fabric_id": 64,
    "data_ver": 32, "event_no": 64, "field_id": 32, "trans_id": 32, "entry_idx": 16, "subject_id": 64,
    "percent": 8, "percent100ths": 16, "status": 8, "priority": 8, "tod": 32, "date": 32,
}
SIGNED_ALIASES = {"temperature": 16, "amperage_ma": 64, "voltage_mv": 64, "power_mw": 64, "energy_mwh": 64}
for alias, bits in UNSIGNED_ALIASES.items():
    BASE[alias] = {"type": "integer", "minimum": 0, "maximum": 2 ** bits - 1}
for alias, bits in SIGNED_ALIASES.items():
    BASE[alias] = {"type": "integer", "minimum": -(2 ** (bits - 1)), "maximum": 2 ** (bits - 1) - 1}
BASE["percent"]["maximum"] = 100
BASE["percent100ths"]["maximum"] = 10000


def aws_type(type_name):
    """Managed integrations' name for a Matter data type: int16u is uint16, char_string is string."""
    match = re.fullmatch(r"int(\d+)([us])", type_name)
    if match:
        return ("uint" if match.group(2) == "u" else "int") + match.group(1)
    return {"char_string": "string", "long_char_string": "longstring", "octet_string": "octstr",
            "long_octet_string": "longoctstr"}.get(type_name, type_name)


def definition_id(type_name):
    return f"/schema-versions/definition/matter.{aws_type(type_name)}@{VERSION}"


def capability_name(display):
    return re.sub(r"[^A-Za-z0-9]", "", display)


def parse_int(text):
    if text is None:
        return None
    text = text.strip()
    try:
        return int(text, 16) if text.lower().startswith("0x") else int(text)
    except ValueError:
        return None


def hex_id(value, width=4):
    return f"0x{value:0{width}X}"


class Model:
    """Every enum, bitmap and struct of the data model, by name, with the clusters that scope it."""

    def __init__(self, roots):
        self.types = {}
        for root in roots:
            for kind in ("enum", "bitmap", "struct"):
                for element in root.iter(kind):
                    name = element.get("name")
                    if name:
                        clusters = [parse_int(c.get("code")) for c in element.findall("cluster")]
                        self.types.setdefault(name, []).append((kind, element, clusters))

    def lookup(self, name, cluster):
        candidates = self.types.get(name, [])
        for kind, element, clusters in candidates:
            if cluster in clusters:
                return kind, element
        for kind, element, clusters in candidates:
            if not clusters:
                return kind, element
        return candidates[0][:2] if candidates else (None, None)


def value_schema(model, type_name, cluster, defs, array=False, nullable=False):
    if type_name is None:
        schema = {}
    elif type_name in BASE:
        schema = {"$ref": definition_id(type_name)}
    else:
        kind, element = model.lookup(type_name, cluster)
        if element is None:
            schema = {"$ref": definition_id("int64u")}
        else:
            if type_name not in defs:
                defs[type_name] = {}
                defs[type_name] = type_definition(model, kind, element, cluster, defs)
            schema = {"$ref": f"#/$defs/{type_name}"}
    if array:
        schema = {"type": "array", "items": schema}
    if nullable:
        schema = {**schema, "nullable": True}
    return schema


def type_definition(model, kind, element, cluster, defs):
    base = element.get("type")
    if kind == "enum":
        items = element.findall("item")
        return {
            "$ref": definition_id(base if base in BASE else "enum8"),
            "enum": [parse_int(item.get("value")) for item in items],
            "x-enumNames": [item.get("name") for item in items],
        }
    if kind == "bitmap":
        return {
            "$ref": definition_id(base if base in BASE else "bitmap8"),
            "x-bitmapFields": {field.get("name"): field.get("mask") for field in element.findall("field")},
        }
    properties = {}
    required = []
    for index, item in enumerate(element.findall("item")):
        name = item.get("name") or item.get("fieldId")
        field = value_schema(model, item.get("type"), cluster, defs, item.get("array") == "true",
                             item.get("isNullable") == "true")
        field["extrinsicId"] = str(parse_int(item.get("fieldId")) if item.get("fieldId") else index)
        properties[name] = field
        if item.get("optional") != "true":
            required.append(name)
    schema = {"type": "object", "properties": properties}
    if required:
        schema["required"] = required
    return schema


def parameters(model, element, cluster, defs, child):
    params = {}
    for index, arg in enumerate(element.findall(child)):
        name = arg.get("name")
        field_id = parse_int(arg.get("fieldId")) if arg.get("fieldId") else parse_int(arg.get("id"))
        params[name] = {
            "extrinsicId": str(index if field_id is None else field_id),
            "value": value_schema(model, arg.get("type"), cluster, defs, arg.get("array") == "true",
                                  arg.get("isNullable") == "true"),
        }
        if arg.get("optional") == "true":
            params[name]["optional"] = True
    return params


def description(element, fallback):
    text = element.findtext("description")
    text = re.sub(r"\s+", " ", text or "").strip() or fallback
    text = re.sub(r"[^a-zA-Z0-9.,/ -]", " ", text)
    text = re.sub(r"\s+", " ", text).strip()
    if len(text) < 10:
        text = f"{fallback} capability"
    return text[:2048]


def capability(model, cluster):
    code = parse_int(cluster.findtext("code"))
    display = cluster.findtext("name").strip()
    name = capability_name(display)
    revision = 1
    for attribute in cluster.findall("globalAttribute"):
        if parse_int(attribute.get("code")) == 0xFFFD:
            revision = parse_int(attribute.get("value")) or 1
    defs = {}
    properties = {}
    for attribute in cluster.findall("attribute"):
        if attribute.get("side") not in (None, "server"):
            continue
        attribute_name = (attribute.text or "").strip() or attribute.findtext("description", "").strip()
        if not attribute_name:
            continue
        properties[capability_name(attribute_name)] = {
            "extrinsicId": hex_id(parse_int(attribute.get("code"))),
            "value": value_schema(model, attribute.get("type"), code, defs, attribute.get("type") == "array"
                                  or attribute.get("entryType") is not None, attribute.get("isNullable") == "true"),
            "retrievable": True,
            "mutable": attribute.get("writable") == "true",
            "reportable": True,
        }
        if attribute.get("entryType"):
            properties[capability_name(attribute_name)]["value"] = {
                "type": "array", "items": value_schema(model, attribute.get("entryType"), code, defs)}
    responses = {command.get("name"): command for command in cluster.findall("command")
                 if command.get("source") == "server"}
    actions = []
    for command in cluster.findall("command"):
        if command.get("source") != "client":
            continue
        action = {"name": command.get("name"), "extrinsicId": hex_id(parse_int(command.get("code"))),
                  "description": description(command, command.get("name"))[:1536]}
        request = parameters(model, command, code, defs, "arg")
        if request:
            action["request"] = {"parameters": request}
        response_name = command.get("response")
        if response_name and response_name in responses:
            response = responses[response_name]
            action["response"] = {"name": response_name,
                                  "extrinsicId": hex_id(parse_int(response.get("code"))),
                                  "parameters": parameters(model, response, code, defs, "arg")}
        actions.append(action)
    events = []
    for event in cluster.findall("event"):
        entry = {"name": event.get("name"), "extrinsicId": hex_id(parse_int(event.get("code")))}
        fields = parameters(model, event, code, defs, "field")
        if fields:
            entry["fields"] = fields
        events.append(entry)
    schema = {
        "$id": f"/schema-versions/capability/matter.{name}@{VERSION}",
        "$ref": "/schema-versions/definition/aws.capability@1.0",
        "name": display,
        "title": display,
        "description": description(cluster, display),
        "version": VERSION,
        "extrinsicId": hex_id(code),
        "extrinsicVersion": str(revision),
        "properties": properties,
        "actions": actions,
        "events": events,
    }
    if defs:
        schema["$defs"] = dict(sorted(defs.items()))
    return name, schema


def build(source):
    roots = [ElementTree.parse(path).getroot() for path in sorted(Path(source).glob("*.xml"))]
    model = Model(roots)
    capabilities = {}
    for root in roots:
        for cluster in root.iter("cluster"):
            if cluster.findtext("code") is None or cluster.findtext("name") is None:
                continue
            name, schema = capability(model, cluster)
            if schema["properties"] or schema["actions"] or schema["events"]:
                capabilities[f"matter.{name}@{VERSION}"] = schema
    definitions = {}
    for type_name, schema in sorted(BASE.items()):
        definitions[f"matter.{aws_type(type_name)}@{VERSION}"] = {
            "$id": definition_id(type_name), "name": aws_type(type_name),
            "description": f"The Matter {type_name} data type", **schema}
    definitions["aws.capability@1.0"] = {
        "$id": "/schema-versions/definition/aws.capability@1.0", "name": "capability",
        "description": "The base definition every capability refers to", "type": "object"}
    return {
        "_source": "connectedhomeip v1.4.0.0 src/app/zap-templates/zcl/data-model/chip "
                   "(tools/aws/regen_iotmi_matter_schemas.py)",
        "capabilities": dict(sorted(capabilities.items())),
        "definitions": definitions,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--source-dir", help="the chip data-model directory of connectedhomeip v1.4.0.0")
    parser.add_argument("--check", action="store_true", help="verify the committed file parses and is complete")
    args = parser.parse_args()
    if args.check:
        document = json.loads(OUTPUT.read_text())
        problems = [key for key, schema in document["capabilities"].items()
                    if not schema.get("properties") and not schema.get("actions") and not schema.get("events")]
        if len(document["capabilities"]) < 50 or problems:
            print(f"error: {OUTPUT} is incomplete: {problems[:5]}", file=sys.stderr)
            return 1
        print(f"{OUTPUT.relative_to(Path.cwd()) if OUTPUT.is_relative_to(Path.cwd()) else OUTPUT}: "
              f"{len(document['capabilities'])} capabilities, {len(document['definitions'])} definitions")
        return 0
    if not args.source_dir:
        parser.error("--source-dir is required to regenerate")
    document = build(args.source_dir)
    OUTPUT.write_text(json.dumps(document, indent=1, sort_keys=False) + "\n")
    print(f"wrote {OUTPUT}: {len(document['capabilities'])} capabilities, {len(document['definitions'])} definitions")
    return 0


if __name__ == "__main__":
    sys.exit(main())
