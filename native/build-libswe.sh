#!/usr/bin/env sh
# SPDX-License-Identifier: AGPL-3.0-or-later
# Builds libswe (shared library), swetest and copies the 1800-2400 ephemeris files from the pinned Swiss
# Ephemeris commit. Only the needed files are fetched (~3.6 MB, not the 376 MB repository tarball), each
# verified against swisseph.sha256. Used by Gradle (local runs and tests) and by container builds.
#
# Usage: build-libswe.sh <output-dir>
# Output: <output-dir>/lib/libswe.{so|dylib}, <output-dir>/bin/swetest, <output-dir>/ephe/*.se1
# SWE_CACHE (optional): directory keeping verified downloads between builds (default <output-dir>/work).
set -eu

OUT=${1:?usage: build-libswe.sh <output-dir>}
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/swisseph.lock"
SRC="${SWE_CACHE:-$OUT/work}/swisseph-$SWE_COMMIT"
mkdir -p "$SRC/ephe" "$OUT/lib" "$OUT/bin" "$OUT/ephe"

sha256() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1; }

# Fetch each locked file unless a verified copy is cached; refuse any checksum mismatch.
grep -v '^#' "$HERE/swisseph.sha256" | while read -r expected file; do
  target="$SRC/$file"
  if [ -f "$target" ] && [ "$(sha256 "$target")" = "$expected" ]; then continue; fi
  curl -fsSL --retry 3 --connect-timeout 20 --speed-limit 1024 --speed-time 60 \
    -o "$target.part" "https://raw.githubusercontent.com/aloistr/swisseph/$SWE_COMMIT/$file"
  actual=$(sha256 "$target.part")
  [ "$actual" = "$expected" ] || { echo "checksum mismatch for $file: expected $expected, got $actual" >&2; rm -f "$target.part"; exit 1; }
  mv "$target.part" "$target"
done

case "$(uname -s)" in
  Darwin) EXT=dylib; SHARED="-dynamiclib -install_name @rpath/libswe.dylib"; LIBS="" ;;
  *)      EXT=so;    SHARED="-shared";                                       LIBS="-lm -ldl" ;;
esac

LIB_SOURCES="swedate.c swehouse.c swejpl.c swemmoon.c swemplan.c sweph.c swephlib.c swecl.c swehel.c"
CFLAGS="-O2 -fPIC -w"

(
  cd "$SRC"
  # shellcheck disable=SC2086
  ${CC:-cc} $CFLAGS $SHARED -o "$OUT/lib/libswe.$EXT" $LIB_SOURCES $LIBS
  # shellcheck disable=SC2086
  ${CC:-cc} $CFLAGS -o "$OUT/bin/swetest" swetest.c $LIB_SOURCES $LIBS
)

cp "$SRC"/ephe/*.se1 "$OUT/ephe/"
echo "libswe $SWE_COMMIT -> $OUT/lib/libswe.$EXT"
