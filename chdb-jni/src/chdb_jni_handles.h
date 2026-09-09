// Opaque handle framework for the chDB JNI shim.
//
// Work plan section 5.5 requires that a handle crossing the JNI boundary carry a magic,
// an ABI version, a kind and a state, and that an invalid or already-closed handle be
// rejected rather than dereferenced.
//
// The handles Java sees are registry ids, not pointers. A pointer-as-jlong scheme cannot
// distinguish "freed" from "freed and the allocator handed the address to a new handle",
// so a stale id would eventually pass a magic check and dereference reused memory. An id
// that is gone from the registry is simply absent, which turns every use-after-free into
// a clean Java exception. Lookup takes one mutex; the data path makes native calls per
// batch rather than per cell (work plan section 3.3), so that cost is not on a hot loop.

#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <unordered_map>

namespace chdb_jni
{

// Bumped only on an incompatible change to the Java <-> shim contract. Must match
// ChdbNative.JNI_ABI_VERSION on the Java side and chdb.jni.abi.version in the poms.
constexpr int32_t kJniAbiVersion = 2;

enum HandleKind : int32_t
{
    kKindConnection = 1,
    kKindResult = 2,
    kKindStream = 3,
};

// Lock order, for every mutex in the shim. Acquire left to right, never right to left.
//
//     StreamHandle::mutex  ->  ConnHandle::mutex  ->  HandleRegistry::mutex_
//
// and, separately and never nested with any of the above:
//
//     chdb_jni_signals.cpp signalMutex()      (leaf, held only around chdb_connect and
//                                              chdb_set_signal_handlers_enabled)
//
// Why the stream comes first: a stream is driven by exactly one caller thread, which holds
// its handle mutex for the length of a fetch, and that fetch needs the connection to stay
// open underneath it -- so the connection lock is the inner one. Nothing goes the other way:
// closeConnection() takes only ConnHandle::mutex and does not reach into stream handles, and
// the open paths take only ConnHandle::mutex because their stream is not published yet and no
// other thread can see it.
//
// HandleRegistry::mutex_ is last because registry methods are leaves: they never call the
// engine and never take a handle mutex.
//
// ConnHandle::conn must be read under ConnHandle::mutex, and the engine call that uses it made
// while that lock is still held. Reading it under the stream's lock alone is a data race with
// closeConnection() -- and worse than a race, since the value can become null between the
// check and the dereference.
struct HandleBase
{
    explicit HandleBase(HandleKind k) : kind(k) { }
    virtual ~HandleBase() = default;

    HandleBase(const HandleBase &) = delete;
    HandleBase & operator=(const HandleBase &) = delete;

    const int32_t abi = kJniAbiVersion;
    const HandleKind kind;

    // Guards the handle's native payload. Every entry point that touches the payload
    // holds it, which is what makes close idempotent against a concurrent in-flight call.
    std::mutex mutex;
    bool closed = false;
};

// Registry of live handles. Ids are monotonic and never reused within a process, so a
// double close or a use-after-close is a missing key rather than a dangling pointer.
class HandleRegistry
{
public:
    static HandleRegistry & instance()
    {
        static HandleRegistry registry;
        return registry;
    }

    int64_t insert(const std::shared_ptr<HandleBase> & handle)
    {
        std::lock_guard<std::mutex> lock(mutex_);
        const int64_t id = next_id_++;
        handles_.emplace(id, handle);
        counts_[handle->kind].fetch_add(1, std::memory_order_relaxed);
        return id;
    }

    // Returns the handle if the id is live and of the expected kind, else nullptr.
    // The shared_ptr keeps the handle alive for the duration of the caller's call even
    // if another thread removes the id concurrently.
    std::shared_ptr<HandleBase> get(int64_t id, HandleKind expected) const
    {
        std::lock_guard<std::mutex> lock(mutex_);
        auto it = handles_.find(id);
        if (it == handles_.end() || it->second->kind != expected)
            return nullptr;
        return it->second;
    }

    // Removes the id and returns what it pointed at, or nullptr if it was already gone.
    // Callers use "was it there" to make close idempotent without a second lookup.
    std::shared_ptr<HandleBase> remove(int64_t id, HandleKind expected)
    {
        std::lock_guard<std::mutex> lock(mutex_);
        auto it = handles_.find(id);
        if (it == handles_.end() || it->second->kind != expected)
            return nullptr;
        auto handle = it->second;
        handles_.erase(it);
        counts_[handle->kind].fetch_sub(1, std::memory_order_relaxed);
        return handle;
    }

    // Live handles of one kind. Tests assert this returns to zero (work plan section 5.11).
    int64_t openCount(int32_t kind) const
    {
        auto it = counts_.find(kind);
        return it == counts_.end() ? 0 : it->second.load(std::memory_order_relaxed);
    }

    int64_t openCountTotal() const
    {
        std::lock_guard<std::mutex> lock(mutex_);
        return static_cast<int64_t>(handles_.size());
    }

private:
    HandleRegistry()
    {
        counts_.emplace(kKindConnection, 0);
        counts_.emplace(kKindResult, 0);
        counts_.emplace(kKindStream, 0);
    }

    mutable std::mutex mutex_;
    std::unordered_map<int64_t, std::shared_ptr<HandleBase>> handles_;
    // Separate from handles_ so openCount() is cheap and lock-free for test assertions.
    std::unordered_map<int32_t, std::atomic<int64_t>> counts_;
    int64_t next_id_ = 1;
};

}  // namespace chdb_jni
