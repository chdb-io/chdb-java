// Keeps the host JVM's signal dispositions intact across chDB calls.
//
// Work plan section 5.6 makes this a release gate: the JVM's SIGSEGV/SIGBUS/SIGILL/SIGFPE
// handlers are load-bearing (implicit null checks, stack banging, safepoint polling), so
// losing them is not a degraded diagnostic, it is a crash.
//
// What upstream does today (chdb-core programs/local/chdb.cpp):
//
//   void chdb_set_signal_handlers_enabled(int enabled) {
//       HandledSignals::disable_signal_handlers.store(!enabled, ...);
//       if (!enabled) chdb_reset_signal_handlers();
//   }
//
//   void chdb_reset_signal_handlers(void) {
//       static constexpr int deadly_signals[] = {SIGABRT, SIGSEGV, SIGILL, SIGBUS,
//                                                SIGSYS, SIGFPE, SIGTSTP, SIGTRAP};
//       ... sigaction(sig, {SIG_DFL}, nullptr) for each ...
//   }
//
// So the very call that opts out of chDB's handlers also drops the JVM's to SIG_DFL, and
// chdb_connect() re-runs the reset whenever the opt-out flag is set (chdb.cpp lines 237
// and 550, once each on the inner and outer connect path).
//
// Until chdb-core grows an API that suppresses future installs without resetting the
// incumbent handlers, the shim brackets every chDB call that can reach that code with
// SignalGuard: snapshot the dispositions, make the call, restore whatever changed. The
// guard is deliberately wider than upstream's eight signals -- restoring an unchanged
// disposition is a no-op, so guarding more costs nothing and survives upstream adding a
// signal to the list.

#pragma once

#include <csignal>
#include <string>
#include <vector>

namespace chdb_jni
{

// The signals SignalGuard snapshots and restores.
const std::vector<int> & guardedSignals();

// Human-readable name for a guarded signal, for diagnostics and test assertions.
const char * signalName(int signum);

// Snapshots the disposition of every guarded signal on construction and restores any
// that changed. Construction takes a process-wide lock held until destruction, so two
// threads cannot interleave a reset with a restore.
//
// The destructor restores, so the guard is correct when used purely as a scope guard.
// restore() exists because the caller that reports which signals chDB clobbered needs the
// answer while the guard is still in scope, and reading it after the destructor has run is
// not something C++ lets you do.
class SignalGuard
{
public:
    SignalGuard();
    ~SignalGuard();

    SignalGuard(const SignalGuard &) = delete;
    SignalGuard & operator=(const SignalGuard &) = delete;

    // Restores every guarded signal whose disposition changed since construction and
    // returns those signals. Idempotent: a second call finds nothing left to restore and
    // returns an empty vector, so the destructor calling it again is harmless.
    std::vector<int> restore();

private:
    std::vector<struct sigaction> saved_;
    bool restored_ = false;
};

// One line per guarded signal describing its current disposition, e.g.
// "SIGSEGV=handler:0x10a3b4c20 flags=0x42". Tests diff this across load/connect/query/close
// (work plan section 5.6); it is a diagnostic, not a parsed contract.
std::string describeSignalDispositions();

}  // namespace chdb_jni
