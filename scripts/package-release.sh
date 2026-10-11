#!/usr/bin/env sh
# SPDX-License-Identifier: AGPL-3.0-or-later
# Assembles a release archive: the application distribution, libswe for this platform, the .se1 files,
# LICENSE and README.md, under a top-level swiss-ephemeris-service/ directory.
#
# Usage: package-release.sh <arch> <app-dir> <native-dir> <out-dir>
#   <app-dir>     build/install/swiss-ephemeris-service (./gradlew installDist)
#   <native-dir>  output of native/build-libswe.sh (lib/libswe.so, ephe/*.se1)
# Output: <out-dir>/swiss-ephemeris-service-linux-<arch>.tar.gz
set -eu

ARCH=${1:?arch}; APP=${2:?app dir}; NATIVE=${3:?native dir}; OUT=${4:?out dir}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
STAGE=$(mktemp -d)
HOME_DIR="$STAGE/swiss-ephemeris-service"

mkdir -p "$HOME_DIR/native" "$HOME_DIR/ephe" "$OUT"
cp -R "$APP"/. "$HOME_DIR/"
chmod +x "$HOME_DIR/bin/swiss-ephemeris-service"
cp "$NATIVE/lib/libswe.so" "$HOME_DIR/native/"
cp "$NATIVE"/ephe/*.se1 "$HOME_DIR/ephe/"
cp "$ROOT/LICENSE" "$ROOT/README.md" "$HOME_DIR/"
tar -C "$STAGE" -czf "$OUT/swiss-ephemeris-service-linux-$ARCH.tar.gz" swiss-ephemeris-service
rm -rf "$STAGE"
echo "$OUT/swiss-ephemeris-service-linux-$ARCH.tar.gz"
