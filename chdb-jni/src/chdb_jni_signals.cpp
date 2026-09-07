#include "chdb_jni_signals.h"

#include <cstdio>
#include <cstring>
#include <mutex>

namespace chdb_jni
{

namespace
{

// Serializes snapshot/call/restore across threads. A second thread running chdb_connect()
// while this one is between the reset and the restore would otherwise snapshot SIG_DFL as
// if the JVM had never installed anything, and restore that.
std::mutex & signalMutex()
{
    static std::mutex mutex;
    return mutex;
}

struct NamedSignal
{
    int signum;
    const char * name;
};

// The first eight are exactly upstream's `deadly_signals`. The rest are signals a JVM or
// a host application cares about (graceful shutdown, HotSpot's thread suspend/resume) that
// upstream does not touch today but might, and that cost nothing to guard.
const NamedSignal kNamedSignals[] = {
    {SIGABRT, "SIGABRT"}, {SIGSEGV, "SIGSEGV"}, {SIGILL, "SIGILL"},   {SIGBUS, "SIGBUS"},
    {SIGSYS, "SIGSYS"},   {SIGFPE, "SIGFPE"},   {SIGTSTP, "SIGTSTP"}, {SIGTRAP, "SIGTRAP"},
    {SIGINT, "SIGINT"},   {SIGTERM, "SIGTERM"}, {SIGQUIT, "SIGQUIT"}, {SIGHUP, "SIGHUP"},
    {SIGPIPE, "SIGPIPE"}, {SIGUSR1, "SIGUSR1"}, {SIGUSR2, "SIGUSR2"}, {SIGXFSZ, "SIGXFSZ"},
};

// sa_handler and sa_sigaction share a union, so which one carries the pointer depends on
// SA_SIGINFO. Reading the wrong member is not a crash but it does make two different
// dispositions compare equal, which would defeat the guard.
const void * handlerOf(const struct sigaction & sa)
{
    if ((sa.sa_flags & SA_SIGINFO) != 0)
        return reinterpret_cast<const void *>(sa.sa_sigaction);
    return reinterpret_cast<const void *>(sa.sa_handler);
}

bool sameDisposition(const struct sigaction & a, const struct sigaction & b)
{
    if (a.sa_flags != b.sa_flags)
        return false;
    if (handlerOf(a) != handlerOf(b))
        return false;
    // sigset_t is opaque and not guaranteed to be comparable with memcmp on every libc,
    // so compare it signal by signal.
    for (const auto & named : kNamedSignals)
    {
        const bool in_a = sigismember(&a.sa_mask, named.signum) == 1;
        const bool in_b = sigismember(&b.sa_mask, named.signum) == 1;
        if (in_a != in_b)
            return false;
    }
    return true;
}

}  // namespace

const std::vector<int> & guardedSignals()
{
    static const std::vector<int> signals = [] {
        std::vector<int> result;
        result.reserve(sizeof(kNamedSignals) / sizeof(kNamedSignals[0]));
        for (const auto & named : kNamedSignals)
            result.push_back(named.signum);
        return result;
    }();
    return signals;
}

const char * signalName(int signum)
{
    for (const auto & named : kNamedSignals)
        if (named.signum == signum)
            return named.name;
    return "SIG?";
}

SignalGuard::SignalGuard()
{
    signalMutex().lock();

    const auto & signals = guardedSignals();
    saved_.resize(signals.size());
    for (size_t i = 0; i < signals.size(); ++i)
    {
        std::memset(&saved_[i], 0, sizeof(saved_[i]));
        // A failed query leaves saved_[i] zeroed; the restore below then compares it
        // against the live disposition and, if they differ, installs the zeroed one.
        // Zeroed means SIG_DFL with no flags, which is what a signal we could not read
        // would have been set to anyway -- and on every platform we support, querying a
        // guardable signal does not fail.
        sigaction(signals[i], nullptr, &saved_[i]);
    }
}

std::vector<int> SignalGuard::restore()
{
    std::vector<int> restored;
    if (restored_)
        return restored;
    restored_ = true;

    const auto & signals = guardedSignals();
    for (size_t i = 0; i < signals.size(); ++i)
    {
        struct sigaction current;
        std::memset(&current, 0, sizeof(current));
        if (sigaction(signals[i], nullptr, &current) != 0)
            continue;
        if (sameDisposition(current, saved_[i]))
            continue;
        if (sigaction(signals[i], &saved_[i], nullptr) == 0)
            restored.push_back(signals[i]);
    }
    return restored;
}

SignalGuard::~SignalGuard()
{
    restore();
    signalMutex().unlock();
}

std::string describeSignalDispositions()
{
    std::string out;
    for (const auto & named : kNamedSignals)
    {
        struct sigaction sa;
        std::memset(&sa, 0, sizeof(sa));
        if (sigaction(named.signum, nullptr, &sa) != 0)
        {
            out += named.name;
            out += "=<unreadable>\n";
            continue;
        }

        const void * handler = handlerOf(sa);
        const char * symbolic = nullptr;
        if (handler == reinterpret_cast<const void *>(SIG_DFL))
            symbolic = "SIG_DFL";
        else if (handler == reinterpret_cast<const void *>(SIG_IGN))
            symbolic = "SIG_IGN";

        char line[160];
        if (symbolic != nullptr)
            std::snprintf(line, sizeof(line), "%s=%s flags=0x%x\n", named.name, symbolic, sa.sa_flags);
        else
            std::snprintf(line, sizeof(line), "%s=handler:%p flags=0x%x\n", named.name, handler, sa.sa_flags);
        out += line;
    }
    return out;
}

}  // namespace chdb_jni
