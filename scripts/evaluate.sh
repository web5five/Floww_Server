#!/bin/sh
# Requires local PostgreSQL and exported FLOWW_DB_PASSWORD (source .env privately first).
set -eu
cd "$(dirname "$0")/.."
./mvnw -B -q -Dtest=ExecutionIntegrationTest,MerchantGatewayTest,KilnProvenanceTest test
python3 scripts/evaluation_report.py
