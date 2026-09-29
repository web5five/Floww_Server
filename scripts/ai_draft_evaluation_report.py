#!/usr/bin/env python3
"""Summarize actual JUnit fixture results; never infer live-provider success."""

import json
import re
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CORPUS = ROOT / "eval/ai-draft-scenarios-v1.json"
XML = ROOT / "target/surefire-reports/TEST-com.floww.server.aidraft.AiDraftEvaluationTest.xml"
REPORT = ROOT / "target/f008-evaluation.json"


def main() -> int:
    corpus = json.loads(CORPUS.read_text(encoding="utf-8"))
    root = ET.parse(XML).getroot()
    scenarios = corpus["cases"]
    observed = {}
    for case in root.findall("testcase"):
        match = re.fullmatch(r"scenarios\(\)\[(\d+)\]", case.get("name", ""))
        if match:
            index = int(match.group(1))
            if index in observed:
                raise ValueError(f"duplicate JUnit scenario index: {index}")
            observed[index] = case
    if set(observed) != set(range(1, len(scenarios) + 1)):
        raise ValueError("JUnit scenario indexes do not match the corpus; report would be stale")
    results = []
    for index, scenario in enumerate(scenarios, 1):
        case = observed[index]
        passed = not any(case.find(tag) is not None for tag in ("failure", "error", "skipped"))
        results.append({
            "id": scenario["id"],
            "passed": passed,
            "kind": scenario["kind"],
            "expected": scenario.get("expectedStatus", scenario.get("expectedFailure")),
            "provenance": corpus["evidenceMode"] if scenario["kind"] == "model" else "deterministic_preflight",
        })
    report = {
        "schemaVersion": corpus["schemaVersion"],
        "draftSchemaVersion": corpus["baseDraft"]["schemaVersion"],
        "evidence": "local_http_model_fixture_and_junit",
        "liveKilnCalls": 0,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "source": "AiDraftEvaluationTest Surefire XML",
        "total": len(results),
        "passed": sum(row["passed"] for row in results),
        "failed": sum(not row["passed"] for row in results),
        "cases": results,
    }
    REPORT.parent.mkdir(exist_ok=True)
    REPORT.write_text(json.dumps(report, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
    print(f"F008 local fixtures: {report['passed']}/{report['total']} passed; report: {REPORT}")
    return 0 if report["failed"] == 0 else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError, ET.ParseError) as error:
        print(f"F008 evaluation report unavailable: {error}", file=sys.stderr)
        sys.exit(2)
