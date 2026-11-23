#!/bin/sh
# SeaweedFS launcher for testPike object storage (single source for every channel:
# docker compose main slot AND scripts/chain_slot.py per-chain slot containers).
#
# Rendered config = PostgreSQL-backed filer metadata (SeaweedFS v4.x reads store config
# from filer.toml, not command-line flags; the column key is `hostname`, and table
# bootstrap requires createTable=true) plus the S3 identity file. All values come from
# environment variables (install-time .env, R48): this file must never contain secrets.
#
# Required env: SWF_META_DB_PASSWORD, S3_ACCESS_KEY, S3_SECRET_KEY
# Optional env (factory defaults shown):
#   SWF_META_DB_HOST=testpike-postgres  SWF_META_DB_PORT=5432  SWF_META_DB_NAME=seaweedfs
#   SWF_META_DB_USER=testpike  SWF_S3_PORT=8333  SWF_MASTER_PORT=9333  SWF_VOLUME_PORT=9334
#   SWF_FILER_PORT=8888  SWF_VOLUME_SIZE_LIMIT_MB=1024
set -eu

CONF=/etc/seaweedfs
mkdir -p "$CONF" /data/master /data/volumes

: "${SWF_META_DB_HOST:=testpike-postgres}"
: "${SWF_META_DB_PORT:=5432}"
: "${SWF_META_DB_NAME:=seaweedfs}"
: "${SWF_META_DB_USER:=testpike}"
: "${SWF_META_DB_PASSWORD:?SWF_META_DB_PASSWORD missing (from .env DB_PASSWORD)}"
: "${S3_ACCESS_KEY:?S3_ACCESS_KEY missing (from .env S3_ACCESS_KEY)}"
: "${S3_SECRET_KEY:?S3_SECRET_KEY missing (from .env S3_SECRET_KEY)}"

cat > "$CONF/filer.toml" <<EOF
[postgres]
enabled = true
username = "$SWF_META_DB_USER"
password = "$SWF_META_DB_PASSWORD"
hostname = "$SWF_META_DB_HOST"
port = $SWF_META_DB_PORT
database = "$SWF_META_DB_NAME"
schema = "public"
sslmode = "disable"
createTable = true
EOF
chmod 600 "$CONF/filer.toml"

cat > "$CONF/s3.json" <<EOF
{"identities":[{"name":"admin","credentials":[{"accessKey":"$S3_ACCESS_KEY","secretKey":"$S3_SECRET_KEY"}],"actions":["Admin","Read","Write"]}]}
EOF
chmod 600 "$CONF/s3.json"

# Filer and S3 gateway processes are stateless; the all-in-one form (master+volume+filer+s3
# in one process) avoids the raft-leader race that separate quick-started containers hit
# on single-node dev machines (proven 2026-11-16: split startup reported
# "No writable volumes" until leader election finished).
exec weed server \
  -s3=true -s3.config="$CONF/s3.json" \
  -filer=true \
  -dir=/data/volumes -master.dir=/data/master \
  -master.port="${SWF_MASTER_PORT:-9333}" \
  -volume.port="${SWF_VOLUME_PORT:-9334}" \
  -filer.port="${SWF_FILER_PORT:-8888}" \
  -s3.port="${SWF_S3_PORT:-8333}" \
  -master.volumeSizeLimitMB="${SWF_VOLUME_SIZE_LIMIT_MB:-1024}"
