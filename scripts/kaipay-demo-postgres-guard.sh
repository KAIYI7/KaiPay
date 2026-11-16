#!/bin/sh
# Existing PostgreSQL 16 clusters only. Never invoke the image's initdb entrypoint.
set -eu
export LC_ALL=C
fail() { echo "PostgreSQL bootstrap guard: $*" >&2; exit 1; }
case "${KAIPAY_EXPECTED_PG_SYSTEM_ID:-}" in ''|*[!0-9]*) fail 'invalid expected system identifier';; esac
[ "${PGDATA:-/var/lib/postgresql/data}" = /var/lib/postgresql/data ] || fail 'unexpected PGDATA'
data=/var/lib/postgresql/data
[ -d "$data" ] && [ ! -L "$data" ] || fail 'data directory missing or symlinked'
[ -f "$data/PG_VERSION" ] && [ ! -L "$data/PG_VERSION" ] || fail 'PG_VERSION missing or symlinked'
version=$(cat "$data/PG_VERSION") || fail 'cannot read PostgreSQL major version'
[ "$version" = 16 ] || fail 'unexpected PostgreSQL major version'
[ -f "$data/global/pg_control" ] && [ ! -L "$data/global" ] && [ ! -L "$data/global/pg_control" ] || fail 'pg_control missing or symlinked'
control=$(pg_controldata "$data") || fail 'invalid or incompatible cluster control data'
system=$(printf '%s\n' "$control" | sed -n 's/^Database system identifier:[[:space:]]*//p')
[ -n "$system" ] && [ "$system" = "$KAIPAY_EXPECTED_PG_SYSTEM_ID" ] || fail 'cluster system identifier mismatch'
# Unlike the stock entrypoint, this path has no initdb branch, even if data is
# removed after validation. The existing cluster must already be owned correctly.
exec su-exec postgres postgres -D "$data"
