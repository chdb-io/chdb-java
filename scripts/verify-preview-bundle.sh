#!/usr/bin/env bash
#
# Consumes a preview bundle the way a user will: installs it into an empty local Maven
# repository and builds a project that is not part of this reactor against it.
#
# The distinction matters, and an earlier version of this check missed it. Installing the
# bundle and then running a class off a hand-built `-cp` proves the JARs execute; it says
# nothing about whether Maven can resolve them. Everything a consumer depends on that a
# classpath does not exercise lives in the POMs -- the parent relationship, the BOM's
# dependencyManagement, the native package's dependency on the driver -- and all three are
# things `install-file` can get wrong. So the consumer here is a real project, outside this
# checkout, resolving from nothing but the repository the bundle was installed into:
#
#   - it declares the native package and nothing else, so `chdb-jdbc` has to arrive
#     transitively through that POM;
#   - it imports chdb-bom and omits every version, so the BOM has to be readable and complete;
#   - it runs a query, on-disk and in-memory, so the engine inside the JAR has to unpack and
#     load from a local repository path rather than from a build directory;
#   - and every org.chdb artifact on the resulting classpath is checked to have come from that
#     repository, because `org.chdb` not being on Central is a fact about today, not a
#     guarantee.
#
# Usage:
#   scripts/verify-preview-bundle.sh <bundle.zip>
#
# Needs unzip, mvn and a JDK. Writes only into a temporary directory, kept on failure.

set -euo pipefail

die() { printf 'verify-preview-bundle: %s\n' "$*" >&2; exit 1; }
step() { printf '\n== %s\n' "$*"; }
ok() { printf '   ok: %s\n' "$*"; }

BUNDLE_ZIP=${1:-}
[[ -n "$BUNDLE_ZIP" ]] || { printf 'usage: %s <bundle.zip>\n' "$0" >&2; exit 2; }
[[ -f "$BUNDLE_ZIP" ]] || die "no such bundle: $BUNDLE_ZIP"
BUNDLE_ZIP=$(cd "$(dirname "$BUNDLE_ZIP")" && printf '%s/%s' "$(pwd)" "$(basename "$BUNDLE_ZIP")")

for command_name in unzip mvn java; do
  command -v "$command_name" >/dev/null 2>&1 || die "required command is not installed: $command_name"
done

MVN_FLAGS=(--batch-mode --no-transfer-progress)
WORK="$(cd "$(mktemp -d "${TMPDIR:-/tmp}/chdb-verify-preview.XXXXXX")" && pwd)"
cleanup() {
  status=$?
  if [ "$status" -eq 0 ]; then
    rm -rf "$WORK"
  else
    printf '\nverify-preview-bundle: left %s in place for diagnosis\n' "$WORK" >&2
  fi
}
trap cleanup EXIT

M2="$WORK/m2"
CONSUMER="$WORK/consumer"
mkdir -p "$M2" "$CONSUMER/src/main/java"

step "Unpacking the bundle"
unzip -q "$BUNDLE_ZIP" -d "$WORK/extracted"
# By the directory that holds preview.properties, not by taking the first one: `jar --create`
# writes a META-INF/MANIFEST.MF of its own beside the bundle directory.
PROPERTIES=$(find "$WORK/extracted" -mindepth 2 -maxdepth 2 -name preview.properties -print -quit)
[[ -n "$PROPERTIES" ]] || die "the bundle has no preview.properties"
BUNDLE_ROOT=$(dirname "$PROPERTIES")

read_property() { awk -F= -v key="$1" '$1 == key { print $2; exit }' "$PROPERTIES"; }
GROUP_ID=$(read_property chdb.java.preview.groupId)
VERSION=$(read_property chdb.java.preview.version)
PLATFORM=$(read_property chdb.java.preview.platform)
[[ -n "$GROUP_ID" && -n "$VERSION" && -n "$PLATFORM" ]] || die "preview.properties is incomplete"
GROUP_PATH=${GROUP_ID//./\/}
ok "$GROUP_ID:$VERSION for $PLATFORM"

# The bundle is per-platform, and the whole point of the run is to execute a query.
case "$(uname -s):$(uname -m)" in
  Darwin:arm64|Darwin:aarch64) HOST=macos-aarch64 ;;
  Darwin:x86_64|Darwin:amd64)  HOST=macos-x86_64 ;;
  Linux:aarch64|Linux:arm64)   HOST=linux-aarch64-gnu ;;
  Linux:x86_64|Linux:amd64)    HOST=linux-x86_64-gnu ;;
  *) die "unsupported host $(uname -s)/$(uname -m)" ;;
esac
[[ "$PLATFORM" == "$HOST" ]] || die "this is a $PLATFORM bundle and the host is $HOST"

step "Installing it into an empty local repository"
install_file() {
  ( cd "$WORK" && mvn "${MVN_FLAGS[@]}" -q -Dmaven.repo.local="$M2" \
      org.apache.maven.plugins:maven-install-plugin:3.1.2:install-file \
      -Dfile="$1" -DpomFile="$2" -Dpackaging="${3:-jar}" -DgeneratePom=false )
}
POMS="$BUNDLE_ROOT/maven-poms"
install_file "$POMS/chdb-java-parent.pom" "$POMS/chdb-java-parent.pom" pom
install_file "$BUNDLE_ROOT/lib/chdb-jdbc-${VERSION}.jar" "$POMS/chdb-jdbc.pom"
install_file "$BUNDLE_ROOT/lib/chdb-native-${PLATFORM}-${VERSION}.jar" "$POMS/chdb-native-${PLATFORM}.pom"
install_file "$POMS/chdb-bom.pom" "$POMS/chdb-bom.pom" pom
ok "parent, driver, native package and BOM installed"

step "Building a project outside this checkout against it"
# One dependency, no version: the BOM has to supply the version and the native package's POM
# has to bring in the driver. A project that named both, with versions, would pass while the
# metadata this check is about was broken.
cat > "$CONSUMER/pom.xml" <<POM
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>org.chdb.verify</groupId>
  <artifactId>preview-consumer</artifactId>
  <version>1</version>
  <properties>
    <maven.compiler.release>11</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>
  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>${GROUP_ID}</groupId>
        <artifactId>chdb-bom</artifactId>
        <version>${VERSION}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>${GROUP_ID}</groupId>
      <artifactId>chdb-native-${PLATFORM}</artifactId>
    </dependency>
  </dependencies>
</project>
POM

cat > "$CONSUMER/src/main/java/PreviewConsumer.java" <<'JAVA'
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/** A user's first five minutes: no Class.forName, one in-memory query, one that persists. */
public final class PreviewConsumer {
    public static void main(String[] args) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:chdb::memory:");
                Statement statement = connection.createStatement();
                ResultSet results = statement.executeQuery("SELECT 42, chdb()")) {
            results.next();
            if (results.getInt(1) != 42) {
                throw new IllegalStateException("SELECT 42 returned " + results.getInt(1));
            }
            System.out.println("engine " + results.getString(2));
        }

        Path storage = Path.of(args[0]);
        try (Connection connection = DriverManager.getConnection("jdbc:chdb:" + storage);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE IF NOT EXISTS verify");
            statement.execute("CREATE TABLE IF NOT EXISTS verify.t (id UInt32) ENGINE = MergeTree ORDER BY id");
            statement.execute("INSERT INTO verify.t SELECT number FROM numbers(1000)");
        }
        try (Connection connection = DriverManager.getConnection("jdbc:chdb:" + storage);
                Statement statement = connection.createStatement();
                ResultSet results = statement.executeQuery("SELECT count() FROM verify.t")) {
            results.next();
            if (results.getLong(1) != 1000) {
                throw new IllegalStateException("reopened storage holds " + results.getLong(1) + " rows");
            }
        }
        System.out.println("PREVIEW BUNDLE OK");
    }
}
JAVA

( cd "$CONSUMER" && mvn "${MVN_FLAGS[@]}" -q -Dmaven.repo.local="$M2" package ) \
  > "$WORK/build.log" 2>&1 || { tail -40 "$WORK/build.log"; die "the consumer project did not build; see $WORK/build.log"; }
ok "resolved and compiled with only the BOM and the native package declared"

( cd "$CONSUMER" && mvn "${MVN_FLAGS[@]}" -q -Dmaven.repo.local="$M2" \
    dependency:build-classpath -Dmdep.outputFile="$WORK/cp.txt" ) \
  > "$WORK/resolve.log" 2>&1 || { tail -40 "$WORK/resolve.log"; die "could not resolve the consumer's classpath"; }

# The driver has to be here through the native package's POM, not because it was asked for.
grep -q "chdb-jdbc-${VERSION}.jar" "$WORK/cp.txt" \
  || die "chdb-jdbc is not on the classpath: the native package's POM did not bring it in"
ok "chdb-jdbc arrived transitively"

# Everything under the group has to have come from the repository the bundle was installed
# into. A copy elsewhere serving these coordinates would make the whole run meaningless.
while IFS= read -r entry; do
  case "$entry" in
    *"/${GROUP_PATH}/"*)
      [[ "$entry" == "$M2/"* ]] || die "$entry did not come from $M2"
      ;;
  esac
done < <(tr ':' '\n' < "$WORK/cp.txt")
ok "every $GROUP_ID artifact resolved from the bundle's repository"

step "Running a query through it"
OUTPUT=$( cd "$CONSUMER" && java -cp "target/classes:$(cat "$WORK/cp.txt")" \
  -Dchdb.cache.dir="$WORK/native-cache" PreviewConsumer "$WORK/storage" )
printf '%s\n' "$OUTPUT"
grep -q 'PREVIEW BUNDLE OK' <<<"$OUTPUT" || die "the consumer did not complete its queries"

printf '\nverify-preview-bundle: %s is installable and works, %s\n' "$(basename "$BUNDLE_ZIP")" "$PLATFORM"
