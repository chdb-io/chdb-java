#!/usr/bin/env bash
#
# Checks the release gate that a query wanting more memory than it may have returns a
# catchable exception rather than getting the process OOM-killed (work plan section 5.11).
#
# Has to run in a container: the gate is about behaviour under a cgroup memory limit, and there
# is no way to impose one on the host. Runs on Linux only for the same reason.
#
# Usage: scripts/run-memory-limit-test.sh <platform-id> [runtime-dir]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
die() { printf 'run-memory-limit-test: %s\n' "$*" >&2; exit 1; }

PLATFORM="${1:-}"
[ -n "$PLATFORM" ] || die "usage: $0 <platform-id> [runtime-dir]"
case "$PLATFORM" in
  linux-*) ;;
  *) die "only Linux: the gate is about cgroup limits" ;;
esac
case "$PLATFORM" in *aarch64*) ARCH=aarch64 ;; *) ARCH=x86_64 ;; esac

RUNTIME="${2:-${ROOT}/chdb-native-${PLATFORM}/target/native/META-INF/chdb/native/linux/${ARCH}}"
[ -f "${RUNTIME}/libchdb.so" ] || die "no engine in ${RUNTIME}; build the platform package first"

PROBE="${ROOT}/target/memory-probe"
mkdir -p "$PROBE"
cat > "${PROBE}/MemoryLimitProbe.java" <<'JAVA'
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Asks for far more memory than the container has, and reports what came back.
 *
 * groupArray is used on purpose: it has to hold every value at once, so unlike a GROUP BY it
 * cannot spill to disk and get away with it.
 */
public class MemoryLimitProbe {
    public static void main(String[] args) {
        String settings = args.length > 0 ? args[0] : "";
        String url = "jdbc:chdb::memory:" + (settings.isEmpty() ? "" : "?" + settings);
        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT length(groupArray(number)) FROM numbers(200000000)")) {
            rs.next();
            System.out.println("VERDICT=completed rows=" + rs.getLong(1));
        } catch (SQLException e) {
            System.out.println("VERDICT=exception type=" + e.getClass().getSimpleName()
                    + " code=" + e.getErrorCode() + " state=" + e.getSQLState());
        }
    }
}
JAVA

printf 'run-memory-limit-test: building the probe\n'
"${JAVA_HOME:+${JAVA_HOME}/bin/}javac" --release 11 \
  -cp "${ROOT}/chdb-jdbc/target/classes" -d "$PROBE" "${PROBE}/MemoryLimitProbe.java"

failures=0
probe() {
  local label="$1" settings="$2"
  printf '\n=== %s ===\n' "$label"
  # 2 GB is small enough that a 200-million-element groupArray cannot fit, and large enough
  # that the engine and JVM start comfortably.
  local out
  out="$(docker run --rm --memory=2g --memory-swap=2g \
      -v "${ROOT}:${ROOT}" -v "${RUNTIME}:${RUNTIME}" -w "${ROOT}" \
      almalinux:8 bash -c '
        dnf -q -y install java-17-openjdk-headless >/dev/null 2>&1
        java -Xmx256m -Dchdb.library.path='"${RUNTIME}"' \
          -cp '"${ROOT}"'/chdb-jdbc/target/classes:'"${PROBE}"' \
          MemoryLimitProbe "'"$settings"'"
        echo "JVM_EXIT=$?"
      ' 2>&1)" || true
  printf '%s\n' "$out" | grep -E 'VERDICT|JVM_EXIT|Low memory' | sed 's/^/  /'

  if printf '%s' "$out" | grep -q 'VERDICT=exception'; then
    printf '  -> a catchable exception, which is the gate\n'
  elif printf '%s' "$out" | grep -q 'VERDICT=completed'; then
    printf '  -> completed; the query was not heavy enough to test anything\n'
    failures=$((failures + 1))
  else
    printf '  -> NO VERDICT: the process died rather than reporting. This is the OOM kill the\n'
    printf '     gate exists to prevent.\n'
    failures=$((failures + 1))
  fi
}

# With an explicit cap, which is what the documentation tells people to set.
probe "max_memory_usage=200MB, cgroup 2GB" "max_memory_usage=200000000"
# And without one: the engine reads the cgroup limit itself and applies its own tracker, so
# this must also come back as an exception rather than an OOM kill.
probe "no max_memory_usage, cgroup 2GB" ""

printf '\n'
[ "$failures" -eq 0 ] || die "$failures of 2 cases did not produce a catchable exception"
printf 'run-memory-limit-test: both cases returned a catchable exception\n'
