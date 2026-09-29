#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
# Requires Java 21 through JAVA_HOME or the normal Maven launcher environment.
rm -f target/surefire-reports/TEST-com.floww.server.aidraft.AiDraftEvaluationTest.xml target/f008-evaluation.json
./mvnw -B -q -Dtest=AiDraftEvaluationTest test
python3 scripts/ai_draft_evaluation_report.py
