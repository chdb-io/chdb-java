#!/usr/bin/env bash
#
# Runs the shim's code under sanitizers (work plan section 5.11).
#
# Usage:
#   scripts/run-sanitizer-tests.sh <platform-id> [sanitizers]     default: address,undefined
#
# Two passes, because one tool cannot cover both halves:
#
#   chdb_jni_test        A JVM-free, engine-free harness over the shim's standalone logic:
#                        Arrow format parsing, the handle registry, the signal guard. Runs
#                        under whatever sanitizers are asked for, including ASan.
#
#   the JDBC suite       The real thing, in a real JVM against the real engine. Runs under
#                        UBSan only.
#
# Why ASan cannot cover the JDBC suite
# ------------------------------------
# ASan replaces malloc process-wide, and a preloaded ASan in a process that loads the released
# libchdb crashes: inside libchdb with the JIT off, inside HotSpot's C2 with it on. The engine
# is a release build with its own allocator and is not ASan-clean, and neither is the JVM.
# Covering them needs a sanitizer build of chdb-core, which upstream does not publish -- which
# is why the work plan asks for "a full chDB sanitizer build" rather than just an ASan run.
#
# UBSan has no such problem: it instruments arithmetic and casts rather than intercepting
# allocation, so it coexists with both, and the whole suite runs clean under it.
#
# Three things a JVM under any sanitizer needs
# --------------------------------------------
#   1. The runtime must be loaded before the shim. Arriving by dlopen is too late for ASan's
#      interceptors, and it says so and aborts. Hence LD_PRELOAD / DYLD_INSERT_LIBRARIES.
#
#   2. The sanitizer must not handle SIGSEGV, SIGBUS, SIGILL or SIGFPE. HotSpot needs them for
#      implicit null checks and stack banging, so a sanitizer that takes them reports the JVM's
#      ordinary recovery as a fatal DEADLYSIGNAL -- the same conflict chDB's own signal handlers
#      cause, from a different direction.
#
#   3. The test JVM has to be exec'd by this script rather than forked by Maven. On macOS dyld
#      drops DYLD_* from an environment handed to a hardened binary, which is what a surefire
#      fork is: LD_PRELOAD and ASAN_OPTIONS arrive, DYLD_INSERT_LIBRARIES does not. Exec'ing
#      java from a shell works, so the run goes through the JUnit Platform console launcher.
#
# Leak detection is Linux-only: LSan does not support Darwin, so detect_leaks is off there.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

die() { printf 'run-sanitizer-tests: %s\n' "$*" >&2; exit 1; }

PLATFORM="${1:-}"
SANITIZERS="${2:-address,undefined}"
[ -n "$PLATFORM" ] || die "usage: $0 <platform-id> [sanitizers]"

case "$PLATFORM" in
  macos-aarch64)     OS=macos; ARCH=aarch64; LIBEXT=dylib ;;
  macos-x86_64)      OS=macos; ARCH=x86_64;  LIBEXT=dylib ;;
  linux-x86_64-gnu)  OS=linux; ARCH=x86_64;  LIBEXT=so ;;
  linux-aarch64-gnu) OS=linux; ARCH=aarch64; LIBEXT=so ;;
  *) die "unknown platform '${PLATFORM}'" ;;
esac

ENGINE_VERSION="$(sed -n 's/^engine.version=//p' "${SCRIPT_DIR}/engine.properties" | head -n1)"
ENGINE_DIR="${ROOT}/target/engine/${PLATFORM}"
HEADER_DIR="${ROOT}/chdb-jdbc/target/native-headers"

[ -f "${ENGINE_DIR}/libchdb.so" ] \
  || die "no engine at ${ENGINE_DIR}. Run scripts/fetch-libchdb.sh ${PLATFORM} first."
[ -f "${HEADER_DIR}/org_chdb_internal_ChdbNative.h" ] \
  || die "no generated JNI header. Run 'mvn -pl chdb-jdbc compile' first."

# ---------------------------------------------------------------- build the instrumented shim

BUILD_DIR="${ROOT}/target/jni-sanitize-${PLATFORM}"
printf 'run-sanitizer-tests: building the shim and the harness with -fsanitize=%s\n' "$SANITIZERS"
cmake -S "${ROOT}/chdb-jni" -B "$BUILD_DIR" \
  -DCMAKE_BUILD_TYPE=Debug \
  -DCHDB_ENGINE_DIR="$ENGINE_DIR" \
  -DCHDB_ENGINE_VERSION="$ENGINE_VERSION" \
  -DCHDB_JNI_HEADER_DIR="$HEADER_DIR" \
  -DCHDB_JNI_BUILD_TESTS=ON \
  -DCHDB_JNI_SANITIZE="$SANITIZERS"
cmake --build "$BUILD_DIR" --parallel

RUNTIME="${ROOT}/target/runtime-sanitize-${PLATFORM}"
mkdir -p "$RUNTIME"
cp -f "${ENGINE_DIR}/libchdb.so" "${RUNTIME}/libchdb.so"
cp -f "${BUILD_DIR}/libchdb_java_jni.${LIBEXT}" "${RUNTIME}/libchdb_java_jni.${LIBEXT}"

# ---------------------------------------------------------------- locate the sanitizer runtime

PRELOAD=""
if printf '%s' "$SANITIZERS" | grep -q address; then
  if [ "$OS" = macos ]; then
    # The shim records @rpath/libclang_rt.asan_osx_dynamic.dylib; the absolute path is what
    # DYLD_INSERT_LIBRARIES needs.
    for candidate in \
        /Library/Developer/CommandLineTools/usr/lib/clang/*/lib/darwin/libclang_rt.asan_osx_dynamic.dylib \
        /Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/lib/clang/*/lib/darwin/libclang_rt.asan_osx_dynamic.dylib; do
      if [ -f "$candidate" ]; then
        PRELOAD="$candidate"
        break
      fi
    done
    [ -n "$PRELOAD" ] || die "could not find libclang_rt.asan_osx_dynamic.dylib in the toolchain"
  else
    # Whichever compiler built the shim owns the matching runtime; asking it avoids guessing
    # between a gcc libasan and a clang libclang_rt.
    PRELOAD="$(cc -print-file-name=libasan.so 2>/dev/null || true)"
    if [ ! -f "$PRELOAD" ]; then
      PRELOAD="$(cc -print-file-name=libclang_rt.asan-${ARCH}.so 2>/dev/null || true)"
    fi
    [ -f "$PRELOAD" ] || die "could not find an ASan runtime; install libasan or clang's compiler-rt"
  fi
  printf 'run-sanitizer-tests: preloading %s\n' "$PRELOAD"
fi

# handle_* off so the sanitizer leaves HotSpot's crash-recovery signals alone. detect_leaks is
# Linux-only, and reports from uninstrumented libchdb and the JVM are suppressed by
# scripts/lsan-suppressions.txt rather than by turning leak detection off entirely.
ASAN_OPTS="handle_segv=0:handle_sigbus=0:handle_sigfpe=0:handle_sigill=0:abort_on_error=1"
if [ "$OS" = linux ]; then
  ASAN_OPTS="${ASAN_OPTS}:detect_leaks=1:suppressions=${SCRIPT_DIR}/lsan-suppressions.txt"
else
  # LSan has no Darwin support.
  ASAN_OPTS="${ASAN_OPTS}:detect_leaks=0"
fi

# halt_on_error, plus -fno-sanitize-recover=undefined in CMake: undefined behaviour has to fail
# the run rather than print and continue, or CI stays green while reporting the defect.
UBSAN_OPTS="print_stacktrace=1:halt_on_error=1:handle_segv=0:handle_sigbus=0:handle_sigfpe=0:handle_sigill=0"

# ---------------------------------------------------------------- pass 1: native harness

printf '\n=== chdb_jni_test (no JVM, no engine) under %s ===\n' "$SANITIZERS"
ASAN_OPTIONS="abort_on_error=1$([ "$OS" = linux ] && printf ':detect_leaks=1' || printf ':detect_leaks=0')" \
UBSAN_OPTIONS="print_stacktrace=1:halt_on_error=1" \
  "${BUILD_DIR}/chdb_jni_test"

if printf '%s' "$SANITIZERS" | grep -q address; then
  cat <<'EOF'

=== JDBC suite: skipped under AddressSanitizer ===
ASan replaces malloc for the whole process, and the released libchdb is not ASan-clean: a
preloaded ASan crashes inside libchdb with the JIT disabled and inside HotSpot's C2 with it
enabled. Running the suite would report the engine's allocator, not this driver.

Run this script with "undefined" to cover the JDBC suite under UBSan, which does not intercept
allocation and does run clean. Covering the engine needs a sanitizer build of chdb-core.
EOF
  exit 0
fi

# ---------------------------------------------------------------- test classpath

# Compiled and resolved by Maven; run by this script. Maven is the wrong thing to run the tests
# with here for the reason in note 3 above.
cd "$ROOT"
CP_FILE="${ROOT}/target/sanitizer-test-classpath.txt"
printf 'run-sanitizer-tests: compiling the tests and resolving their classpath\n'
# One invocation, not two: chdb-jdbc is a reactor sibling rather than an installed artifact, so
# build-classpath can only resolve it inside a reactor that has just built it. Each module in
# turn writes the file, and chdb-integration-tests is processed last as the dependent.
mvn --batch-mode --no-transfer-progress -pl chdb-integration-tests -am \
  test-compile dependency:build-classpath \
  -Dmdep.outputFile="$CP_FILE" -Dmdep.includeScope=test

CP="${ROOT}/chdb-jdbc/target/classes"
CP="${CP}:${ROOT}/chdb-integration-tests/target/classes"
CP="${CP}:${ROOT}/chdb-integration-tests/target/test-classes"
CP="${CP}:$(cat "$CP_FILE")"

# ---------------------------------------------------------------- run

printf '\n=== JDBC integration suite under %s ===\n\n' "$SANITIZERS"

if [ -n "$PRELOAD" ]; then
  if [ "$OS" = macos ]; then
    export DYLD_INSERT_LIBRARIES="$PRELOAD"
  else
    export LD_PRELOAD="$PRELOAD"
  fi
fi
export ASAN_OPTIONS="$ASAN_OPTS"
export UBSAN_OPTIONS="$UBSAN_OPTS"

JAVA="${JAVA_HOME:+${JAVA_HOME}/bin/}java"

# Fails the run on the first sanitizer report, because abort_on_error and halt_on_error make
# the JVM die rather than let a test method fail -- which is the behaviour wanted: a memory
# error is not a test failure to tally, it is a reason to stop.
exec "$JAVA" \
  -Xmx512m \
  -Dchdb.library.path="$RUNTIME" \
  -Dchdb.it.engine.version="$ENGINE_VERSION" \
  -cp "$CP" \
  org.junit.platform.console.ConsoleLauncher execute \
  --select-package=org.chdb.it \
  --include-classname='.*IT' \
  --details=tree \
  --disable-banner \
  --fail-if-no-tests
