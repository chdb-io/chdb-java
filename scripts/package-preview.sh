#!/usr/bin/env bash

set -euo pipefail

usage() {
  printf 'Usage: %s <version> <platform> <output-dir>\n' "$(basename "$0")" >&2
  printf 'Platforms: macos-aarch64, macos-x86_64, linux-aarch64-gnu, linux-x86_64-gnu\n' >&2
}

die() {
  printf 'error: %s\n' "$1" >&2
  exit 1
}

if [[ $# -ne 3 ]]; then
  usage
  exit 2
fi

VERSION=$1
PLATFORM=$2
OUTPUT_DIR=$3
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)

case "$VERSION" in
  ''|*[!A-Za-z0-9._-]*) die "version contains unsupported characters: $VERSION" ;;
esac

case "$PLATFORM" in
  macos-aarch64|macos-x86_64|linux-aarch64-gnu|linux-x86_64-gnu) ;;
  *) die "unsupported platform: $PLATFORM" ;;
esac

case "$PLATFORM" in
  macos-aarch64)    NATIVE_OS=macos; NATIVE_ARCH=aarch64; JNI_SUFFIX=dylib ;;
  macos-x86_64)     NATIVE_OS=macos; NATIVE_ARCH=x86_64;  JNI_SUFFIX=dylib ;;
  linux-aarch64-gnu) NATIVE_OS=linux; NATIVE_ARCH=aarch64; JNI_SUFFIX=so ;;
  linux-x86_64-gnu)  NATIVE_OS=linux; NATIVE_ARCH=x86_64;  JNI_SUFFIX=so ;;
esac

DRIVER_JAR="$ROOT/chdb-jdbc/target/chdb-jdbc-${VERSION}.jar"
NATIVE_JAR="$ROOT/chdb-native-${PLATFORM}/target/chdb-native-${PLATFORM}-${VERSION}.jar"
PARENT_POM="$ROOT/pom.xml"
DRIVER_POM="$ROOT/chdb-jdbc/pom.xml"
NATIVE_POM="$ROOT/chdb-native-${PLATFORM}/pom.xml"
BOM_POM="$ROOT/chdb-bom/pom.xml"

for required_file in "$DRIVER_JAR" "$NATIVE_JAR" "$PARENT_POM" "$DRIVER_POM" "$NATIVE_POM" "$BOM_POM" "$ROOT/LICENSE" "$ROOT/README.md"; do
  [[ -f "$required_file" ]] || die "required file does not exist: $required_file"
done

GROUP_ID=$(awk '
  /<groupId>/ {
    sub(/^.*<groupId>/, "")
    sub(/<\/groupId>.*$/, "")
    print
    exit
  }
' "$PARENT_POM")
[[ -n "$GROUP_ID" ]] || die "could not determine the Maven groupId from $PARENT_POM"

mkdir -p "$OUTPUT_DIR"
OUTPUT_DIR=$(cd -- "$OUTPUT_DIR" && pwd)
ASSET="$OUTPUT_DIR/chdb-java-${VERSION}-${PLATFORM}.zip"
WORK=$(mktemp -d "${TMPDIR:-/tmp}/chdb-java-preview.XXXXXX")
trap 'rm -rf "$WORK"' EXIT

BUNDLE_ROOT="$WORK/chdb-java-${VERSION}-${PLATFORM}"
mkdir -p "$BUNDLE_ROOT/lib" "$BUNDLE_ROOT/maven-poms"

cp "$DRIVER_JAR" "$BUNDLE_ROOT/lib/"
cp "$NATIVE_JAR" "$BUNDLE_ROOT/lib/"
cp "$PARENT_POM" "$BUNDLE_ROOT/maven-poms/chdb-java-parent.pom"
cp "$DRIVER_POM" "$BUNDLE_ROOT/maven-poms/chdb-jdbc.pom"
cp "$NATIVE_POM" "$BUNDLE_ROOT/maven-poms/chdb-native-${PLATFORM}.pom"
cp "$BOM_POM" "$BUNDLE_ROOT/maven-poms/chdb-bom.pom"
cp "$ROOT/LICENSE" "$BUNDLE_ROOT/LICENSE"
cp "$ROOT/README.md" "$BUNDLE_ROOT/README.md"

printf 'chdb.java.preview.groupId=%s\nchdb.java.preview.version=%s\nchdb.java.preview.platform=%s\n' \
  "$GROUP_ID" "$VERSION" "$PLATFORM" > "$BUNDLE_ROOT/preview.properties"

if ! command -v jar >/dev/null 2>&1; then
  die "the Java jar tool is required to create the preview bundle"
fi
if ! command -v unzip >/dev/null 2>&1; then
  die "unzip is required to validate the native JAR"
fi

NATIVE_ROOT="META-INF/chdb/native/${NATIVE_OS}/${NATIVE_ARCH}"
NATIVE_CONTENTS="$WORK/native-jar-contents.txt"
unzip -l "$NATIVE_JAR" > "$NATIVE_CONTENTS"
for entry in \
  "$NATIVE_ROOT/libchdb.so" \
  "$NATIVE_ROOT/libchdb_java_jni.${JNI_SUFFIX}" \
  "$NATIVE_ROOT/manifest.properties" \
  "$NATIVE_ROOT/sha256sums.txt" \
  "META-INF/sbom/bom.json"; do
  grep -Fq "$entry" "$NATIVE_CONTENTS" || die "native JAR is missing $entry: $NATIVE_JAR"
done
grep -Fq 'META-INF/licenses/' "$NATIVE_CONTENTS" || die "native JAR is missing its license inventory: $NATIVE_JAR"

rm -f "$ASSET"
jar --create --file="$ASSET" -C "$WORK" "$(basename "$BUNDLE_ROOT")"

printf 'Created %s (%s bytes)\n' "$ASSET" "$(wc -c < "$ASSET" | tr -d ' ')"
