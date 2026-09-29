#!/usr/bin/env python3
"""Build and exercise the real server image against an isolated PostgreSQL 16.4.

No fixture, live provider, historical database, or existing Docker resource is used.
The printed result is deliberately limited to assertions and nonsecret identifiers.
"""

import argparse
import hashlib
import json
import os
import re
import secrets
import subprocess
import sys
import tempfile
import time
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


ROOT = Path(__file__).resolve().parent.parent
JAR = ROOT / "target/floww-server-0.1.0.jar"
DB_IMAGE = "postgres:16.4-alpine"
JAVA_IMAGE = "eclipse-temurin:21-jre"
LABEL = "com.floww.task=F016"
RUN_LABEL_KEY = "com.floww.run"


def run(step, args, *, timeout=30, cwd=None, env=None):
    try:
        result = subprocess.run(args, cwd=cwd, env=env, text=True,
                                capture_output=True, timeout=timeout, check=False)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise RuntimeError(f"{step}: command could not complete ({type(exc).__name__})") from None
    if result.returncode:
        raise RuntimeError(f"{step}: exit {result.returncode}; inspect this step locally")
    return result.stdout


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def resource_state(kind, name, suffix):
    """Return an owned object's immutable ID, or None only on confirmed absence."""
    labels = ".Labels" if kind == "network" else ".Config.Labels"
    try:
        probe = subprocess.run(["docker", kind, "inspect", "--format",
                                "{{json .Id}}|{{json " + labels + "}}", name],
                               text=True, capture_output=True, timeout=10, check=False)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise RuntimeError(f"{kind} {name}: inspect unavailable ({type(exc).__name__})") from None
    if probe.returncode:
        listing = {"container": ["docker", "container", "ls", "-a", "--format", "{{.Names}}"],
                   "network": ["docker", "network", "ls", "--format", "{{.Name}}"],
                   "image": ["docker", "image", "ls", "--format", "{{.Repository}}:{{.Tag}}"]}[kind]
        names = run(f"confirm absence of {kind} {name}", listing, timeout=10).splitlines()
        require(name not in names, f"{kind} {name}: inspect failed while resource still exists")
        return None
    try:
        object_id, label_json = probe.stdout.strip().split("|", 1)
        object_id = json.loads(object_id)
        found_labels = json.loads(label_json)
    except (ValueError, TypeError):
        raise RuntimeError(f"{kind} {name}: unreadable inspect labels") from None
    require(isinstance(found_labels, dict) and
            found_labels.get("com.floww.task") == "F016" and
            found_labels.get(RUN_LABEL_KEY) == suffix,
            f"{kind} {name}: ownership labels do not match this F016 run")
    require(isinstance(object_id, str) and object_id,
            f"{kind} {name}: inspect returned no object ID")
    return object_id


def create_resource(registered, kind, name, suffix, step, command, *, timeout=30):
    require(resource_state(kind, name, suffix) is None,
            f"{kind} {name}: generated name already exists")
    registered.append((kind, name))  # A failed create can still leave an object behind.
    run(step, command, timeout=timeout)


def cleanup_resources(registered, suffix):
    errors = []
    for kind, name in reversed(registered):
        try:
            object_id = resource_state(kind, name, suffix)
            if object_id is None:
                continue
            target = name if kind == "image" else object_id
            command = {"container": ["docker", "rm", "-f", target],
                       "network": ["docker", "network", "rm", target],
                       "image": ["docker", "image", "rm", target]}[kind]
            run(f"cleanup {kind} {name}", command, timeout=30)
        except (RuntimeError, OSError, subprocess.TimeoutExpired) as exc:
            errors.append(str(exc))
    if errors:
        raise RuntimeError("; ".join(errors))


def write_secret(path, content):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w") as stream:
        stream.write(content)


def request(base, method, path, token=None, body=None, key=None):
    headers = {"Content-Type": "application/json"}
    if token is not None:
        headers["Authorization"] = "Bearer " + token
    if key is not None:
        headers["Idempotency-Key"] = key
    data = json.dumps(body).encode() if body is not None else None
    req = Request(base + path, data=data, headers=headers, method=method)
    try:
        with urlopen(req, timeout=5) as response:
            raw = response.read(262145)
            require(len(raw) <= 262144, "HTTP response exceeded size bound")
            return response.status, json.loads(raw)
    except HTTPError as exc:
        return exc.code, None


def wait_until(label, seconds, probe):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            if probe():
                return
        except (RuntimeError, URLError, ConnectionError, TimeoutError, ValueError,
                subprocess.TimeoutExpired):
            pass
        time.sleep(1)
    raise RuntimeError(f"{label}: timed out after {seconds}s")


def flyway_rows(db):
    output = run("read Flyway history", ["docker", "exec", db, "psql", "-U", "floww",
        "-d", "floww", "-At", "-F", "|", "-c",
        "SELECT installed_rank, version, success FROM flyway_schema_history ORDER BY installed_rank"], timeout=10)
    rows = [line.strip() for line in output.splitlines() if line.strip()]
    require(rows == ["1|1|t"], f"Flyway V1 history unexpected: {rows!r}")
    return rows


def application_base(app):
    # Docker may allocate another ephemeral host port when this container restarts.
    port_output = run("read app loopback port", ["docker", "port", app, "8080/tcp"]).strip()
    match = re.fullmatch(r"127\.0\.0\.1:(\d+)", port_output)
    require(match is not None, "application port is not loopback-only")
    return "http://127.0.0.1:" + match.group(1)


def assert_state(base, alice, bob, execution_id):
    path = f"/api/executions/{execution_id}"
    code, detail = request(base, "GET", path, alice)
    require(code == 200 and detail["id"] == execution_id and
            detail["ownerId"] == "alice" and detail["status"] == "FAILED",
            "owner read or persisted FAILED status mismatch")
    code, evidence = request(base, "GET", path + "/evidence.json", alice)
    require(code == 200 and evidence["format"] == "floww-evidence-2" and
            evidence["complete"] is True and evidence["paymentStatus"] == "NOT_AVAILABLE" and
            evidence["evidenceMode"] == "no_merchant_quote" and
            evidence["modelEvidenceMode"] == "none" and
            evidence["modelUsage"]["status"] == "none",
            "owner evidence/provenance mismatch")
    events = evidence["events"]["events"]
    require([event["kind"] for event in events] ==
            ["MANDATE_CONFIRMED", "RUN_STARTED", "INFERENCE_FAILED"],
            "failure event sequence mismatch")
    require(events[-1]["payload"]["code"] == "MERCHANT_NOT_CONFIGURED" and
            events[-1]["payload"]["paymentStatus"] == "NOT_ATTEMPTED",
            "missing merchant failure is not explicit")
    for route in (path, path + "/evidence.json"):
        require(request(base, "GET", route, bob)[0] == 404,
                "cross-owner read was not denied")
        require(request(base, "GET", route)[0] == 401,
                "anonymous read was not denied")
    return len(events)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.parse_args()
    suffix = uuid.uuid4().hex[:12]
    network = f"floww-f016-{suffix}"
    db = f"floww-f016-db-{suffix}"
    app = f"floww-f016-app-{suffix}"
    image = f"floww-f016:{suffix}"
    registered = []
    run_label = f"{RUN_LABEL_KEY}={suffix}"
    with tempfile.TemporaryDirectory(prefix="floww-f016-") as temp:
        directory = Path(temp)
        db_env = directory / "db.env"
        app_env = directory / "app.env"
        password = secrets.token_urlsafe(32)
        alice = secrets.token_urlsafe(32)
        bob = secrets.token_urlsafe(32)
        write_secret(db_env, f"POSTGRES_DB=floww\nPOSTGRES_USER=floww\nPOSTGRES_PASSWORD={password}\n")
        write_secret(app_env, "\n".join([
            "FLOWW_DB_URL=jdbc:postgresql://db:5432/floww",
            "FLOWW_DB_USER=floww", f"FLOWW_DB_PASSWORD={password}",
            f"FLOWW_DEV_TOKEN_ALICE={alice}", f"FLOWW_DEV_TOKEN_BOB={bob}",
            "KILN_API_KEY=", "FLOWW_TEST_MERCHANT_BASE_URL=", ""]))
        try:
            print("F016 run:", suffix, flush=True)
            for base_image in (DB_IMAGE, JAVA_IMAGE):
                run(f"verify cached {base_image}", ["docker", "image", "inspect", base_image])
            print("cached base images: present", flush=True)
            build_env = os.environ.copy()
            build_env["MAVEN_OPTS"] = "-Xmx512m -XX:MaxMetaspaceSize=256m"
            run("Maven package", [str(ROOT / "mvnw"), "-B", "-DskipTests", "package"],
                timeout=360, cwd=ROOT, env=build_env)
            require(JAR.is_file(), "Maven package did not produce the expected JAR")
            jar_hash = hashlib.sha256(JAR.read_bytes()).hexdigest()
            print("Maven package: PASS; JAR SHA-256:", jar_hash, flush=True)
            create_resource(registered, "image", image, suffix, "Docker image build",
                ["docker", "build", "--pull=false", "--network=none", "--label", LABEL,
                 "--label", run_label, "-t", image, str(ROOT)], timeout=240)
            print("Docker image build: PASS", flush=True)
            create_resource(registered, "network", network, suffix, "isolated network",
                ["docker", "network", "create", "--label", LABEL,
                 "--label", run_label, network])
            create_resource(registered, "container", db, suffix, "PostgreSQL start",
                ["docker", "run", "-d", "--pull=never", "--name", db,
                 "--label", LABEL, "--label", run_label,
                 "--network", network, "--network-alias", "db",
                 "--memory=256m", "--memory-swap=256m", "--cpus=1",
                 "--tmpfs", "/var/lib/postgresql/data:rw,size=128m,mode=0700",
                 "--env-file", str(db_env), DB_IMAGE])
            wait_until("PostgreSQL readiness", 60, lambda: subprocess.run(
                ["docker", "exec", db, "pg_isready", "-U", "floww", "-d", "floww"],
                capture_output=True, timeout=5).returncode == 0)
            create_resource(registered, "container", app, suffix, "application start",
                ["docker", "run", "-d", "--pull=never", "--name", app,
                 "--label", LABEL, "--label", run_label, "--network", network,
                 "--memory=640m", "--memory-swap=640m", "--cpus=1",
                 "-p", "127.0.0.1::8080", "--env-file", str(app_env), image])
            base = application_base(app)
            wait_until("application health", 120, lambda: request(base, "GET", "/actuator/health") ==
                       (200, {"status": "UP"}))
            flyway_rows(db)
            migration_line = r'Migrating schema .* to version "1'
            first_logs = run("read initial migration log", ["docker", "logs", app], timeout=10)
            require(len(re.findall(migration_line, first_logs)) == 1,
                    "Flyway V1 migration was not logged exactly once at first startup")
            print("fresh Flyway V1 and health: PASS", flush=True)
            code, readiness = request(base, "GET", "/api/integrations/readiness", alice)
            require(code == 200 and readiness["kilnConfigured"] is False and
                    readiness["merchantConfigured"] is False and
                    readiness["paymentConfigured"] is False,
                    "missing-provider readiness was not truthful")
            expires = (datetime.now(timezone.utc) + timedelta(hours=1)).isoformat().replace("+00:00", "Z")
            mandate = {"confirmed": True, "mandate": {"goal": "F016 isolated runtime check",
                "itemId": "item-1", "maxTotal": "10.00", "currency": "TEST_USDC",
                "recipient": "merchant_good", "expiresAt": expires}}
            code, execution = request(base, "POST", "/api/executions", alice, mandate, str(uuid.uuid4()))
            require(code == 200 and execution["status"] == "CREATED", "authenticated create failed")
            execution_id = execution["id"]
            code, result = request(base, "POST", f"/api/executions/{execution_id}/run", alice)
            require(code == 200 and result["status"] == "FAILED", "missing-provider run did not fail closed")
            count = assert_state(base, alice, bob, execution_id)
            print("create/read/evidence/denials/missing provider: PASS", flush=True)
            run("application restart", ["docker", "restart", "--time", "10", app], timeout=45)
            base = application_base(app)
            wait_until("restarted application health", 120, lambda: request(
                base, "GET", "/actuator/health") == (200, {"status": "UP"}))
            flyway_rows(db)
            restart_logs = run("read restart migration log", ["docker", "logs", app], timeout=10)
            require(len(re.findall(migration_line, restart_logs)) == 1,
                    "Flyway V1 repeated or initial migration log changed after restart")
            require(assert_state(base, alice, bob, execution_id) == count,
                    "evidence event count changed after restart")
            print("restart persistence; Flyway V1 present once: PASS", flush=True)
        finally:
            cleanup_resources(registered, suffix)
    print("RESULT PASS; resources cleaned; execution ID:", execution_id, flush=True)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, KeyError, TypeError, ValueError) as exc:
        print("F016 FAIL:", exc, file=sys.stderr)
        sys.exit(1)
