#!/usr/bin/env bash
# Runner labels do not guarantee Maven is installed (including ubuntu-latest ARM64).
# Download only the pinned Apache distribution and verify it before executing anything.
set -euo pipefail

MAVEN_VERSION=3.9.16
# Apache's apache-maven-3.9.16-bin.tar.gz.sha512, pinned alongside the version.
MAVEN_SHA512=831a8591fe20c8243b1dbe7d71e3244f31d1665b0804b2e825e38cbbe5ce0cafb8338851f90780735568773e0a6cd07bbec107cda0b896b008b861075358b6f6

: "${RUNNER_TEMP:?RUNNER_TEMP must identify the runner temporary directory}"
: "${GITHUB_PATH:?GITHUB_PATH must identify the Actions path file}"

MAVEN_SETUP_DIR="$(mktemp -d "${RUNNER_TEMP%/}/chdb-maven.XXXXXX")"
MAVEN_ARCHIVE="$MAVEN_SETUP_DIR/apache-maven-$MAVEN_VERSION-bin.tar.gz"
MAVEN_URL="https://archive.apache.org/dist/maven/maven-3/$MAVEN_VERSION/binaries/apache-maven-$MAVEN_VERSION-bin.tar.gz"

printf 'Installing Apache Maven %s from its pinned distribution\n' "$MAVEN_VERSION"
curl --disable --fail --location --proto '=https' --proto-redir '=https' \
  --silent --show-error --retry 3 --connect-timeout 15 --max-time 180 \
  --output "$MAVEN_ARCHIVE" "$MAVEN_URL"

if command -v sha512sum >/dev/null 2>&1; then
  MAVEN_ACTUAL_SHA512="$(sha512sum "$MAVEN_ARCHIVE" | awk '{print $1}')"
else
  MAVEN_ACTUAL_SHA512="$(shasum -a 512 "$MAVEN_ARCHIVE" | awk '{print $1}')"
fi
if [ "$MAVEN_ACTUAL_SHA512" != "$MAVEN_SHA512" ]; then
  printf 'Maven archive SHA-512 mismatch; refusing to extract or execute it.\n' >&2
  exit 1
fi

tar -xzf "$MAVEN_ARCHIVE" -C "$MAVEN_SETUP_DIR"
MAVEN_BIN="$MAVEN_SETUP_DIR/apache-maven-$MAVEN_VERSION/bin"
"$MAVEN_BIN/mvn" --version
printf '%s\n' "$MAVEN_BIN" >> "$GITHUB_PATH"
