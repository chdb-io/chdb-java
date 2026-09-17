#!/usr/bin/env bash
#
# Reads the same expressions through chdb-jdbc and clickhouse-jdbc and reports where they
# disagree. See docs/type-parity-clickhouse-jdbc.md for what the last run found.
#
# Not a test. It needs a ClickHouse server and network access to resolve clickhouse-jdbc, so
# it is a tool you run deliberately when the type mapping changes -- the findings it produced
# are pinned as assertions in TypeMatrixIT instead.
#
#   scripts/run-type-parity.sh [platform-id]
#
# The server is pinned to the same version as scripts/engine.properties so that a difference
# is the driver's and not the engine's. Both sides run in UTC: chDB takes its timezone from
# the host, a container server takes UTC, and that alone accounts for five apparent
# differences in DateTime64.
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PLATFORM="${1:-macos-aarch64}"
case "$PLATFORM" in
  macos-*) OS=macos ;;
  *)       OS=linux ;;
esac
case "$PLATFORM" in
  *aarch64*) ARCH=aarch64 ;;
  *)         ARCH=x86_64 ;;
esac

die() { printf 'run-type-parity: %s\n' "$1" >&2; exit 1; }

ENGINE_VERSION="$(sed -n 's/^engine\.version=//p' "${ROOT}/scripts/engine.properties" | head -n1)"
[ -n "$ENGINE_VERSION" ] || die "no engine.version in scripts/engine.properties"

LIBS="${ROOT}/chdb-native-${PLATFORM}/target/native/META-INF/chdb/native/${OS}/${ARCH}"
[ -f "${LIBS}/libchdb.so" ] || die "no staged engine in ${LIBS}; run scripts/build-native.sh ${PLATFORM}"
[ -d "${ROOT}/chdb-jdbc/target/classes" ] || die "run 'mvn -pl chdb-jdbc compile' first"

CH_PORT="${CH_PORT:-18999}"
CONTAINER="${CH_CONTAINER:-chdb-type-parity}"
JAVA="${JAVA_HOME:+${JAVA_HOME}/bin/}java"
JAVAC="${JAVA_HOME:+${JAVA_HOME}/bin/}javac"

if ! curl -fsS --max-time 3 "http://localhost:${CH_PORT}/ping" >/dev/null 2>&1; then
  printf 'run-type-parity: starting clickhouse-server %s on port %s\n' "$ENGINE_VERSION" "$CH_PORT"
  docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
  # The same version the engine is built from, so the comparison isolates the driver.
  docker run -d --name "$CONTAINER" \
    -e CLICKHOUSE_USER=parity -e CLICKHOUSE_PASSWORD=parity \
    -e CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT=1 \
    -p "${CH_PORT}:8123" "clickhouse/clickhouse-server:${ENGINE_VERSION}" >/dev/null
  for _ in $(seq 1 40); do
    curl -fsS --max-time 3 "http://localhost:${CH_PORT}/ping" >/dev/null 2>&1 && break
    sleep 3
  done
  curl -fsS --max-time 3 "http://localhost:${CH_PORT}/ping" >/dev/null 2>&1 \
    || die "clickhouse-server did not come up on port ${CH_PORT}"
fi

WORK="${ROOT}/target/type-parity"
mkdir -p "$WORK/classes"
cat > "$WORK/pom.xml" <<POM
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>org.chdb.tools</groupId><artifactId>type-parity-cp</artifactId><version>1</version>
  <dependencies>
    <dependency>
      <groupId>com.clickhouse</groupId><artifactId>clickhouse-jdbc</artifactId>
      <version>${CLICKHOUSE_JDBC_VERSION:-0.10.0}</version>
    </dependency>
  </dependencies>
</project>
POM
( cd "$WORK" && mvn -q -B dependency:build-classpath -Dmdep.outputFile="${WORK}/cp.txt" )

CP="${ROOT}/chdb-jdbc/target/classes:$(cat "${WORK}/cp.txt")"
"$JAVAC" -nowarn -cp "$CP" -d "$WORK/classes" "${ROOT}"/tools/type-parity/*.java

# UTC on both sides. chDB reads the host timezone and the container server is UTC; without
# this, DateTime64 looks like a driver disagreement when it is only a zone difference.
TZ=UTC "$JAVA" -cp "${WORK}/classes:${CP}" -Duser.timezone=UTC \
  -Dchdb.library.path="$LIBS" \
  -Dch.url="jdbc:clickhouse://localhost:${CH_PORT}/default" \
  Parity
