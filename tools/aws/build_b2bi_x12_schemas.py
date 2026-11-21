#!/usr/bin/env python3
"""Condense B2B Data Interchange starter mapping templates into the X12 loop schemas Floci parses with.

A starter template (CreateStarterMappingTemplate, JSONATA, no sample document) lays out a transaction set's
segments in X12 order: each segment with its element count and composite elements, a repeatable segment listed
twice, and each loop as ``<first segment>-<loop id>_loop`` around its segments and inner loops. Floci keeps only
that structure, which is the X12 standard's, so it can file a parsed document's segments into loops and write a
starter template for a transaction set.

Usage: build_b2bi_x12_schemas.py <dir of X12_<set>_<version>.json responses> [out]
Fetch a response with:
  aws b2bi create-starter-mapping-template --mapping-type JSONATA \\
    --template-details '{"x12":{"transactionSet":"X12_850","version":"VERSION_4010"}}'
"""
import json
import pathlib
import re
import sys

OUT = pathlib.Path("src/main/resources/aws/b2bi-x12-schemas.json")


def segment_id(segment):
    return next(iter(segment)).split("_")[0]


def node(segment):
    key = next(iter(segment))
    if key.endswith("_loop"):
        return {"loop": key[: -len("_loop")], "children": nodes(segment[key])}
    composites = {}
    for name, value in segment.items():
        if isinstance(value, dict):
            composites[str(int(name.split("_")[1]))] = len(value)
    out = [segment_id(segment), len(segment), 1]
    if composites:
        out.append(composites)
    return out


def nodes(segments):
    out = []
    for segment in segments:
        current = node(segment)
        previous = out[-1] if out else None
        if isinstance(current, list) and isinstance(previous, list) and previous[0] == current[0] \
                and previous[1] == current[1] and previous[3:] == current[3:]:
            previous[2] = 2
            continue
        out.append(current)
    return out


def main():
    source = pathlib.Path(sys.argv[1])
    out = pathlib.Path(sys.argv[2]) if len(sys.argv) > 2 else OUT
    schemas = {}
    for path in sorted(source.glob("X12_*_VERSION_*.json")):
        match = re.match(r"(X12_\w+?)_(VERSION_\w+)\.json$", path.name)
        try:
            template = json.loads(json.loads(path.read_text())["mappingTemplate"])
        except (ValueError, KeyError):
            continue
        transaction = template["interchanges"][0]["functional_groups"][0]["transactions"][0]
        schemas.setdefault(match.group(1), {})[match.group(2)] = nodes(transaction["segments"])
    document = {
        "_source": "Condensed by tools/aws/build_b2bi_x12_schemas.py from AWS B2B Data Interchange starter "
                   "mapping templates: the X12 segment order, loops, element counts and composites of each "
                   "transaction set and version.",
        "schemas": schemas,
    }
    out.write_text(json.dumps(document, separators=(",", ":"), sort_keys=True) + "\n")
    print(f"wrote {out}: {sum(len(v) for v in schemas.values())} schemas, {out.stat().st_size} bytes")


if __name__ == "__main__":
    main()
