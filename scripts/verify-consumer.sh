#!/usr/bin/env bash
#
# Consumes the driver the way a user does -- from a Maven repository, in a project that is not
# part of this build -- and asserts the things that only fail when consumed.
#
# Issue #10 is the release gate that says nothing has ever depended on this as a published
# artifact: every test to date builds it in the same reactor, where the POMs are read from
# target/ and the native libraries are found next to them. That hides five questions. This
# script answers four and a half of them, locally, in a few minutes, without publishing
# anything:
#
#   1. do the POMs resolve from a repository rather than from target/?   -- yes, checked below
#   2. does the BOM do what a BOM should when imported?                  -- yes, checked below
#   3. does declaring exactly one platform package yield a working
#      driver, and what is the error when someone declares none or
#      the wrong one?                                                    -- checked below
#   4. does a 98-166 MB artifact upload and download intact?             -- NOT covered here.
#      This is the half that needs a real repository. `mvn deploy` to a file:// URL is a copy,
#      not a transfer, so it cannot fail the way an upload can. The release workflow's deploy
#      job verifies sha256sums.txt after the artifact round trip, which covers the CI half of
#      it; only Central itself can cover the rest.
#   5. does the native extraction path behave when the JAR comes from a
#      local repository rather than a build directory?                   -- yes, checked below
#
# How it works, and why it is arranged this way:
#
#   `mvn deploy -DaltDeploymentRepository=...file://` writes a real repository layout --
#   POMs, maven-metadata.xml, checksums -- into a temporary directory. The consumer project
#   then resolves from that directory with its own empty local repository, so nothing it gets
#   can have come from this reactor or from the developer's ~/.m2. `-Dmaven.install.skip=true`
#   keeps the ~100 MB platform JAR out of the real ~/.m2 as well.
#
#   The consumer lives in a temporary directory outside this checkout on purpose. Put it in
#   the reactor and Maven resolves the modules from the reactor, which is the exact thing this
#   script exists to stop doing.
#
# Usage:
#   scripts/verify-consumer.sh [platform-id]
#
# Defaults to this machine's platform, which must already be staged:
#   mvn -pl chdb-jdbc compile && scripts/build-native.sh <platform>
#
# Needs network on first run: the consumer's empty local repository has to fetch Maven's own
# plugins. Everything under org.chdb comes from the file repository, and the script asserts it.
#
# What is isolated, and what is not:
#
#   org.chdb artifacts   isolated. The consumer's project repositories are the throwaway file
#                        repository and a disabled `central`, and every downloaded file's
#                        recorded provenance is checked afterwards. Nothing else may serve
#                        them, because a good copy on Central masking a bad one we just
#                        deployed is the single failure this script exists to detect.
#   Maven's own plugins  not isolated, on purpose. They come from the real Central through the
#                        inherited pluginRepositories. Vendoring them would make this script
#                        unrunnable for no gain: org.chdb is never a plugin dependency.
#   the local repository overridden, not shared: -Dmaven.repo.local points at an empty
#                        directory, so nothing can be satisfied from a previous build.
#   settings.xml         not suppressed. A caller's mirrors and profiles still apply, and a
#                        <mirrorOf>*</mirrorOf> would intercept even the file repository -- so
#                        the provenance check fails loudly on such a machine rather than
#                        reporting a pass it cannot justify. Its message says to re-run with
#                        -s. Suppressing settings.xml outright would break the environments
#                        where that mirror is the only route to Central for the plugins above.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

die() { printf 'verify-consumer: %s\n' "$*" >&2; exit 1; }
step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
ok() { printf '   ok: %s\n' "$*"; }

MVN_FLAGS=(--batch-mode --no-transfer-progress)

# ---------------------------------------------------------------- platform

case "$(uname -s)" in
  Darwin) HOST_OS=macos ;;
  Linux)  HOST_OS=linux ;;
  *)      die "unsupported host OS $(uname -s)" ;;
esac
case "$(uname -m)" in
  arm64|aarch64) HOST_ARCH=aarch64 ;;
  x86_64|amd64)  HOST_ARCH=x86_64 ;;
  *)             die "unsupported host CPU $(uname -m)" ;;
esac
DEFAULT_PLATFORM="${HOST_OS}-${HOST_ARCH}"
[ "$HOST_OS" = linux ] && DEFAULT_PLATFORM="${DEFAULT_PLATFORM}-gnu"

PLATFORM="${1:-$DEFAULT_PLATFORM}"
case "$PLATFORM" in
  macos-aarch64)     OS=macos; ARCH=aarch64; JNIEXT=dylib ;;
  macos-x86_64)      OS=macos; ARCH=x86_64;  JNIEXT=dylib ;;
  linux-x86_64-gnu)  OS=linux; ARCH=x86_64;  JNIEXT=so ;;
  linux-aarch64-gnu) OS=linux; ARCH=aarch64; JNIEXT=so ;;
  *) die "unknown platform '${PLATFORM}'" ;;
esac
[ "$OS" = "$HOST_OS" ] && [ "$ARCH" = "$HOST_ARCH" ] \
  || die "cannot run ${PLATFORM} on ${HOST_OS}-${HOST_ARCH}: the point of this script is to run a query."

MODULE="chdb-native-${PLATFORM}"
STAGED="${ROOT}/${MODULE}/target/native/META-INF/chdb/native/${OS}/${ARCH}"
[ -f "${STAGED}/libchdb.so" ] || die "${PLATFORM} is not staged (no ${STAGED}/libchdb.so).
Run: mvn -pl chdb-jdbc compile && scripts/build-native.sh ${PLATFORM}"

# The other platform's coordinate, for the "declared the wrong one" case below.
case "$ARCH" in
  aarch64) OTHER_ARCH=x86_64 ;;
  *)       OTHER_ARCH=aarch64 ;;
esac
OTHER_PLATFORM="${OS}-${OTHER_ARCH}"
[ "$OS" = linux ] && OTHER_PLATFORM="${OTHER_PLATFORM}-gnu"

ENGINE_VERSION="$(sed -n 's/^engine.version=//p' "${SCRIPT_DIR}/engine.properties" | head -n1)"

# Read from Maven rather than with sed, and read it first: it is the cheapest thing here that
# can fail, and an unparseable POM should not be discovered forty seconds into a deploy.
VERSION="$(cd "$ROOT" && mvn "${MVN_FLAGS[@]}" -q -DforceStdout help:evaluate -Dexpression=project.version)" \
  || die "could not read the project version; the POMs do not parse"

# cd/pwd rather than mktemp's own answer: TMPDIR usually ends in a slash, so mktemp hands back
# a path with a doubled separator, and Maven reports the normalised form. The check below that
# every org.chdb artifact came from the consumer's own local repository compares paths as
# strings, and would fail on that difference alone.
WORK="$(cd "$(mktemp -d "${TMPDIR:-/tmp}/chdb-verify-consumer.XXXXXX")" && pwd)"

# Kept on failure, removed on success. The interesting failures here are a Maven deploy or a
# dependency resolution that did not work, and both write their whole output to a log under
# $WORK -- which an unconditional `rm -rf` deleted a moment before the message telling you to
# go and read it. On success there is nothing in there worth 200 MB of disk.
cleanup() {
  status=$?
  if [ "$status" -eq 0 ]; then
    rm -rf "$WORK"
  else
    printf '\nverify-consumer: left %s in place for diagnosis:\n' "$WORK" >&2
    for log in deploy.log resolve.log; do
      [ -f "${WORK}/${log}" ] && printf '  %s\n' "${WORK}/${log}" >&2
    done
    printf '  remove it when you are done.\n' >&2
  fi
}
trap cleanup EXIT
REPO="${WORK}/repository"
CONSUMER="${WORK}/consumer"
CONSUMER_M2="${WORK}/consumer-m2"
CACHE="${WORK}/native-cache"
mkdir -p "$REPO" "$CONSUMER/src" "$CONSUMER_M2" "$CACHE"

printf 'platform:      %s\n' "$PLATFORM"
printf 'engine:        %s\n' "$ENGINE_VERSION"
printf 'version:       %s\n' "$VERSION"
printf 'work:          %s\n' "$WORK"

# ---------------------------------------------------------------- 1. publish to a file repo

step "Deploying to a throwaway file:// repository"

# `deploy`, not `install`: it is the phase that writes a repository layout with
# maven-metadata.xml, which is what a consumer resolving a -SNAPSHOT actually reads. Install
# is skipped so a ~100 MB platform JAR does not land in the developer's real ~/.m2.
#
# Only the published modules: chdb-integration-tests and chdb-examples set
# maven.deploy.skip, and building them here would run the reactor tests this script is not
# about.
(
  cd "$ROOT"
  mvn "${MVN_FLAGS[@]}" deploy \
    -pl ".,chdb-bom,chdb-jdbc,${MODULE}" \
    -DskipTests \
    -Dmaven.install.skip=true \
    -DaltDeploymentRepository="chdb-verify::file://${REPO}"
) > "${WORK}/deploy.log" 2>&1 || { tail -40 "${WORK}/deploy.log"; die "deploy to the file repository failed; see ${WORK}/deploy.log"; }

# Every artifact a consumer needs has to be in there, POM included -- a jar with no POM
# resolves for a direct dependency and then fails to bring in chdb-jdbc.
for coord in \
  "org/chdb/chdb-java-parent/${VERSION}" \
  "org/chdb/chdb-bom/${VERSION}" \
  "org/chdb/chdb-jdbc/${VERSION}" \
  "org/chdb/${MODULE}/${VERSION}"; do
  [ -d "${REPO}/${coord}" ] || die "nothing published at ${coord}"
  # A JAR with no POM resolves for a direct dependency and then quietly brings in nothing.
  compgen -G "${REPO}/${coord}/*.pom" > /dev/null || die "no POM published at ${coord}"
done
ok "parent, BOM, driver and ${MODULE} are in the repository, each with a POM"

case "$VERSION" in
  *-SNAPSHOT)
    # A snapshot deploy does not write chdb-jdbc-<version>-SNAPSHOT.jar. It writes
    # chdb-jdbc-26.7.0.1-20260909.053707-1.jar and a maven-metadata.xml that maps -SNAPSHOT
    # onto that timestamp, and a consumer cannot resolve the version without it. Nothing in
    # the reactor exercises that indirection, which is the sort of thing issue #10 is about.
    [ -f "${REPO}/org/chdb/chdb-jdbc/${VERSION}/maven-metadata.xml" ] \
      || die "no snapshot metadata for chdb-jdbc; a consumer could not resolve ${VERSION}"
    ok "snapshot metadata maps ${VERSION} onto the timestamped filenames"
    ;;
esac

NATIVE_JAR=""
for candidate in "${REPO}/org/chdb/${MODULE}/${VERSION}/"*.jar; do
  case "$candidate" in *-sources.jar|*-javadoc.jar) continue ;; esac
  NATIVE_JAR="$candidate"
  break
done
[ -n "$NATIVE_JAR" ] || die "no platform JAR published for ${MODULE}"
printf 'native jar:    %s MB\n' "$(( $(wc -c < "$NATIVE_JAR") / 1024 / 1024 ))"

# ---------------------------------------------------------------- 2. the consumer project

step "Building a consumer project outside this checkout"

# The shape the README tells a user to write: import the BOM, declare one platform package,
# name no versions. If the BOM does not manage the versions this will not resolve.
cat > "${CONSUMER}/pom.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>org.example</groupId>
  <artifactId>chdb-consumer</artifactId>
  <version>1</version>
  <packaging>jar</packaging>

  <repositories>
    <repository>
      <id>chdb-verify</id>
      <url>file://${REPO}</url>
      <releases><enabled>true</enabled></releases>
      <snapshots><enabled>true</enabled><updatePolicy>always</updatePolicy></snapshots>
    </repository>
    <!--
      The Super POM's \`central\`, switched off rather than merely outranked.

      A repository declared here is *added* to the inherited \`central\`, not substituted for
      it, so with only the block above there were two places an org.chdb artifact could come
      from. That is fatal to the point of this script: once we publish, a broken artifact we
      have just deployed to \${REPO} could be masked by a good one on Central, and the check
      would report that consuming the driver from a repository works. The one thing it exists
      to catch is exactly that.

      Both \`releases\` and \`snapshots\` disabled means the resolver never consults it, and the
      URL names nothing so a future change that re-enabled it would fail loudly rather than
      reach the network. Verified by asking for a Central-only artifact and watching it fail:
      "Could not find artifact com.zaxxer:HikariCP:jar:5.1.0 in chdb-verify".

      Kept under its own id rather than renaming the block above to \`central\`, which is the
      other way to do this. Two reasons. Provenance: Maven records the repository an artifact
      came from by *id* in _remote.repositories, so reusing \`central\` would make our file
      repository indistinguishable from the real one and the assertion further down could not
      be written. And plugin repositories keep the id \`central\` pointing at the real Central
      (see below), so an artifact fetched through that path is recorded as \`central=\` and
      would then look available from \`central\` to project-scope resolution too, because that
      bookkeeping is keyed by id and not by URL.
    -->
    <repository>
      <id>central</id>
      <url>file://${WORK}/there-is-no-such-repository</url>
      <releases><enabled>false</enabled></releases>
      <snapshots><enabled>false</enabled></snapshots>
    </repository>
  </repositories>

  <!--
    pluginRepositories deliberately left inherited, so Maven's own plugins still come from the
    real Central. Isolating them would mean vendoring surefire, the dependency plugin and their
    transitive trees into \${REPO} to make this script run at all, and it would buy nothing:
    plugin repositories are consulted for plugins and their dependencies, and org.chdb is
    neither. The asymmetry is the point: our artifacts are isolated, Maven's are not.
  -->

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.chdb</groupId>
        <artifactId>chdb-bom</artifactId>
        <version>${VERSION}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <dependencies>
    <!-- No version: if the BOM does not supply it, this project does not build. -->
    <dependency>
      <groupId>org.chdb</groupId>
      <artifactId>${MODULE}</artifactId>
    </dependency>
  </dependencies>
</project>
EOF

# A fresh, empty local repository, so nothing can be resolved from a previous build of this
# checkout. Maven's own plugins come from Central; everything under org.chdb must come from
# the file repository, which is asserted below.
(
  cd "$CONSUMER"
  mvn "${MVN_FLAGS[@]}" -Dmaven.repo.local="$CONSUMER_M2" \
    dependency:build-classpath -Dmdep.outputFile="${WORK}/cp.txt"
) > "${WORK}/resolve.log" 2>&1 || { tail -40 "${WORK}/resolve.log"; die "the consumer could not resolve its dependencies; see ${WORK}/resolve.log"; }

CP="$(cat "${WORK}/cp.txt")"
ok "resolved with no version declared, so the imported BOM supplied it"

# The point of the whole exercise: these bytes came out of a repository.
# Split on the classpath separator rather than piping into a loop: a `die` inside a pipeline
# runs in a subshell, so the failure would be reported and then ignored.
IFS=':' read -r -a CP_ENTRIES <<< "$CP"
for entry in "${CP_ENTRIES[@]}"; do
  case "$entry" in
    *org/chdb/*)
      case "$entry" in
        "${CONSUMER_M2}"/*) ;;
        *) die "an org.chdb artifact resolved from outside the consumer's local repository: ${entry}" ;;
      esac
      ;;
  esac
done

# ...and out of *our* repository, which the check above cannot tell. Every path it accepts is
# under the consumer's local repository, and an artifact served by Central or by a corporate
# mirror lands there too. So the configuration is not trusted to be right; the result is read
# back.
#
# Maven writes _remote.repositories beside each downloaded file, recording the id of the
# repository that served it. Anything other than chdb-verify means this run did not test what
# it claims to: most likely the `central` block above stopped being disabled, or a
# <mirrorOf>*</mirrorOf> in the caller's settings.xml intercepted the file repository. Both are
# reasons to stop rather than to report a pass.
PROVENANCE_LINES=0
while IFS= read -r record; do
  while IFS= read -r line; do
    case "$line" in
      \#*|"") continue ;;
    esac
    PROVENANCE_LINES=$((PROVENANCE_LINES + 1))
    case "$line" in
      *">chdb-verify="*) ;;
      *)
        die "an org.chdb artifact was served by a repository other than the throwaway one:
  ${record}
  ${line}
Only 'chdb-verify' proves the artifact under test is the one this run deployed. If a mirror in
your settings.xml claims '*', run this with -s pointing at a settings file that does not."
        ;;
    esac
  done < "$record"
done < <(find "${CONSUMER_M2}/org/chdb" -name '_remote.repositories' -type f)
[ "$PROVENANCE_LINES" -gt 0 ] \
  || die "no _remote.repositories entries under ${CONSUMER_M2}/org/chdb: nothing was actually
downloaded, so this run proved nothing about resolving from a repository."
ok "all ${PROVENANCE_LINES} downloaded org.chdb files came from the throwaway repository, not from Central"
# Located by its repository path rather than by a file name, and this matters more than it
# looks. A snapshot exists under two names at once: the remote repository holds
# chdb-jdbc-26.7.2-rc.2.1-20260909.081309-1.jar, and Maven's local repository ends up with
# *both* that file and a chdb-jdbc-26.7.2-rc.2.1-SNAPSHOT.jar beside it -- measured, not
# assumed. Which of the two `dependency:build-classpath` emits is a resolver detail that this
# check has no business depending on, and matching "chdb-jdbc-${VERSION}.jar" quietly did:
# it happens to be the base-version name today, and a version scheme or a Maven upgrade that
# changed that would not fail this assertion, it would skip the two error paths below and
# still print "all consumer checks passed".
#
# org/chdb/chdb-jdbc/ is the coordinate. It cannot drift.
JDBC_ONLY=""
for entry in "${CP_ENTRIES[@]}"; do
  case "$entry" in
    */org/chdb/chdb-jdbc/*.jar)
      [ -z "$JDBC_ONLY" ] || die "two chdb-jdbc jars on the consumer's classpath:
  ${JDBC_ONLY}
  ${entry}"
      JDBC_ONLY="$entry"
      ;;
  esac
done
[ -n "$JDBC_ONLY" ] \
  || die "chdb-jdbc is not on the consumer's classpath; the platform package did not pull it in.
Classpath was:
$(printf '%s' "$CP" | tr ':' '\n' | sed 's/^/  /')"
[ -s "$JDBC_ONLY" ] || die "the resolved chdb-jdbc jar is missing or empty: ${JDBC_ONLY}"
ok "chdb-jdbc arrived transitively from ${MODULE}, from the local repository"
printf '   as: %s\n' "$(basename "$JDBC_ONLY")"

# ---------------------------------------------------------------- 3. run a query

cat > "${CONSUMER}/src/Consumer.java" <<'EOF'
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * The smallest thing a consumer does: get a connection, ask the engine its version, run a
 * query. No Class.forName -- the driver has to be found through
 * META-INF/services/java.sql.Driver in a JAR that came from a repository.
 */
public final class Consumer {
    public static void main(String[] args) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:chdb::memory:")) {
            System.out.println("engine=" + c.getMetaData().getDatabaseProductVersion());
            try (Statement s = c.createStatement();
                    ResultSet rs = s.executeQuery(
                            "SELECT count(), sum(number) FROM numbers(1000)")) {
                rs.next();
                System.out.println("rows=" + rs.getLong(1) + " sum=" + rs.getLong(2));
            }
        }
    }
}
EOF

JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
"$JAVAC" -cp "$CP" -d "${CONSUMER}/classes" "${CONSUMER}/src/Consumer.java"

step "Running a query against the JAR from the local repository"

# No -Dchdb.library.path: the loader must find the platform package on the classpath, unpack
# it into a content-addressed cache and verify the checksums. That is the production path and
# the one the reactor tests never take, because they point at target/native instead.
set +e
OUT="$("$JAVA" -cp "${CP}:${CONSUMER}/classes" \
  -Dchdb.cache.dir="$CACHE" \
  Consumer 2>&1)"
STATUS=$?
set -e
printf '%s\n' "$OUT" | sed 's/^/   | /'
[ $STATUS -eq 0 ] || die "the consumer could not run a query"

printf '%s\n' "$OUT" | grep -q "engine=${ENGINE_VERSION}" \
  || die "the engine reported a version other than the pinned ${ENGINE_VERSION}"
printf '%s\n' "$OUT" | grep -q 'rows=1000 sum=499500' \
  || die "the query returned the wrong answer"
ok "query ran, engine reports the pinned ${ENGINE_VERSION}"

# The extraction half of question 5: the libraries a consumer actually loaded are the ones the
# cache holds, and they are byte-identical to what build-native.sh staged. If the JAR round
# trip or the unpack had mangled anything, the loader's own checksum gate would have failed
# first -- this asserts the gate compared against the right bytes rather than trivially.
UNPACKED_ENGINE="$(find "$CACHE" -name libchdb.so -type f | head -n1)"
UNPACKED_JNI="$(find "$CACHE" -name "libchdb_java_jni.${JNIEXT}" -type f | head -n1)"
[ -n "$UNPACKED_ENGINE" ] || die "nothing was unpacked into ${CACHE}: the libraries did not come from the JAR"
sha_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1; fi
}
for pair in "${UNPACKED_ENGINE}:libchdb.so" "${UNPACKED_JNI}:libchdb_java_jni.${JNIEXT}"; do
  unpacked="${pair%%:*}"; name="${pair##*:}"
  expected="$(awk -v n="$name" '$2 == n {print $1}' "${STAGED}/sha256sums.txt")"
  actual="$(sha_of "$unpacked")"
  [ "$expected" = "$actual" ] || die "${name} unpacked from the JAR differs from the staged file
  staged:   ${expected}
  unpacked: ${actual}"
done
ok "both libraries were unpacked from the JAR into $(dirname "$UNPACKED_ENGINE" | sed "s|${WORK}|\$WORK|") and match the staged checksums"

# ---------------------------------------------------------------- 4. the two error cases

step "Declaring no platform package"

# chdb-jdbc alone -- $JDBC_ONLY, found by coordinate above. This is the mistake a consumer
# makes when they copy a one-line dependency snippet, so the message has to name the coordinate
# for the machine they are on.
set +e
NONE_OUT="$("$JAVA" -cp "${JDBC_ONLY}:${CONSUMER}/classes" -Dchdb.cache.dir="$CACHE" Consumer 2>&1)"
NONE_STATUS=$?
set -e
[ $NONE_STATUS -ne 0 ] || die "a driver with no platform package ran a query, which it must not"
printf '%s\n' "$NONE_OUT" | grep -q "chdb-native-${PLATFORM}" \
  || die "the error does not name chdb-native-${PLATFORM}:
${NONE_OUT}"
printf '%s\n' "$NONE_OUT" | grep -q '<artifactId>' \
  || die "the error does not show the dependency to add"
ok "names chdb-native-${PLATFORM} and prints the dependency to add"
printf '%s\n' "$NONE_OUT" | grep -A12 'No chDB native runtime' | sed 's/^/   | /'

step "Declaring the wrong platform package"

# The platform package for the *other* architecture, simulated by overriding os.arch rather
# than by staging a second platform -- Platform.detect() reads that property, so the loader
# takes exactly the branch it would on a real ${OTHER_PLATFORM} machine with a
# chdb-native-${PLATFORM} JAR on its classpath.
set +e
WRONG_OUT="$("$JAVA" -cp "${CP}:${CONSUMER}/classes" \
  -Dos.arch="${OTHER_ARCH}" -Dchdb.cache.dir="$CACHE" Consumer 2>&1)"
WRONG_STATUS=$?
set -e
[ $WRONG_STATUS -ne 0 ] || die "the driver loaded a package for the wrong architecture"
printf '%s\n' "$WRONG_OUT" | grep -q "chdb-native-${OTHER_PLATFORM}" \
  || die "the error does not name the package this machine needs (chdb-native-${OTHER_PLATFORM}):
${WRONG_OUT}"
# The distinguishing half. Without it this message is identical to the "declared nothing"
# one, and a consumer looking at chdb-native-${PLATFORM} in their POM is told to add a
# dependency they can see is already there.
printf '%s\n' "$WRONG_OUT" | grep -q "The classpath does carry chdb-native-${PLATFORM}" \
  || die "the error does not mention that chdb-native-${PLATFORM} is on the classpath, so it
reads exactly like the no-package-at-all case:
${WRONG_OUT}"
ok "names chdb-native-${OTHER_PLATFORM}, and says which package is on the classpath instead"
printf '%s\n' "$WRONG_OUT" | grep -A6 'No chDB native runtime' | sed 's/^/   | /'

printf '\n\033[1mAll consumer checks passed for %s at %s.\033[0m\n' "$PLATFORM" "$VERSION"
printf 'Not covered here: whether a %s MB artifact survives a real upload and download.\n' \
  "$(( $(wc -c < "$NATIVE_JAR") / 1024 / 1024 ))"
printf 'Only Maven Central can answer that; see issue #10 and docs/release-readiness.md.\n'
