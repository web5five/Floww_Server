#!/bin/sh
set -eu

# The certificate is supplied at runtime. Never copy a project certificate,
# database password, or other deployment secret into the image build context.
if [ -n "${FLOWW_DB_CA_CERT_B64:-}" ]; then
    umask 077
    printf '%s' "$FLOWW_DB_CA_CERT_B64" | base64 -d > /tmp/floww-db-ca.crt
    unset FLOWW_DB_CA_CERT_B64
fi

# Serverless app instances must not pin a session-mode Supabase connection.
# Keep Flyway on the existing session endpoint, where its migrations and locks
# retain session semantics; use the transaction pooler only for app queries.
case "${FLOWW_DB_URL:-}" in
    jdbc:postgresql://aws-*.pooler.supabase.com:5432/*) ;;
    *) echo 'Expected the configured Supabase session-pooler JDBC URL' >&2; exit 1 ;;
esac
: "${FLOWW_DB_USER:?Database user is required}"
: "${FLOWW_DB_PASSWORD:?Database password is required}"
db_prefix=${FLOWW_DB_URL%%:5432/*}
db_suffix=${FLOWW_DB_URL#*:5432/}
app_db_url="${db_prefix}:6543/${db_suffix}"
case "$app_db_url" in
    *\?*) app_db_url="${app_db_url}&prepareThreshold=0" ;;
    *) app_db_url="${app_db_url}?prepareThreshold=0" ;;
esac
export SPRING_DATASOURCE_URL="$app_db_url"
export SPRING_FLYWAY_URL="$FLOWW_DB_URL"
export SPRING_FLYWAY_USER="$FLOWW_DB_USER"
export SPRING_FLYWAY_PASSWORD="$FLOWW_DB_PASSWORD"
unset db_prefix db_suffix app_db_url

# Favor cold-start time over peak JVM throughput for the request-driven demo.
# Vercel must see the HTTP port before its container startup deadline.
exec java -XX:TieredStopAtLevel=1 -jar /app/server.jar
