#!/usr/bin/env bash
#
# Installs a published preview into a local Maven repository.
#
# Until `org.chdb` exists on Maven Central there is nowhere for `mvn` to resolve the driver
# from, so a preview ships as one GitHub Release asset per platform: a zip holding the driver
# jar, the native package for that platform, and the four POMs the build produced. This script
# downloads the asset for the host it runs on, verifies it against the release's SHA256SUMS,
# and installs the contents with `install-file` so an ordinary `<dependency>` resolves.
#
# It is deliberately the same bytes a Central release would carry, installed by hand rather
# than rebuilt: the POMs come out of the bundle rather than being generated here, so a preview
# a user installs is the artifact CI packaged, not an approximation of it.
#
# Usage:
#   install-preview.sh v1.0.0-preview.1 [--repo OWNER/REPO] [--maven-repo PATH]
#
# Needs curl, unzip and mvn. Writes nothing outside the local Maven repository and one
# temporary directory.

set -euo pipefail

usage() {
  cat >&2 <<'EOF'
Usage: install-preview.sh <release-tag> [--repo OWNER/REPO] [--maven-repo PATH]

Downloads the matching platform bundle from a GitHub Release and installs its
POMs and JARs into the local Maven repository.
EOF
}

die() {
  printf 'error: %s\n' "$1" >&2
  exit 1
}

TAG=''
REPO='chdb-io/chdb-java'
MAVEN_REPO=${MAVEN_REPO:-"$HOME/.m2/repository"}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help)
      usage
      exit 0
      ;;
    --repo)
      [[ $# -ge 2 ]] || die "--repo requires OWNER/REPO"
      REPO=$2
      shift 2
      ;;
    --repo=*)
      REPO=${1#*=}
      shift
      ;;
    --maven-repo)
      [[ $# -ge 2 ]] || die "--maven-repo requires a path"
      MAVEN_REPO=$2
      shift 2
      ;;
    --maven-repo=*)
      MAVEN_REPO=${1#*=}
      shift
      ;;
    --*)
      die "unknown option: $1"
      ;;
    '')
      die "release tag cannot be empty"
      ;;
    *)
      [[ -z "$TAG" ]] || die "only one release tag may be supplied"
      TAG=$1
      shift
      ;;
  esac
done

[[ -n "$TAG" ]] || { usage; exit 2; }
[[ "$REPO" =~ ^[^/]+/[^/]+$ ]] || die "repository must look like OWNER/REPO: $REPO"
case "$TAG" in
  v*-preview.*) ;;
  *) die "release tag must look like v1.0.0-preview.1: $TAG" ;;
esac
VERSION=${TAG#v}

case "$(uname -s):$(uname -m)" in
  Darwin:arm64|Darwin:aarch64)
    PLATFORM='macos-aarch64'
    ;;
  Darwin:x86_64|Darwin:amd64)
    PLATFORM='macos-x86_64'
    ;;
  Linux:aarch64|Linux:arm64)
    PLATFORM='linux-aarch64-gnu'
    ;;
  Linux:x86_64|Linux:amd64)
    PLATFORM='linux-x86_64-gnu'
    ;;
  *)
    die "unsupported host $(uname -s)/$(uname -m); chdb-java preview supports Linux and macOS on x86_64/aarch64"
    ;;
esac

for command_name in curl unzip mvn; do
  command -v "$command_name" >/dev/null 2>&1 || die "required command is not installed: $command_name"
done

ASSET="chdb-java-${VERSION}-${PLATFORM}.zip"
BASE_URL="https://github.com/${REPO}/releases/download/${TAG}"
WORK=$(mktemp -d "${TMPDIR:-/tmp}/chdb-java-preview.XXXXXX")
trap 'rm -rf "$WORK"' EXIT

# curl's own "error: 22" says nothing about which of the two plausible mistakes was made --
# a tag that does not exist, or a release that never carried this platform -- and both are
# ordinary enough to name.
download() {
  local name=$1
  curl --fail --location --retry 3 --silent --show-error \
    --output "$WORK/$name" "$BASE_URL/$name" && return 0
  die "cannot download $name from $BASE_URL.
Check that the release exists and publishes this asset: https://github.com/${REPO}/releases/tag/${TAG}"
}

download "$ASSET"
download SHA256SUMS

EXPECTED=$(awk -v asset="$ASSET" '$2 == asset || $3 == asset { print $1; exit }' "$WORK/SHA256SUMS")
[[ "$EXPECTED" =~ ^[[:xdigit:]]{64}$ ]] || die "no valid checksum found for $ASSET"

if command -v sha256sum >/dev/null 2>&1; then
  ACTUAL=$(sha256sum "$WORK/$ASSET" | awk '{print $1}')
else
  command -v shasum >/dev/null 2>&1 || die "sha256sum or shasum is required to verify the bundle"
  ACTUAL=$(shasum -a 256 "$WORK/$ASSET" | awk '{print $1}')
fi
[[ "$EXPECTED" == "$ACTUAL" ]] || die "checksum mismatch for $ASSET"

unzip -q "$WORK/$ASSET" -d "$WORK/extracted"
BUNDLE_ROOT="$WORK/extracted/chdb-java-${VERSION}-${PLATFORM}"
[[ -d "$BUNDLE_ROOT" ]] || die "unexpected bundle layout in $ASSET"

PARENT_POM="$BUNDLE_ROOT/maven-poms/chdb-java-parent.pom"
DRIVER_POM="$BUNDLE_ROOT/maven-poms/chdb-jdbc.pom"
NATIVE_POM="$BUNDLE_ROOT/maven-poms/chdb-native-${PLATFORM}.pom"
BOM_POM="$BUNDLE_ROOT/maven-poms/chdb-bom.pom"
PREVIEW_PROPERTIES="$BUNDLE_ROOT/preview.properties"
DRIVER_JAR=$(find "$BUNDLE_ROOT/lib" -maxdepth 1 -type f -name 'chdb-jdbc-*.jar' -print -quit)
NATIVE_JAR=$(find "$BUNDLE_ROOT/lib" -maxdepth 1 -type f -name "chdb-native-${PLATFORM}-*.jar" -print -quit)

for required_file in "$PARENT_POM" "$DRIVER_POM" "$NATIVE_POM" "$BOM_POM" "$PREVIEW_PROPERTIES" "$DRIVER_JAR" "$NATIVE_JAR"; do
  [[ -n "$required_file" && -f "$required_file" ]] || die "bundle is missing $required_file"
done

read_property() {
  awk -F= -v key="$1" '$1 == key { print $2; exit }' "$PREVIEW_PROPERTIES"
}

GROUP_ID=$(read_property chdb.java.preview.groupId)
[[ -n "$GROUP_ID" ]] || die "bundle does not declare a Maven groupId"

# The asset name is chosen by this script from the tag, so a release that uploaded a bundle
# built at a different version would be installed under a version it does not carry.
BUNDLE_VERSION=$(read_property chdb.java.preview.version)
[[ "$BUNDLE_VERSION" == "$VERSION" ]] || \
  die "bundle declares version $BUNDLE_VERSION but $TAG names $VERSION; the release asset does not match its tag"

install_file() {
  local file=$1
  local pom=$2
  local packaging=${3:-jar}

  (
    # Do not let Maven inspect the caller's project POM. The preview POMs are the only metadata
    # this operation needs, and a caller may be installing from an unrelated Maven project.
    cd "$WORK"
    mvn -q -B -Dmaven.repo.local="$MAVEN_REPO" \
      org.apache.maven.plugins:maven-install-plugin:3.1.2:install-file \
      -Dfile="$file" \
      -DpomFile="$pom" \
      -Dpackaging="$packaging" \
      -DgeneratePom=false
  )
}

# Install the parent first because the module POMs retain their normal parent relationship.
install_file "$PARENT_POM" "$PARENT_POM" pom
install_file "$DRIVER_JAR" "$DRIVER_POM"
install_file "$NATIVE_JAR" "$NATIVE_POM"
install_file "$BOM_POM" "$BOM_POM" pom

printf 'Installed chdb-java preview %s (%s) into %s\n' "$TAG" "$PLATFORM" "$MAVEN_REPO"
printf 'Driver: %s:chdb-jdbc:%s\n' "$GROUP_ID" "$VERSION"
printf 'Native: %s:chdb-native-%s:%s\n' "$GROUP_ID" "$PLATFORM" "$VERSION"
