// Sanitizer harness for the shim's standalone logic.
//
// Why this exists rather than running the JDBC suite under ASan: ASan replaces malloc for the
// whole process, and the released libchdb has its own allocator. Preloading ASan into a JVM
// that loads a release engine crashes -- inside libchdb with the JIT disabled, inside C2 with
// it enabled. Covering the engine's allocations needs a sanitizer build of chdb-core, which
// upstream does not publish (work plan section 5.11 anticipates exactly that).
//
// So ASan is pointed at the parts of the shim that can be exercised without either a JVM or
// the engine, which is where its pointer arithmetic and lifetime logic live:
//
//   - Arrow format-string parsing, including malformed input, which decides how many bytes of
//     each buffer Java is allowed to see. Getting a width wrong here is how a caller would end
//     up reading past a producer's allocation.
//   - The handle registry, which is the mechanism that turns a use-after-close into an
//     exception instead of a dereference.
//   - The signal guard's snapshot and restore.
//
// UBSan covers the whole JDBC suite separately, in a real JVM against the real engine, because
// it does not intercept allocation and so coexists with both.
//
// No test framework, on purpose: this links two translation units and a header, and adding a
// dependency to run twenty assertions would be the larger cost.

#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <thread>
#include <vector>

#include "chdb_jni_arrow.h"
#include "chdb_jni_handles.h"
#include "chdb_jni_signals.h"

using namespace chdb_jni;

namespace
{

int failures = 0;
int checks = 0;

void check(bool condition, const std::string & what)
{
    ++checks;
    if (!condition)
    {
        ++failures;
        std::printf("FAIL  %s\n", what.c_str());
    }
}

void checkEqual(int64_t actual, int64_t expected, const std::string & what)
{
    ++checks;
    if (actual != expected)
    {
        ++failures;
        std::printf("FAIL  %s: got %lld, expected %lld\n", what.c_str(),
                    static_cast<long long>(actual), static_cast<long long>(expected));
    }
}

// ---------------------------------------------------------------- arrow layout

void testArrowLayout()
{
    struct Case
    {
        const char * format;
        ArrowLayout layout;
        int32_t bytes;
    };

    const Case cases[] = {
        {"b", ArrowLayout::kBitmap, 0},
        {"c", ArrowLayout::kFixedWidth, 1},
        {"C", ArrowLayout::kFixedWidth, 1},
        {"s", ArrowLayout::kFixedWidth, 2},
        {"S", ArrowLayout::kFixedWidth, 2},
        {"e", ArrowLayout::kFixedWidth, 2},
        {"i", ArrowLayout::kFixedWidth, 4},
        {"I", ArrowLayout::kFixedWidth, 4},
        {"f", ArrowLayout::kFixedWidth, 4},
        {"l", ArrowLayout::kFixedWidth, 8},
        {"L", ArrowLayout::kFixedWidth, 8},
        {"g", ArrowLayout::kFixedWidth, 8},
        {"u", ArrowLayout::kVarBinary32, 0},
        {"z", ArrowLayout::kVarBinary32, 0},
        {"U", ArrowLayout::kVarBinary64, 0},
        {"Z", ArrowLayout::kVarBinary64, 0},
        {"d:18,3", ArrowLayout::kFixedWidth, 16},
        {"d:9,2,128", ArrowLayout::kFixedWidth, 16},
        {"d:76,10,256", ArrowLayout::kFixedWidth, 32},
        {"w:16", ArrowLayout::kFixedWidth, 16},
        {"w:3", ArrowLayout::kFixedWidth, 3},
        {"w:1", ArrowLayout::kFixedWidth, 1},
        {"tdD", ArrowLayout::kFixedWidth, 4},
        {"tdm", ArrowLayout::kFixedWidth, 8},
        {"tss:UTC", ArrowLayout::kFixedWidth, 8},
        {"tsm:", ArrowLayout::kFixedWidth, 8},
        {"tsu:Europe/Berlin", ArrowLayout::kFixedWidth, 8},
        {"tsn:UTC", ArrowLayout::kFixedWidth, 8},
        {"tts", ArrowLayout::kFixedWidth, 4},
        {"ttm", ArrowLayout::kFixedWidth, 4},
        {"ttu", ArrowLayout::kFixedWidth, 8},
        {"ttn", ArrowLayout::kFixedWidth, 8},
        {"tDs", ArrowLayout::kFixedWidth, 8},
    };

    for (const auto & c : cases)
    {
        const ArrowColumnLayout layout = parseArrowFormat(c.format);
        check(layout.layout == c.layout, std::string("layout of \"") + c.format + "\"");
        if (c.layout == ArrowLayout::kFixedWidth)
            checkEqual(layout.element_bytes, c.bytes, std::string("width of \"") + c.format + "\"");
    }

    // Anything not recognized has to come back unsupported. The shim then hands Java no data
    // buffers for the column, which is what turns an unknown type into a typed error rather
    // than a mis-sliced read.
    const char * unsupported[] = {
        "",       "+l",   "+L",  "+s",     "+m",   "+ud:0,1", "+r",   "+w:3",
        "vu",     "vz",   "qqq", "d:9",    "d:",   "d:a,b",   "d:9,2,64",
        "w:",     "w:0",  "w:-1", "w:abc", "tdX",  "tt",      "ttX",  "ts",
        "tsX:UTC", "tiM", "n",   "Q",      "\x01",
    };
    for (const char * format : unsupported)
    {
        const ArrowColumnLayout layout = parseArrowFormat(format);
        check(layout.layout == ArrowLayout::kUnsupported,
              std::string("\"") + format + "\" must be unsupported");
    }

    // A very long malformed format must not read past its end.
    std::string longFormat(4096, 'd');
    longFormat[1] = ':';
    check(parseArrowFormat(longFormat).layout != ArrowLayout::kBitmap, "long malformed format");

    // A width that would overflow the multiplication the shim does to size a buffer.
    const ArrowColumnLayout huge = parseArrowFormat("w:2147483647");
    check(huge.layout == ArrowLayout::kFixedWidth, "w:2147483647 parses");
    checkEqual(huge.element_bytes, 2147483647, "w:2147483647 width");
}

// ---------------------------------------------------------------- handle registry

struct TestHandle : HandleBase
{
    explicit TestHandle(HandleKind kind, std::atomic<int> * destroyed_counter)
        : HandleBase(kind), destroyed(destroyed_counter)
    {
    }

    ~TestHandle() override
    {
        if (destroyed != nullptr)
            destroyed->fetch_add(1);
    }

    std::atomic<int> * destroyed;
};

void testHandleRegistry()
{
    auto & registry = HandleRegistry::instance();
    const int64_t connections_before = registry.openCount(kKindConnection);

    std::atomic<int> destroyed{0};
    const int64_t id = registry.insert(std::make_shared<TestHandle>(kKindConnection, &destroyed));

    check(id > 0, "ids are positive");
    checkEqual(registry.openCount(kKindConnection), connections_before + 1, "count after insert");
    check(registry.get(id, kKindConnection) != nullptr, "a live id resolves");

    // The kind is part of the identity: a result id must not resolve as a connection, or a
    // handle would be reinterpreted as the wrong type.
    check(registry.get(id, kKindResult) == nullptr, "wrong kind does not resolve");
    check(registry.get(id, kKindStream) == nullptr, "wrong kind does not resolve (stream)");

    // Ids never seen, and ids that cannot exist.
    check(registry.get(id + 1000000, kKindConnection) == nullptr, "unknown id does not resolve");
    check(registry.get(0, kKindConnection) == nullptr, "id 0 does not resolve");
    check(registry.get(-1, kKindConnection) == nullptr, "negative id does not resolve");
    check(registry.get(INT64_MAX, kKindConnection) == nullptr, "INT64_MAX does not resolve");

    // Removing returns the handle once and only once. That is what makes close idempotent
    // without a second lookup, and what keeps a double close from double-freeing.
    auto removed = registry.remove(id, kKindConnection);
    check(removed != nullptr, "remove returns the handle");
    check(registry.remove(id, kKindConnection) == nullptr, "second remove returns nothing");
    check(registry.get(id, kKindConnection) == nullptr, "a removed id does not resolve");
    checkEqual(registry.openCount(kKindConnection), connections_before, "count after remove");

    // The shared_ptr the caller still holds keeps it alive, which is what lets an in-flight
    // call finish while another thread closes the same handle.
    checkEqual(destroyed.load(), 0, "still alive while a reference is held");
    removed.reset();
    checkEqual(destroyed.load(), 1, "destroyed when the last reference goes");

    // Ids are not reused, so a stale id can never resolve to a later handle. Without this a
    // use-after-close would eventually read a live object of the right shape.
    std::vector<int64_t> ids;
    for (int i = 0; i < 64; ++i)
        ids.push_back(registry.insert(std::make_shared<TestHandle>(kKindResult, nullptr)));
    for (int64_t used : ids)
        registry.remove(used, kKindResult);
    const int64_t after = registry.insert(std::make_shared<TestHandle>(kKindResult, nullptr));
    for (int64_t used : ids)
        check(after != used, "ids are not reused");
    registry.remove(after, kKindResult);

    checkEqual(registry.openCount(kKindResult), 0, "result count back to zero");
}

void testHandleRegistryConcurrency()
{
    auto & registry = HandleRegistry::instance();
    constexpr int kThreads = 8;
    constexpr int kPerThread = 500;

    std::atomic<int> resolved{0};
    std::atomic<int> missed{0};
    std::vector<std::thread> threads;

    // Insert, look up and remove from several threads at once. ASan and TSan aside, the
    // property being checked is that no thread ever sees another's handle as its own and the
    // count returns to zero.
    for (int t = 0; t < kThreads; ++t)
    {
        threads.emplace_back([&registry, &resolved, &missed] {
            for (int i = 0; i < kPerThread; ++i)
            {
                const int64_t id
                    = registry.insert(std::make_shared<TestHandle>(kKindStream, nullptr));
                auto found = registry.get(id, kKindStream);
                if (found)
                    ++resolved;
                else
                    ++missed;
                registry.remove(id, kKindStream);
                // A removed id must stay gone even while other threads are inserting.
                if (registry.get(id, kKindStream) != nullptr)
                    ++missed;
            }
        });
    }
    for (auto & thread : threads)
        thread.join();

    checkEqual(resolved.load(), kThreads * kPerThread, "every handle resolved on its own thread");
    checkEqual(missed.load(), 0, "no handle was missed or resurrected");
    checkEqual(registry.openCount(kKindStream), 0, "stream count back to zero");
}

// ---------------------------------------------------------------- signal guard

void testSignalGuard()
{
    check(!guardedSignals().empty(), "the guarded set is not empty");

    // Every signal upstream's chdb_reset_signal_handlers() touches has to be guarded, or the
    // shim would restore only part of what chDB clobbers.
    const int upstream[] = {SIGABRT, SIGSEGV, SIGILL, SIGBUS, SIGSYS, SIGFPE, SIGTSTP, SIGTRAP};
    for (int signum : upstream)
    {
        bool guarded = false;
        for (int candidate : guardedSignals())
            if (candidate == signum)
                guarded = true;
        check(guarded, std::string("guards ") + signalName(signum));
        check(std::strcmp(signalName(signum), "SIG?") != 0,
              std::string("names signal ") + std::to_string(signum));
    }

    check(!describeSignalDispositions().empty(), "dispositions describe as non-empty");

    // Install a handler, let the guard clobber it the way chDB would, and check it comes back.
    struct sigaction installed;
    std::memset(&installed, 0, sizeof(installed));
    installed.sa_handler = [](int) {};
    sigemptyset(&installed.sa_mask);
    installed.sa_flags = 0;

    struct sigaction original;
    std::memset(&original, 0, sizeof(original));
    // SIGUSR2 rather than a crash signal: this harness has no JVM to keep alive, but breaking
    // the test process's own SIGSEGV handling would make a failure unreadable.
    sigaction(SIGUSR2, &installed, &original);

    {
        SignalGuard guard;
        struct sigaction defaulted;
        std::memset(&defaulted, 0, sizeof(defaulted));
        defaulted.sa_handler = SIG_DFL;
        sigemptyset(&defaulted.sa_mask);
        defaulted.sa_flags = 0;
        sigaction(SIGUSR2, &defaulted, nullptr);

        const std::vector<int> restored = guard.restore();
        bool reported = false;
        for (int signum : restored)
            if (signum == SIGUSR2)
                reported = true;
        check(reported, "the guard reports the signal it restored");

        struct sigaction now;
        std::memset(&now, 0, sizeof(now));
        sigaction(SIGUSR2, nullptr, &now);
        check(now.sa_handler == installed.sa_handler, "the handler was put back");

        // Idempotent: a second restore has nothing to do, which is why the destructor calling
        // it again is harmless.
        check(guard.restore().empty(), "restore is idempotent");
    }

    // A guard that changes nothing must report nothing.
    {
        SignalGuard guard;
        check(guard.restore().empty(), "an untouched guard restores nothing");
    }

    // Re-installing the same disposition must not count as a change.
    //
    // The case this pins is a Linux one. glibc sets SA_RESTORER on every sigaction() it
    // performs, so a signal sitting at SIG_DFL with no flags reads back with SA_RESTORER after
    // anything -- chDB's reset included -- sets it to SIG_DFL again. Restoring the original
    // goes through glibc too and sets the flag again, so a guard that compared it would report
    // the signal as clobbered forever while the disposition never actually changed.
    //
    // On macOS there is no such flag and this passes trivially, which is exactly why it took
    // CI on Linux to find the bug.
    {
        struct sigaction defaulted;
        std::memset(&defaulted, 0, sizeof(defaulted));
        defaulted.sa_handler = SIG_DFL;
        sigemptyset(&defaulted.sa_mask);
        defaulted.sa_flags = 0;
        sigaction(SIGUSR2, &defaulted, nullptr);

        SignalGuard guard;
        // Exactly what chdb_reset_signal_handlers() does: set SIG_DFL over SIG_DFL.
        sigaction(SIGUSR2, &defaulted, nullptr);
        const std::vector<int> restored = guard.restore();
        for (int signum : restored)
            check(signum != SIGUSR2,
                  "re-installing SIG_DFL over SIG_DFL must not read as a change"
                  " (libc-set flags such as SA_RESTORER are not part of the disposition)");
        check(restored.empty(), "no signal should have needed restoring");
    }

    // And the description must agree with the comparison, or a caller diffing it across a chDB
    // call would see a difference the guard says is not there.
    {
        struct sigaction defaulted;
        std::memset(&defaulted, 0, sizeof(defaulted));
        defaulted.sa_handler = SIG_DFL;
        sigemptyset(&defaulted.sa_mask);
        defaulted.sa_flags = 0;
        sigaction(SIGUSR2, &defaulted, nullptr);

        const std::string before = describeSignalDispositions();
        sigaction(SIGUSR2, &defaulted, nullptr);
        check(describeSignalDispositions() == before,
              "the disposition report must not change when nothing behavioural did");
    }

    sigaction(SIGUSR2, &original, nullptr);
}

}  // namespace

int main()
{
    std::printf("chdb_java_jni sanitizer harness\n\n");

    testArrowLayout();
    testHandleRegistry();
    testHandleRegistryConcurrency();
    testSignalGuard();

    std::printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
