#!/usr/bin/env python3
"""Exercise the loopback API without printing bearer values or raw provider output."""

import argparse
import json
import os
import sys
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen


def load_local_env():
    path = Path(__file__).resolve().parent.parent / ".env"
    if path.is_file():
        for line in path.read_text().splitlines():
            if line and not line.startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                os.environ.setdefault(key, value)


def call(base, method, path, token=None, body=None, key=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if key:
        headers["Idempotency-Key"] = key
    data = json.dumps(body).encode() if body is not None else None
    request = Request(base + path, data=data, headers=headers, method=method)
    try:
        with urlopen(request, timeout=20) as response:
            return response.status, json.load(response)
    except HTTPError as error:
        try:
            return error.code, json.load(error)
        except (ValueError, UnicodeDecodeError):
            return error.code, {}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--check-existing", metavar="EXECUTION_ID")
    args = parser.parse_args()
    load_local_env()
    alice = os.environ.get("FLOWW_DEV_TOKEN_ALICE")
    bob = os.environ.get("FLOWW_DEV_TOKEN_BOB")
    if not alice or not bob:
        print("Local development bearer tokens are not configured")
        return 1
    base = args.base_url.rstrip("/")
    health, status = call(base, "GET", "/actuator/health")
    if health != 200 or status.get("status") != "UP":
        print("Health check failed")
        return 1
    print("health: UP")
    if args.check_existing:
        execution_id = args.check_existing
    else:
        expires = (datetime.now(timezone.utc) + timedelta(hours=1)).isoformat().replace("+00:00", "Z")
        mandate = {"confirmed": True, "mandate": {"goal": "Buy item-1 within budget",
                   "itemId": "item-1", "maxTotal": "10.00",
                   "currency": "TEST_USDC", "recipient": "merchant_good", "expiresAt": expires}}
        created, execution = call(base, "POST", "/api/executions", alice, mandate,
                                  str(uuid.uuid4()))
        if created != 200:
            print("create failed with HTTP", created, execution.get("code", "UNKNOWN"))
            return 1
        execution_id = execution["id"]
        print("created execution:", execution_id)
        ran, execution = call(base, "POST", f"/api/executions/{execution_id}/run", alice)
        if ran != 200:
            print("run failed with HTTP", ran, execution.get("code", "UNKNOWN"))
            return 1
        print("run status:", execution["status"])
    detail_code, detail = call(base, "GET", f"/api/executions/{execution_id}", alice)
    export_code, evidence = call(base, "GET", f"/api/executions/{execution_id}/evidence.json", alice)
    denied_code, _ = call(base, "GET", f"/api/executions/{execution_id}/evidence.json", bob)
    anonymous_code, _ = call(base, "GET", f"/api/executions/{execution_id}/evidence.json")
    if (detail_code, export_code, denied_code, anonymous_code) != (200, 200, 404, 401):
        print("evidence access check failed:", detail_code, export_code, denied_code, anonymous_code)
        return 1
    events = evidence["events"]["events"]
    print("persisted status:", detail["status"])
    print("evidence format:", evidence["format"], "complete:", evidence["complete"])
    print("evidence mode:", evidence["evidenceMode"])
    print("event kinds:", ", ".join(event["kind"] for event in events))
    print("cross-owner/anonymous evidence: 404/401")
    print("payment status:", evidence["paymentStatus"])
    return 0


if __name__ == "__main__":
    sys.exit(main())
