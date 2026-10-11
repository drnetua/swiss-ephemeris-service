#!/usr/bin/env sh
# SPDX-License-Identifier: AGPL-3.0-or-later
# Unpacks a release archive, starts it without any SWE_* settings and waits for its health check.
# Usage: smoke-archive.sh <archive.tar.gz>   (needs a JRE 25 on PATH)
set -eu

DIR=$(mktemp -d)
tar -xzf "${1:?archive}" -C "$DIR"
BIN="$DIR/swiss-ephemeris-service/bin/swiss-ephemeris-service"
PORT=50071 "$BIN" > "$DIR/service.log" 2>&1 &
PID=$!
trap 'kill $PID 2>/dev/null || true' EXIT
for _ in $(seq 1 30); do
  if PORT=50071 "$BIN" healthcheck; then echo "archive is healthy"; exit 0; fi
  sleep 2
done
cat "$DIR/service.log"
exit 1
