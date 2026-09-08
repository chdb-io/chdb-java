#!/usr/bin/env bash
#
# Downloads the pinned chDB Core release asset for one platform and extracts libchdb
# into a cache directory, verifying the SHA-256 from scripts/engine.properties first.
#
# Work plan section 5.3:
#   - only ever fetch the pinned release asset, never a "latest" URL
#   - verify the checksum before the bytes are used for anything
#   - the download is a build step; nothing here runs at application runtime
#
# Usage:
#   scripts/fetch-libchdb.sh <platform-id> [dest-dir]
#   scripts/fetch-libchdb.sh --local <platform-id> <path/to/libchdb.so> [dest-dir]
#
# platform-id is one of: macos-aarch64 macos-x86_64 linux-x86_64-gnu linux-aarch64-gnu
#
# --local installs an engine you built yourself. It skips the checksum gate, so it is a
# developer convenience only: the resulting native package is stamped
# engine.source=local and must never be published.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROPS="${SCRIPT_DIR}/engine.properties"

die() { printf 'fetch-libchdb: %s\n' "$*" >&2; exit 1; }

prop() {
  # Reads key=value from engine.properties. Fails loudly on a missing key rather than
  # returning empty, so a typo cannot silently produce an unchecked download.
  local key="$1" value
  value="$(sed -n "s/^${key}=//p" "$PROPS" | head -n1)"
  [ -n "$value" ] || die "no '${key}' in ${PROPS}"
  printf '%s' "$value"
}

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  else
    shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

LOCAL_MODE=0
if [ "${1:-}" = "--local" ]; then
  LOCAL_MODE=1
  shift
fi

PLATFORM="${1:-}"
[ -n "$PLATFORM" ] || die "usage: $0 [--local] <platform-id> [...]"

if [ "$LOCAL_MODE" = 1 ]; then
  LOCAL_LIB="${2:-}"
  DEST="${3:-${SCRIPT_DIR}/../target/engine/${PLATFORM}}"
  [ -n "$LOCAL_LIB" ] || die "--local needs a path to a libchdb shared library"
  [ -f "$LOCAL_LIB" ] || die "no such file: ${LOCAL_LIB}"
else
  DEST="${2:-${SCRIPT_DIR}/../target/engine/${PLATFORM}}"
fi

ENGINE_VERSION="$(prop engine.version)"
LIBNAME="$(prop "libname.${PLATFORM}")"
mkdir -p "$DEST"

if [ "$LOCAL_MODE" = 1 ]; then
  cp -f "$LOCAL_LIB" "${DEST}/${LIBNAME}.tmp"
  mv -f "${DEST}/${LIBNAME}.tmp" "${DEST}/${LIBNAME}"
  sha256_of "${DEST}/${LIBNAME}" > "${DEST}/${LIBNAME}.sha256"
  cat > "${DEST}/engine.source" <<EOF
engine.source=local
engine.version=${ENGINE_VERSION}
engine.local.path=${LOCAL_LIB}
EOF
  printf 'fetch-libchdb: installed LOCAL engine for %s -> %s (not publishable)\n' \
    "$PLATFORM" "${DEST}/${LIBNAME}"
  exit 0
fi

ASSET="$(prop "asset.${PLATFORM}")"
EXPECTED="$(prop "sha256.${PLATFORM}")"
BASE="$(prop engine.asset.base)"
URL="${BASE}/${ASSET}"

case "$URL" in
  *latest*) die "refusing a 'latest' URL: ${URL}" ;;
esac

# Cache the tarball next to the extract dir so a rebuild does not re-download 100-175 MB.
CACHE="${CHDB_ENGINE_CACHE:-${SCRIPT_DIR}/../target/engine/download}"
mkdir -p "$CACHE"
TARBALL="${CACHE}/${ENGINE_VERSION}-${ASSET}"

if [ -f "$TARBALL" ] && [ "$(sha256_of "$TARBALL")" = "$EXPECTED" ]; then
  printf 'fetch-libchdb: cache hit %s\n' "$TARBALL"
else
  printf 'fetch-libchdb: downloading %s\n' "$URL"
  rm -f "$TARBALL" "${TARBALL}.part"
  curl --fail --location --retry 3 --retry-delay 2 --show-error --silent \
    -o "${TARBALL}.part" "$URL" || die "download failed: ${URL}"
  ACTUAL="$(sha256_of "${TARBALL}.part")"
  if [ "$ACTUAL" != "$EXPECTED" ]; then
    rm -f "${TARBALL}.part"
    die "checksum mismatch for ${ASSET}
  expected ${EXPECTED}
  actual   ${ACTUAL}
The pinned asset does not match scripts/engine.properties. Do not build against it."
  fi
  mv -f "${TARBALL}.part" "$TARBALL"
fi

printf 'fetch-libchdb: extracting %s -> %s\n' "$ASSET" "$DEST"
EXTRACT_TMP="$(mktemp -d "${DEST}/.extract.XXXXXX")"
trap 'rm -rf "$EXTRACT_TMP"' EXIT
tar -xzf "$TARBALL" -C "$EXTRACT_TMP"

FOUND="$(find "$EXTRACT_TMP" -type f \( -name 'libchdb.so' -o -name 'libchdb.dylib' \) | head -n1)"
[ -n "$FOUND" ] || die "no libchdb.so/.dylib inside ${ASSET}"

# Atomic rename: a half-written shared library must never be visible under its real name.
cp -f "$FOUND" "${DEST}/${LIBNAME}.tmp"
mv -f "${DEST}/${LIBNAME}.tmp" "${DEST}/${LIBNAME}"
chmod 0755 "${DEST}/${LIBNAME}"
sha256_of "${DEST}/${LIBNAME}" > "${DEST}/${LIBNAME}.sha256"
cat > "${DEST}/engine.source" <<EOF
engine.source=release
engine.version=${ENGINE_VERSION}
engine.asset=${ASSET}
engine.asset.sha256=${EXPECTED}
engine.asset.url=${URL}
EOF

printf 'fetch-libchdb: %s ready (%s)\n' "${DEST}/${LIBNAME}" "$(sha256_of "${DEST}/${LIBNAME}")"
