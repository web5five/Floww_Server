#!/usr/bin/env python3
"""Convert JUnit outcomes to a deterministic, secret-free fixture matrix."""
import json
from pathlib import Path
from xml.etree import ElementTree

ROOT = Path(__file__).resolve().parent.parent
CASES = {
    "normal bounded quote proposal and persisted round-trip": "ExecutionIntegrationTest.boundedRoundTripPersistsEvidenceAndOwnerIsolation",
    "budget overrun": "ExecutionIntegrationTest.rejectsBudgetRecipientItemAndExpiredQuote",
    "changed recipient": "ExecutionIntegrationTest.rejectsBudgetRecipientItemAndExpiredQuote",
    "wrong item": "ExecutionIntegrationTest.rejectsBudgetRecipientItemAndExpiredQuote",
    "expired quote": "ExecutionIntegrationTest.rejectsBudgetRecipientItemAndExpiredQuote",
    "expired mandate": "ExecutionIntegrationTest.blocksLoopCapProviderAuthAndExpiredMandate",
    "unknown tool": "ExecutionIntegrationTest.rejectsMalformedUnknownDuplicateAndTruncatedOutputs",
    "malformed tool": "ExecutionIntegrationTest.rejectsMalformedUnknownDuplicateAndTruncatedOutputs",
    "truncated response": "ExecutionIntegrationTest.rejectsMalformedUnknownDuplicateAndTruncatedOutputs",
    "duplicate tool ID": "ExecutionIntegrationTest.rejectsMalformedUnknownDuplicateAndTruncatedOutputs",
    "loop cap": "ExecutionIntegrationTest.blocksLoopCapProviderAuthAndExpiredMandate",
    "provider authorization failure": "ExecutionIntegrationTest.blocksLoopCapProviderAuthAndExpiredMandate",
    "unauthenticated and cross-owner access": "ExecutionIntegrationTest.boundedRoundTripPersistsEvidenceAndOwnerIsolation",
    "idempotency": "ExecutionIntegrationTest.idempotencyAndStrictMandate",
    "missing merchant fail closed": "MerchantGatewayTest.absentMerchantFailsClosed",
    "non-loopback merchant rejected": "MerchantGatewayTest.rejectsNonLoopbackTestMerchant",
    "unknown quote ID": "ExecutionIntegrationTest.rejectsEarlyStopAndUnknownQuote",
    "final call quote and mandate expiry": "ExecutionIntegrationTest.finalCallRechecksQuoteAndMandateExpiry",
    "created running and paged evidence completeness": "ExecutionIntegrationTest.evidenceCompletenessRequiresTerminalAndFullPage",
    "partial failure usage and retry unknown": "ExecutionIntegrationTest.failedAndRetriedProviderAttemptsKeepUsageProvenance",
    "historical provenance unknown": "ExecutionIntegrationTest.historicalModelEventsRemainUnknownProvenance",
    "fixture and exact official endpoint provenance": "KilnProvenanceTest.onlyExactOfficialEndpointCanClaimKiln",
}
results = {}
for path in (ROOT / "target" / "surefire-reports").glob("TEST-*.xml"):
    tree = ElementTree.parse(path)
    class_name = tree.getroot().get("name", "").split(".")[-1]
    for case in tree.findall(".//testcase"):
        name = class_name + "." + case.get("name", "")
        results[name] = not any(case.find(kind) is not None for kind in ("failure", "error", "skipped"))
missing = set(CASES.values()) - results.keys()
if missing:
    raise SystemExit("Missing expected tests: " + ", ".join(sorted(missing)))
report = {
    "format": "floww-f006-fixture-matrix-1",
    "merchantEvidenceMode": "local_test_merchant",
    "modelEvidenceMode": "local_model_fixture",
    "liveKilnIncluded": False,
    "cases": [{"scenario": case, "test": test, "passed": results[test]}
              for case, test in CASES.items()],
}
path = ROOT / "target" / "f006-evaluation.json"
path.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
print(f"fixture matrix: {sum(v['passed'] for v in report['cases'])}/{len(report['cases'])} passed -> {path}")
if not all(results.values()):
    raise SystemExit(1)
