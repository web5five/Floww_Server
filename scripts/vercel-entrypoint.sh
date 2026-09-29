#!/bin/sh
set -eu

# The certificate is supplied at runtime. Never copy a project certificate,
# database password, or other deployment secret into the image build context.
if [ -n "${FLOWW_DB_CA_CERT_B64:-}" ]; then
    umask 077
    printf '%s' "$FLOWW_DB_CA_CERT_B64" | base64 -d > /tmp/floww-db-ca.crt
    unset FLOWW_DB_CA_CERT_B64
fi

exec java -jar /app/server.jar
