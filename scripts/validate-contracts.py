"""Phase 01 contract validation; schema examples are synthetic, not artifacts."""
import json
from pathlib import Path
from jsonschema import Draft202012Validator, FormatChecker
from openapi_spec_validator import validate_spec
import yaml

root = Path(__file__).resolve().parents[1]
def load(path):
    return json.loads((root / path).read_text(encoding="utf-8"))

manifest = load("packages/manifest-schema/release.schema.json")
example = load("packages/manifest-schema/examples/release.example.json")
event = load("docs/api/event.schema.json")
for schema in (manifest, event):
    Draft202012Validator.check_schema(schema)
Draft202012Validator(manifest, format_checker=FormatChecker()).validate(example)
validate_spec(yaml.safe_load((root / "docs/api/openapi.yaml").read_text(encoding="utf-8")))
phases = sorted(example["phases"], key=lambda p: p["ordinal"])
assert [p["ordinal"] for p in phases] == list(range(1, len(phases)+1))
previous_end = 0
for phase in phases:
    assert previous_end <= phase["startMs"] < phase["endMs"] <= example["durationMs"]
    previous_end = phase["endMs"]
    paths = {f["path"] for f in phase["files"]}
    assert len(paths) == len(phase["files"])
    assert {phase["workspace"], phase["compose"], phase["harness"], *phase["fixtures"]} <= paths
    for path in paths:
        assert not path.startswith("/") and ".." not in path.split("/")
print("OpenAPI and JSON Schema validation passed")
