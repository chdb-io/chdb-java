#!/usr/bin/env bash
#
# Measures the window in which the host JVM has no crash handlers, and demonstrates that it
# is lethal. Issue #14.
#
# chdb_set_signal_handlers_enabled(0) sets a process-wide flag, and that flag arms the two
# `if (disable_signal_handlers) chdb_reset_signal_handlers()` branches inside chdb_connect()
# (chdb-core programs/local/chdb.cpp lines 236 and 549). Each reset drops SIGABRT, SIGSEGV,
# SIGILL, SIGBUS, SIGSYS, SIGFPE, SIGTSTP and SIGTRAP to SIG_DFL for the whole process. The
# shim's SignalGuard puts them back, but dispositions are process-wide, so the restore cannot
# be atomic against the other threads in the JVM: for the few microseconds in between, a
# thread taking a SIGSEGV HotSpot would have recovered from is killed by the kernel instead --
# and writes no hs_err report, because HotSpot's crash reporter *is* the handler that was
# removed. That is why #14 has an exit code and no stack.
#
# Two modes:
#
#   measure  An observer thread samples the dispositions while another thread churns
#            connections, and reports how often the host handlers were seen at SIG_DFL.
#            Non-destructive. This is the number to watch when validating an upstream fix:
#            it should reach zero.
#
#   stress   Connect churn alongside threads that generate the SIGSEGVs HotSpot handles for
#            itself (a stack-guard hit per StackOverflowError). Kills the JVM. Not a
#            regression test -- the window is upstream's and this still dies with the fix in
#            place -- but it is the fastest way to confirm whether an engine build has closed
#            it: a fixed engine survives.
#
# SignalHandlerIT.concurrentConnectsNeverExposeAChdbHandler is the committed, non-destructive
# version of `measure`. This script exists because the stress mode cannot live in a test that
# is supposed to pass, and because reproducing #14 wants far more churn than an IT should run.
#
# Usage: scripts/run-signal-window-test.sh <measure|stress> [runtime-dir] [runs]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
die() { printf 'run-signal-window-test: %s\n' "$*" >&2; exit 1; }

MODE="${1:-}"
case "$MODE" in
  measure|stress) ;;
  *) die "usage: $0 <measure|stress> [runtime-dir] [runs]" ;;
esac

case "$(uname -s)-$(uname -m)" in
  Darwin-arm64) DEFAULT_PLATFORM=macos-aarch64; DEFAULT_DIR="macos/aarch64" ;;
  Darwin-x86_64) DEFAULT_PLATFORM=macos-x86_64; DEFAULT_DIR="macos/x86_64" ;;
  Linux-aarch64) DEFAULT_PLATFORM=linux-aarch64-gnu; DEFAULT_DIR="linux/aarch64" ;;
  Linux-x86_64) DEFAULT_PLATFORM=linux-x86_64-gnu; DEFAULT_DIR="linux/x86_64" ;;
  *) die "unsupported host $(uname -s)-$(uname -m)" ;;
esac

RUNTIME="${2:-${ROOT}/chdb-native-${DEFAULT_PLATFORM}/target/native/META-INF/chdb/native/${DEFAULT_DIR}}"
[ -d "$RUNTIME" ] || die "no runtime directory at ${RUNTIME}; build the platform package first"
RUNS="${3:-5}"

[ -d "${ROOT}/chdb-jdbc/target/classes" ] \
  || die "no compiled driver; run 'mvn -pl chdb-jdbc -am compile' first"

PROBE="${ROOT}/target/signal-window-probe"
mkdir -p "$PROBE"

cat > "${PROBE}/SignalWindowProbe.java" <<'JAVA'
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.chdb.internal.ChdbNative;

/**
 * measure: how often can another thread see the host's crash handlers missing?
 * stress:  can a thread taking one of those signals in that window kill the JVM?
 */
public class SignalWindowProbe {

    static final AtomicBoolean stop = new AtomicBoolean();
    static final AtomicLong guardPageHits = new AtomicLong();
    static final AtomicLong opens = new AtomicLong();

    // Each StackOverflowError is a SIGSEGV on the thread's stack guard page that HotSpot
    // catches and turns into an Error. With the host handler at SIG_DFL it is a kill instead.
    static int recurse(int depth) {
        return recurse(depth + 1) + 1;
    }

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        int connectThreads = Integer.parseInt(args[1]);
        int perThread = Integer.parseInt(args[2]);
        int segvThreads = Integer.parseInt(args[3]);

        // Warm up, so the engine is initialised and the dispositions are at steady state.
        try (Connection c = DriverManager.getConnection("jdbc:chdb::memory:")) {
            c.isValid(1);
        }

        final Map<String, String> steady = parse(ChdbNative.signalDispositions());
        final AtomicLong samples = new AtomicLong();
        final AtomicLong sawDefault = new AtomicLong();
        final AtomicLong sawEngine = new AtomicLong();

        if (mode.equals("measure")) {
            Thread observer = new Thread(() -> {
                while (!stop.get()) {
                    samples.incrementAndGet();
                    for (Map.Entry<String, String> e : parse(ChdbNative.signalDispositions()).entrySet()) {
                        // By handler, not by the whole line: sigaction() is not an atomic read
                        // against a concurrent write, so an observer occasionally catches the
                        // right handler with the flags not yet updated. Which handler runs is
                        // what decides whether the JVM survives.
                        String expected = handler(steady.get(e.getKey()));
                        String actual = handler(e.getValue());
                        if (actual.equals(expected)) {
                            continue;
                        }
                        if (actual.equals("SIG_DFL") || actual.equals("SIG_IGN")) {
                            sawDefault.incrementAndGet();
                        } else {
                            sawEngine.incrementAndGet();
                        }
                    }
                }
            });
            observer.setDaemon(true);
            observer.start();
        } else {
            for (int i = 0; i < segvThreads; i++) {
                Thread t = new Thread(null, () -> {
                    while (!stop.get()) {
                        try {
                            recurse(0);
                        } catch (StackOverflowError expected) {
                            guardPageHits.incrementAndGet();
                        }
                    }
                }, "segv-" + i, 256 * 1024);
                t.setDaemon(true);
                t.start();
            }
        }

        long started = System.nanoTime();
        List<Thread> connectors = new ArrayList<>();
        for (int i = 0; i < connectThreads; i++) {
            Thread t = new Thread(() -> {
                for (int j = 0; j < perThread; j++) {
                    try (Connection c = DriverManager.getConnection("jdbc:chdb::memory:")) {
                        opens.incrementAndGet();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            }, "connect-" + i);
            connectors.add(t);
            t.start();
        }
        for (Thread t : connectors) {
            t.join();
        }
        stop.set(true);
        long ms = (System.nanoTime() - started) / 1_000_000;

        if (mode.equals("measure")) {
            System.out.println("RESULT opens=" + opens.get() + " ms=" + ms
                    + " samples=" + samples.get()
                    + " hostHandlersAtSigDfl=" + sawDefault.get()
                    + " engineHandlerInstalled=" + sawEngine.get());
        } else {
            System.out.println("RESULT survived opens=" + opens.get() + " ms=" + ms
                    + " guardPageHits=" + guardPageHits.get());
        }
    }

    /** The handler part of a disposition line: "SIG_DFL" or "handler:0x1088ff9bc". */
    static String handler(String disposition) {
        if (disposition == null) {
            return "";
        }
        int space = disposition.indexOf(' ');
        return space < 0 ? disposition : disposition.substring(0, space);
    }

    static Map<String, String> parse(String report) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : report.split("\n")) {
            int equals = line.indexOf('=');
            if (equals > 0) {
                out.put(line.substring(0, equals), line.substring(equals + 1));
            }
        }
        return out;
    }
}
JAVA

printf 'run-signal-window-test: building the probe\n'
"${JAVA_HOME:+${JAVA_HOME}/bin/}javac" --release 11 \
  -cp "${ROOT}/chdb-jdbc/target/classes" -d "$PROBE" "${PROBE}/SignalWindowProbe.java"

# ErrorFile is set so that its absence is evidence rather than an oversight: a kill by SIG_DFL
# writes no crash report, which is the part of #14 that made the crash undiagnosable.
run_probe() {
  set +e
  "${JAVA_HOME:+${JAVA_HOME}/bin/}java" -Xmx512m -XX:MaxJavaStackTraceDepth=1 \
    -XX:ErrorFile="${PROBE}/hs_err_pid%p.log" \
    -Dchdb.library.path="$RUNTIME" \
    -cp "${ROOT}/chdb-jdbc/target/classes:${PROBE}" \
    SignalWindowProbe "$@" 2>&1
  printf 'JVM_EXIT=%s\n' "$?"
  set -e
}

printf 'run-signal-window-test: runtime %s\n' "$RUNTIME"
rm -f "${PROBE}"/hs_err_pid*.log

if [ "$MODE" = measure ]; then
  for i in $(seq 1 "$RUNS"); do
    out="$(run_probe measure 1 200 0)"
    printf 'run %s: %s\n' "$i" "$(printf '%s\n' "$out" | grep -E 'RESULT|JVM_EXIT' | tr '\n' ' ')"
    if printf '%s' "$out" | grep -q 'engineHandlerInstalled=[1-9]'; then
      die "the engine's own signal handler was seen installed during a connect. It treats a
     SIGSEGV HotSpot would have recovered from as a fatal crash, so the opt-out in
     NativeLibraryLoader.load() must be restored."
    fi
  done
  printf '\nhostHandlersAtSigDfl is the residual upstream window: it opens inside\n'
  printf 'chdb_connect(), before the shim gets control back. Zero means chdb-core has stopped\n'
  printf 'resetting the host handlers on the connect path and #14 is closed.\n'
else
  crashed=0
  for i in $(seq 1 "$RUNS"); do
    out="$(run_probe stress 8 250 8)"
    code="$(printf '%s\n' "$out" | grep -o 'JVM_EXIT=[0-9]*' | tail -1 | cut -d= -f2)"
    if [ "$code" = 0 ]; then
      printf 'run %s: survived -- %s\n' "$i" "$(printf '%s\n' "$out" | grep RESULT)"
    else
      crashed=$((crashed + 1))
      # 139 on Linux (SIGSEGV), 138 on macOS, where a stack-guard hit arrives as SIGBUS.
      printf 'run %s: KILLED exit=%s\n' "$i" "$code"
    fi
  done
  printf '\nkilled %s of %s runs\n' "$crashed" "$RUNS"
  if [ -n "$(ls -A "${PROBE}"/hs_err_pid*.log 2>/dev/null)" ]; then
    printf 'and hs_err reports were written, so those kills were not the #14 mechanism:\n'
    ls "${PROBE}"/hs_err_pid*.log
  elif [ "$crashed" -gt 0 ]; then
    printf 'and no hs_err report was written for any of them, which is the #14 signature:\n'
    printf 'the kill happened while HotSpot had no handler to report from.\n'
  fi
fi
