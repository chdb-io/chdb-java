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
# This is an acceptance tool for an upstream fix, so every way it could report success without
# having measured anything is a failure it has to refuse rather than round down to zero:
#
#   - a probe that exited non-zero measured nothing, whatever it printed on the way out;
#   - a `stress` kill that is not SIGSEGV/SIGBUS is some other crash, not the #14 signature;
#   - an observer or SIGSEGV-generating thread that never ran also reports zero, and zero is
#     the answer that means "fixed" -- so the probe waits for them and refuses a run in which
#     they produced nothing;
#   - a connector thread that dies takes only itself down, so its exception is collected and
#     failed on rather than showing up as a smaller `opens` count.
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
# Validated rather than trusted: an unchecked value reaches a loop bound, and a non-numeric
# one -- `inf`, `-1`, `1e3` -- turns `seq`/arithmetic into either an unbounded loop or zero
# iterations. Zero iterations is the dangerous one: it would print the closing "zero means
# fixed" note without having run the probe at all.
case "$RUNS" in
  ''|*[!0-9]*) die "runs must be a positive integer, got '${RUNS}'" ;;
esac
[ "$RUNS" -ge 1 ] || die "runs must be at least 1, got '${RUNS}'"
[ "$RUNS" -le 1000 ] || die "runs must be at most 1000, got '${RUNS}'"

# The probe's own tuning. Constants rather than arguments, because the numbers are chosen to
# make each mode work -- 200 connects is enough for the measurement to be stable, and the
# stress mode needs enough churn and enough SIGSEGV pressure to land inside a window that is
# microseconds wide. Kept here, named, so that changing them is a deliberate edit. The probe
# rejects a non-positive value for any of them.
MEASURE_CONNECT_THREADS=1
MEASURE_CONNECTS_PER_THREAD=200
MEASURE_SEGV_THREADS=0
STRESS_CONNECT_THREADS=8
STRESS_CONNECTS_PER_THREAD=250
STRESS_SEGV_THREADS=8

[ -d "${ROOT}/chdb-jdbc/target/classes" ] \
  || die "no compiled driver; run 'mvn -pl chdb-jdbc -am compile' first"

JAVA_BIN="${JAVA_HOME:+${JAVA_HOME}/bin/}java"
JAVAC_BIN="${JAVA_HOME:+${JAVA_HOME}/bin/}javac"

PROBE="${ROOT}/target/signal-window-probe"
mkdir -p "$PROBE"

cat > "${PROBE}/SignalWindowProbe.java" <<'JAVA'
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.chdb.internal.ChdbNative;

/**
 * measure: how often can another thread see the host's crash handlers missing?
 * stress:  can a thread taking one of those signals in that window kill the JVM?
 *
 * <p>Every exit code other than {@link #EXIT_OK} means the run measured nothing. That
 * distinction is the point: zero observations is also what a fixed engine looks like, so a
 * run that never got its observer scheduled must not be reportable as a zero.
 */
public class SignalWindowProbe {

    static final int EXIT_OK = 0;
    static final int EXIT_BAD_USAGE = 3;
    static final int EXIT_NOT_READY = 4;
    static final int EXIT_WORKER_FAILED = 5;
    static final int EXIT_NOTHING_OBSERVED = 6;

    static final AtomicBoolean stop = new AtomicBoolean();
    static final AtomicLong guardPageHits = new AtomicLong();
    static final AtomicLong opens = new AtomicLong();
    static final AtomicLong samples = new AtomicLong();
    static final AtomicLong sawDefault = new AtomicLong();
    static final AtomicLong sawEngine = new AtomicLong();
    static final List<String> failures = Collections.synchronizedList(new ArrayList<String>());

    // Each StackOverflowError is a SIGSEGV on the thread's stack guard page that HotSpot
    // catches and turns into an Error. With the host handler at SIG_DFL it is a kill instead.
    static int recurse(int depth) {
        return recurse(depth + 1) + 1;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.out.println("FAILURE usage: SignalWindowProbe <measure|stress>"
                    + " <connectThreads> <connectsPerThread> <segvThreads>");
            System.exit(EXIT_BAD_USAGE);
        }
        final String mode = args[0];
        if (!mode.equals("measure") && !mode.equals("stress")) {
            System.out.println("FAILURE unknown mode " + mode);
            System.exit(EXIT_BAD_USAGE);
        }
        final int connectThreads = positive("connectThreads", args[1]);
        final int perThread = positive("connectsPerThread", args[2]);
        final int segvThreads = mode.equals("stress")
                ? positive("segvThreads", args[3])
                : 0;

        // Warm up, so the engine is initialised and the dispositions are at steady state.
        try (Connection c = DriverManager.getConnection("jdbc:chdb::memory:")) {
            c.isValid(1);
        }

        final Map<String, String> steady = parse(ChdbNative.signalDispositions());

        // One count per thread that has to be doing its job before the connect churn starts.
        // Without this the churn can finish before the scheduler has run the observer or the
        // SIGSEGV threads even once, and the run reports zero -- indistinguishable from the
        // engine having been fixed.
        final CountDownLatch ready =
                new CountDownLatch(mode.equals("measure") ? 1 : segvThreads);

        if (mode.equals("measure")) {
            Thread observer = new Thread(() -> {
                try {
                    while (!stop.get()) {
                        samples.incrementAndGet();
                        classify(steady, parse(ChdbNative.signalDispositions()));
                        ready.countDown();
                    }
                } catch (Throwable t) {
                    failures.add("observer: " + t);
                    // Released so main reports the failure rather than timing out on it.
                    ready.countDown();
                }
            }, "signal-disposition-observer");
            observer.setDaemon(true);
            observer.start();
        } else {
            for (int i = 0; i < segvThreads; i++) {
                Thread t = new Thread(null, () -> {
                    try {
                        while (!stop.get()) {
                            try {
                                recurse(0);
                            } catch (StackOverflowError expected) {
                                guardPageHits.incrementAndGet();
                                // Counted down only after a real guard-page hit, so being
                                // ready means the SIGSEGVs are actually being generated.
                                ready.countDown();
                            }
                        }
                    } catch (Throwable t2) {
                        failures.add("segv thread: " + t2);
                        ready.countDown();
                    }
                }, "segv-" + i, 256 * 1024);
                t.setDaemon(true);
                t.start();
            }
        }

        if (!ready.await(60, TimeUnit.SECONDS)) {
            System.out.println("FAILURE the observer / SIGSEGV threads did not start within"
                    + " 60s, so this run would have measured nothing. samples="
                    + samples.get() + " guardPageHits=" + guardPageHits.get());
            System.exit(EXIT_NOT_READY);
        }

        long started = System.nanoTime();
        List<Thread> connectors = new ArrayList<>();
        for (int i = 0; i < connectThreads; i++) {
            Thread t = new Thread(() -> {
                for (int j = 0; j < perThread; j++) {
                    try (Connection c = DriverManager.getConnection("jdbc:chdb::memory:")) {
                        opens.incrementAndGet();
                    } catch (Throwable e) {
                        // Collected, not thrown: an exception here would kill only this
                        // thread, join() would still return, and the run would look like a
                        // success with a smaller opens count.
                        failures.add(Thread.currentThread().getName() + ": " + e);
                        return;
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

        if (!failures.isEmpty()) {
            synchronized (failures) {
                for (String failure : failures) {
                    System.out.println("FAILURE " + failure);
                }
            }
            System.exit(EXIT_WORKER_FAILED);
        }

        if (mode.equals("measure")) {
            if (samples.get() == 0) {
                System.out.println("FAILURE the observer took no samples, so 'no window'"
                        + " here would mean 'nothing looked', not 'nothing to see'");
                System.exit(EXIT_NOTHING_OBSERVED);
            }
            System.out.println("RESULT opens=" + opens.get() + " ms=" + ms
                    + " samples=" + samples.get()
                    + " hostHandlersAtSigDfl=" + sawDefault.get()
                    + " engineHandlerInstalled=" + sawEngine.get());
        } else {
            if (guardPageHits.get() == 0) {
                System.out.println("FAILURE no guard-page SIGSEGV was generated, so surviving"
                        + " this run proves nothing about the window");
                System.exit(EXIT_NOTHING_OBSERVED);
            }
            System.out.println("RESULT survived opens=" + opens.get() + " ms=" + ms
                    + " guardPageHits=" + guardPageHits.get());
        }
        System.exit(EXIT_OK);
    }

    static int positive(String name, String raw) {
        int value;
        try {
            value = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            System.out.println("FAILURE " + name + " must be a positive integer, got " + raw);
            System.exit(EXIT_BAD_USAGE);
            return 0;
        }
        if (value < 1) {
            System.out.println("FAILURE " + name + " must be at least 1, got " + value);
            System.exit(EXIT_BAD_USAGE);
        }
        return value;
    }

    static void classify(Map<String, String> steady, Map<String, String> now) {
        for (Map.Entry<String, String> e : now.entrySet()) {
            // By handler, not by the whole line: sigaction() is not an atomic read against a
            // concurrent write, so an observer occasionally catches the right handler with
            // the flags not yet updated. Which handler runs is what decides whether the JVM
            // survives.
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
"$JAVAC_BIN" --release 11 \
  -cp "${ROOT}/chdb-jdbc/target/classes" -d "$PROBE" "${PROBE}/SignalWindowProbe.java"

# Set for its absence: a kill by SIG_DFL writes no crash report, which is the part of #14 that
# made the crash undiagnosable. Written into a per-run directory so that one run's report
# cannot be attributed to another -- with a single shared path, one report would make every
# kill in the batch look like "some other crash".
PROBE_EXIT=0
PROBE_OUT=""
run_probe() {
  local run_dir="$1"
  shift
  rm -rf "$run_dir"
  mkdir -p "$run_dir"
  set +e
  PROBE_OUT="$("$JAVA_BIN" -Xmx512m -XX:MaxJavaStackTraceDepth=1 \
    -XX:ErrorFile="${run_dir}/hs_err_pid%p.log" \
    -Dchdb.library.path="$RUNTIME" \
    -cp "${ROOT}/chdb-jdbc/target/classes:${PROBE}" \
    SignalWindowProbe "$@" 2>&1)"
  PROBE_EXIT=$?
  set -e
}

# Numeric field from the probe's RESULT line, or empty if it is not there.
field() {
  local name="$1" text="$2" value
  value="$(printf '%s\n' "$text" | grep -o "${name}=[0-9][0-9]*" | tail -1 | cut -d= -f2)" \
    || value=""
  printf '%s' "$value"
}

# Crash reports this run produced.
reports_in() {
  find "$1" -maxdepth 1 -type f -name 'hs_err_pid*.log' 2>/dev/null | wc -l | tr -d '[:space:]'
}

printf 'run-signal-window-test: runtime %s\n' "$RUNTIME"
printf 'run-signal-window-test: mode %s, %s run(s)\n' "$MODE" "$RUNS"

if [ "$MODE" = measure ]; then
  for ((i = 1; i <= RUNS; i++)); do
    run_dir="${PROBE}/run-${i}"
    run_probe "$run_dir" measure \
      "$MEASURE_CONNECT_THREADS" "$MEASURE_CONNECTS_PER_THREAD" "$MEASURE_SEGV_THREADS"

    # Checked before the numbers are read at all: a probe that exited non-zero measured
    # nothing, and its output must not be mined for a reassuring zero.
    if [ "$PROBE_EXIT" -ne 0 ]; then
      printf '%s\n' "$PROBE_OUT" >&2
      die "run ${i}: the probe exited ${PROBE_EXIT} instead of 0, so it measured nothing.
     No conclusion about the window can be drawn from this run."
    fi

    samples="$(field samples "$PROBE_OUT")"
    at_sig_dfl="$(field hostHandlersAtSigDfl "$PROBE_OUT")"
    engine_installed="$(field engineHandlerInstalled "$PROBE_OUT")"
    if [ -z "$samples" ] || [ -z "$at_sig_dfl" ] || [ -z "$engine_installed" ]; then
      printf '%s\n' "$PROBE_OUT" >&2
      die "run ${i}: the probe exited 0 but printed no usable RESULT line."
    fi
    if [ "$samples" -eq 0 ]; then
      die "run ${i}: the observer took no samples. Zero is also what a fixed engine looks
     like, so a run that never looked cannot be reported as one that saw nothing."
    fi

    printf 'run %s: samples=%s hostHandlersAtSigDfl=%s engineHandlerInstalled=%s\n' \
      "$i" "$samples" "$at_sig_dfl" "$engine_installed"

    if [ "$engine_installed" -gt 0 ]; then
      die "run ${i}: the engine's own signal handler was seen installed during a connect. It
     treats a SIGSEGV HotSpot would have recovered from as a fatal crash, so the opt-out in
     NativeLibraryLoader.load() must be restored."
    fi
  done
  printf '\nhostHandlersAtSigDfl is the residual upstream window: it opens inside\n'
  printf 'chdb_connect(), before the shim gets control back. Zero across every run -- with a\n'
  printf 'non-zero sample count, which is what makes the zero mean anything -- is how you\n'
  printf 'tell that chdb-core has stopped resetting the host handlers and #14 is closed.\n'
else
  killed_signature=0
  killed_with_report=0
  survived=0
  for ((i = 1; i <= RUNS; i++)); do
    run_dir="${PROBE}/run-${i}"
    run_probe "$run_dir" stress \
      "$STRESS_CONNECT_THREADS" "$STRESS_CONNECTS_PER_THREAD" "$STRESS_SEGV_THREADS"
    reports="$(reports_in "$run_dir")"

    case "$PROBE_EXIT" in
      0)
        hits="$(field guardPageHits "$PROBE_OUT")"
        if [ -z "$hits" ] || [ "$hits" -eq 0 ]; then
          printf '%s\n' "$PROBE_OUT" >&2
          die "run ${i}: survived, but no guard-page SIGSEGV was generated, so it survived
     nothing. This is not evidence that the window is closed."
        fi
        survived=$((survived + 1))
        printf 'run %s: survived -- guardPageHits=%s\n' "$i" "$hits"
        ;;
      138|139)
        # 139 is SIGSEGV, 138 is SIGBUS -- a stack-guard hit arrives as one or the other
        # depending on the platform. These are the only two codes that are the #14 kill.
        # Classified per run, immediately, because a report from any single run must not be
        # allowed to reinterpret the other runs' kills.
        if [ "$reports" -gt 0 ]; then
          killed_with_report=$((killed_with_report + 1))
          printf 'run %s: killed exit=%s BUT wrote %s crash report(s) in %s --\n' \
            "$i" "$PROBE_EXIT" "$reports" "$run_dir"
          printf '        HotSpot still had its handler, so this kill is not the #14 signature\n'
        else
          killed_signature=$((killed_signature + 1))
          printf 'run %s: killed exit=%s, no crash report -- the #14 signature\n' \
            "$i" "$PROBE_EXIT"
        fi
        ;;
      3|4|5|6)
        # The probe's own refusals: bad usage, threads that never got going, a worker that
        # died, nothing observed. It says which on its FAILURE line.
        printf '%s\n' "$PROBE_OUT" >&2
        die "run ${i}: the probe refused the run with exit ${PROBE_EXIT} -- see the FAILURE
     line above. It measured nothing, so this run is not evidence either way."
        ;;
      *)
        printf '%s\n' "$PROBE_OUT" >&2
        die "run ${i}: the probe exited ${PROBE_EXIT}, which is neither a clean run nor a
     SIGSEGV/SIGBUS kill. That is some other failure -- a native library that would not
     load looks like this -- and reporting it as the #14 signature would be wrong."
        ;;
    esac
  done

  printf '\nover %s run(s): %s killed with the #14 signature, %s killed with a crash report,'\
' %s survived\n' "$RUNS" "$killed_signature" "$killed_with_report" "$survived"
  if [ "$killed_with_report" -gt 0 ]; then
    die "${killed_with_report} run(s) died with a crash report, so HotSpot still owned its
     handler and something other than the #14 window killed them. Read the reports before
     drawing any conclusion about the window."
  fi
  if [ "$killed_signature" -gt 0 ]; then
    printf 'The kills wrote no crash report, which is the #14 signature: HotSpot had no\n'
    printf 'handler left to report from.\n'
  else
    printf 'Nothing was killed, and guard-page SIGSEGVs were being generated throughout, so\n'
    printf 'this engine looks like it has closed the window. Confirm with the measure mode.\n'
  fi
fi
