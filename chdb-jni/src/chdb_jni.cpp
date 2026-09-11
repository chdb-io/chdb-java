// JNI shim between org.chdb.internal.ChdbNative and the chDB stable C ABI.
//
// Responsibilities, per work plan section 3.1:
//   - own every native handle and reject invalid or closed ones (chdb_jni_handles.h)
//   - convert every C++ exception and every engine error into a Java exception, so that
//     nothing unwinds across the JNI boundary
//   - keep the host JVM's signal dispositions intact (chdb_jni_signals.h)
//   - hand Java bounded, correctly sized views of the current Arrow batch and nothing else
//
// It deliberately holds no query logic. Deciding what a statement is, mapping types and
// enforcing JDBC semantics all happen in Java, where they are testable without a build.

#include <jni.h>

#include <cstring>
#include <memory>
#include <new>
#include <string>
#include <vector>

#include "arrow_c_abi.h"
#include "chdb_jni_arrow.h"
#include "chdb_jni_handles.h"
#include "chdb_jni_signals.h"
#include "chdb_optional_api.h"

extern "C" {
#include "chdb.h"
}

#ifdef CHDB_JNI_HAVE_GENERATED_HEADER
// javac -h output for org.chdb.internal.ChdbNative. Including it makes the compiler check
// every entry point below against the Java declaration it implements, so a signature that
// drifts is a build error here rather than an UnsatisfiedLinkError in a user's JVM.
#include "org_chdb_internal_ChdbNative.h"
#endif

using namespace chdb_jni;

namespace
{

constexpr const char * kNativeExceptionClass = "org/chdb/internal/ChdbNativeException";

// ---------------------------------------------------------------------- throwing

// Queues a Java exception. The caller must return to Java immediately afterwards; JNI
// calls made while an exception is pending are undefined for most functions.
void throwNative(JNIEnv * env, const std::string & message)
{
    if (env->ExceptionCheck())
        return;  // An earlier failure already queued something more specific.
    jclass clazz = env->FindClass(kNativeExceptionClass);
    if (clazz == nullptr)
        return;  // FindClass queued NoClassDefFoundError, which is the honest report here.
    env->ThrowNew(clazz, message.c_str());
    env->DeleteLocalRef(clazz);
}

void throwOutOfMemory(JNIEnv * env, const char * what)
{
    if (env->ExceptionCheck())
        return;
    jclass clazz = env->FindClass("java/lang/OutOfMemoryError");
    if (clazz == nullptr)
        return;
    env->ThrowNew(clazz, what);
    env->DeleteLocalRef(clazz);
}

// ---------------------------------------------------------------------- handles

struct ConnHandle : HandleBase
{
    ConnHandle() : HandleBase(kKindConnection) { }

    ~ConnHandle() override { closeNow(); }

    // Idempotent, and safe to call from both closeConnection() and the destructor.
    void closeNow()
    {
        if (conn == nullptr)
            return;
        chdb_connection * to_close = conn;
        conn = nullptr;
        closed = true;
        chdb_close_conn(to_close);
    }

    chdb_connection * conn = nullptr;
};

struct ResultHandle : HandleBase
{
    ResultHandle() : HandleBase(kKindResult) { }

    ~ResultHandle() override { closeNow(); }

    void closeNow()
    {
        if (result == nullptr)
            return;
        chdb_result * to_destroy = result;
        result = nullptr;
        closed = true;
        chdb_destroy_query_result(to_destroy);
    }

    chdb_result * result = nullptr;
};

// Where a stream handle's batches come from. Both kinds are the same Arrow C Data
// Interface to Java -- ArrowSchemaView, ArrowBatch and ChdbResultSet cannot tell them
// apart -- and differ only in which engine call produces the next batch.
enum class BatchSource
{
    // chdb_stream_query_arrow_n: the engine holds the pipeline and hands over one batch per
    // chdb_stream_fetch_arrow. Only a SELECT pipeline is admitted (issue #12).
    kEngineStream,
    // chdb_query_arrow_n: the statement ran to completion and exported the whole result into
    // an ArrowArrayStream this handle owns. Batches come from its get_next.
    kMaterializedArrow,
};

struct StreamHandle : HandleBase
{
    StreamHandle() : HandleBase(kKindStream) { }

    ~StreamHandle() override { closeNow(); }

    void releaseCurrent()
    {
        if (has_current)
        {
            if (current.release != nullptr)
                current.release(&current);
            std::memset(&current, 0, sizeof(current));
            has_current = false;
        }
    }

    void releasePending()
    {
        if (has_pending)
        {
            if (pending.release != nullptr)
                pending.release(&pending);
            std::memset(&pending, 0, sizeof(pending));
            has_pending = false;
        }
    }

    void closeNow()
    {
        releaseCurrent();
        releasePending();

        if (has_schema)
        {
            if (schema.release != nullptr)
                schema.release(&schema);
            std::memset(&schema, 0, sizeof(schema));
            has_schema = false;
        }

        if (stream != nullptr)
        {
            chdb_result * to_destroy = stream;
            stream = nullptr;
            chdb_destroy_query_result(to_destroy);
        }

        // Released after the batches above, which is what frees the engine buffers behind a
        // materialized result: the batches are independently owned per the Arrow C ABI, but
        // releasing the producer first would leave them reading from a retired allocator.
        //
        // Keyed on the release callback itself rather than on a flag the open path sets. The
        // engine writes into this struct before it can tell us whether the call succeeded, so
        // a flag set afterwards is a flag that is false on exactly the paths that need to
        // clean up. Zeroed at construction, so "release is non-null" means "there is
        // something to release" and nothing else has to stay in sync with it.
        if (array_stream.release != nullptr)
        {
            array_stream.release(&array_stream);
            std::memset(&array_stream, 0, sizeof(array_stream));
        }

        closed = true;
        // Dropped last: the connection must outlive every stream opened on it, so the
        // stream holds a strong reference and releases it only here.
        owner.reset();
    }

    // Whether this handle still has a batch producer to drive. Kind-dependent: a
    // materialized handle has no chdb_result at all -- chdb_query_arrow_n's metrics object is
    // destroyed at open, because the data lives in array_stream and the two have independent
    // lifetimes.
    bool hasProducer() const
    {
        return source == BatchSource::kEngineStream ? stream != nullptr
                                                     : array_stream.release != nullptr;
    }

    BatchSource source = BatchSource::kEngineStream;

    chdb_result * stream = nullptr;
    std::shared_ptr<ConnHandle> owner;

    // kMaterializedArrow only: the stream chdb_query_arrow_n exported into. Owned here, and
    // released by closeNow() whenever its release callback is set -- there is no separate
    // "is it valid" flag, deliberately: see closeNow().
    ArrowArrayStream array_stream{};

    ArrowSchema schema{};
    bool has_schema = false;

    // The batch Java is reading right now. Its buffers back live DirectByteBuffers, so it
    // is released only on advance or close (work plan section 3.3).
    ArrowArray current{};
    bool has_current = false;

    // The first batch, fetched at open to obtain the schema. Handed out by the first
    // advance rather than discarded.
    ArrowArray pending{};
    bool has_pending = false;

    bool eos = false;

    // Set by streamCancel. Distinct from eos: a cancelled stream must fail the next fetch
    // rather than report a clean end, or a caller cannot tell "you asked me to stop" from
    // "there was no more data" -- and would silently process a truncated result.
    bool cancelled = false;

    std::vector<std::string> column_names;
    std::vector<std::string> column_formats;
    std::vector<ArrowColumnLayout> column_layouts;
    std::vector<unsigned char> column_nullable;
};

// ---------------------------------------------------------------------- lookups

std::shared_ptr<ConnHandle> requireConnection(JNIEnv * env, jlong id)
{
    auto base = HandleRegistry::instance().get(id, kKindConnection);
    if (!base)
    {
        throwNative(env, "connection handle " + std::to_string(id) + " is not open (it was "
                         "closed, or never existed). Reusing a closed Connection is a bug in "
                         "the caller, not a recoverable error.");
        return nullptr;
    }
    return std::static_pointer_cast<ConnHandle>(base);
}

std::shared_ptr<ResultHandle> requireResult(JNIEnv * env, jlong id)
{
    auto base = HandleRegistry::instance().get(id, kKindResult);
    if (!base)
    {
        throwNative(env, "result handle " + std::to_string(id) + " is not open");
        return nullptr;
    }
    return std::static_pointer_cast<ResultHandle>(base);
}

std::shared_ptr<StreamHandle> requireStream(JNIEnv * env, jlong id)
{
    auto base = HandleRegistry::instance().get(id, kKindStream);
    if (!base)
    {
        throwNative(env, "stream handle " + std::to_string(id) + " is not open");
        return nullptr;
    }
    return std::static_pointer_cast<StreamHandle>(base);
}

// ---------------------------------------------------------------------- string marshalling

// Copies a Java byte[] holding UTF-8 into a std::string. Length-carrying throughout: a
// value with an embedded NUL survives, which is the whole reason parameters cross as
// byte[] rather than as String (work plan section 5.5).
bool toBytes(JNIEnv * env, jbyteArray array, std::string & out)
{
    if (array == nullptr)
    {
        out.clear();
        return true;
    }
    const jsize length = env->GetArrayLength(array);
    out.resize(static_cast<size_t>(length));
    if (length > 0)
        env->GetByteArrayRegion(array, 0, length, reinterpret_cast<jbyte *>(&out[0]));
    return !env->ExceptionCheck();
}

bool toBytesVector(JNIEnv * env, jobjectArray array, std::vector<std::string> & out)
{
    out.clear();
    if (array == nullptr)
        return true;
    const jsize count = env->GetArrayLength(array);
    out.reserve(static_cast<size_t>(count));
    for (jsize i = 0; i < count; ++i)
    {
        jobject element = env->GetObjectArrayElement(array, i);
        if (env->ExceptionCheck())
            return false;
        std::string value;
        if (!toBytes(env, static_cast<jbyteArray>(element), value))
        {
            env->DeleteLocalRef(element);
            return false;
        }
        out.push_back(std::move(value));
        env->DeleteLocalRef(element);
    }
    return true;
}

jobjectArray toStringArray(JNIEnv * env, const std::vector<std::string> & values)
{
    jclass string_class = env->FindClass("java/lang/String");
    if (string_class == nullptr)
        return nullptr;
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(values.size()), string_class, nullptr);
    if (result == nullptr)
    {
        env->DeleteLocalRef(string_class);
        return nullptr;
    }
    for (size_t i = 0; i < values.size(); ++i)
    {
        jstring element = env->NewStringUTF(values[i].c_str());
        if (element == nullptr)
        {
            env->DeleteLocalRef(string_class);
            return nullptr;
        }
        env->SetObjectArrayElement(result, static_cast<jsize>(i), element);
        env->DeleteLocalRef(element);
    }
    env->DeleteLocalRef(string_class);
    return result;
}

// Builds the char*/size_t argument arrays the chdb_*_with_params_n family expects. The
// std::string storage stays owned by the caller's vectors, so this must not outlive them.
struct ParamArrays
{
    std::vector<const char *> names;
    std::vector<size_t> name_lengths;
    std::vector<const char *> values;
    std::vector<size_t> value_lengths;
};

ParamArrays buildParams(const std::vector<std::string> & names, const std::vector<std::string> & values)
{
    ParamArrays arrays;
    arrays.names.reserve(names.size());
    arrays.name_lengths.reserve(names.size());
    arrays.values.reserve(values.size());
    arrays.value_lengths.reserve(values.size());
    for (const auto & name : names)
    {
        arrays.names.push_back(name.data());
        arrays.name_lengths.push_back(name.size());
    }
    for (const auto & value : values)
    {
        arrays.values.push_back(value.data());
        arrays.value_lengths.push_back(value.size());
    }
    return arrays;
}

// ---------------------------------------------------------------------- engine errors

// Reads the engine's error text off a result. Returns empty when the call succeeded.
std::string resultError(chdb_result * result)
{
    if (result == nullptr)
        return "chDB returned no result object";
    const char * error = chdb_result_error(result);
    return error == nullptr ? std::string() : std::string(error);
}

// ---------------------------------------------------------------------- arrow plumbing

// Reads the last error off an ArrowArrayStream, for the materialized path. The Arrow C
// stream ABI puts the text behind get_last_error rather than on a result object.
std::string arrowStreamError(ArrowArrayStream & stream)
{
    if (stream.get_last_error == nullptr)
        return std::string();
    const char * error = stream.get_last_error(&stream);
    return error == nullptr ? std::string() : std::string(error);
}

// Fetches one batch from a materialized Arrow stream. Same contract as fetchBatch below.
bool fetchMaterializedBatch(JNIEnv * env, StreamHandle & handle, ArrowArray * out, bool want_schema)
{
    if (handle.array_stream.release == nullptr)
    {
        throwNative(env, "this result has no Arrow stream to read from");
        return false;
    }

    // Unlike the engine's streaming door, the schema is available without producing a batch:
    // the whole result is already exported, so get_schema answers straight away.
    if (want_schema && !handle.has_schema)
    {
        if (handle.array_stream.get_schema == nullptr
            || handle.array_stream.get_schema(&handle.array_stream, &handle.schema) != 0
            || handle.schema.release == nullptr)
        {
            std::string error = arrowStreamError(handle.array_stream);
            throwNative(env, error.empty()
                    ? "chDB executed the statement but exported no Arrow schema for it"
                    : error);
            return false;
        }
        handle.has_schema = true;
    }

    if (handle.array_stream.get_next == nullptr)
    {
        throwNative(env, "chDB exported an Arrow stream with no get_next callback");
        return false;
    }

    if (handle.array_stream.get_next(&handle.array_stream, out) != 0)
    {
        std::string error = arrowStreamError(handle.array_stream);
        throwNative(env, error.empty() ? "failed to read the next Arrow batch from the result" : error);
        return false;
    }
    return true;
}

// Fetches one batch. Returns false and queues a Java exception on engine error.
// `out` is left released (out->release == nullptr) at end of stream.
bool fetchBatch(JNIEnv * env, StreamHandle & handle, ArrowArray * out, bool want_schema)
{
    std::memset(out, 0, sizeof(*out));

    if (handle.source == BatchSource::kMaterializedArrow)
        return fetchMaterializedBatch(env, handle, out, want_schema);

    ArrowArrayStream one_batch;
    std::memset(&one_batch, 0, sizeof(one_batch));

    // The connection lock is taken here, inside the stream's, and held across the fetch:
    // chdb_close_conn() frees what `conn` points at, so reading the pointer and then using it
    // without the lock is a use-after-free, not merely a data race. See the lock order in
    // chdb_jni_handles.h -- StreamHandle::mutex before ConnHandle::mutex, never the reverse.
    //
    // Callers must not already hold the connection lock: std::mutex is not recursive.
    {
        if (!handle.owner)
        {
            throwNative(env, "the stream has no connection to fetch from");
            return false;
        }
        std::lock_guard<std::mutex> connection_lock(handle.owner->mutex);
        if (handle.owner->conn == nullptr)
        {
            throwNative(env, "the connection was closed while the query was still being read");
            return false;
        }
        if (chdb_stream_fetch_arrow(
                *handle.owner->conn, handle.stream, reinterpret_cast<chdb_arrow_stream>(&one_batch))
            != CHDBSuccess)
        {
            std::string error = resultError(handle.stream);
            throwNative(env, error.empty() ? "chdb_stream_fetch_arrow failed" : error);
            return false;
        }
    }

    // The schema is stable for the life of the stream, so it is captured once, from the
    // first batch stream, and outlives the batch stream it came from.
    if (want_schema && !handle.has_schema)
    {
        if (one_batch.get_schema == nullptr
            || one_batch.get_schema(&one_batch, &handle.schema) != 0
            || handle.schema.release == nullptr)
        {
            if (one_batch.release != nullptr)
                one_batch.release(&one_batch);
            throwNative(env, "chDB accepted the streaming query but produced no Arrow schema; "
                             "the engine's streaming state is inconsistent");
            return false;
        }
        handle.has_schema = true;
    }

    if (one_batch.get_next == nullptr)
    {
        if (one_batch.release != nullptr)
            one_batch.release(&one_batch);
        throwNative(env, "chDB returned a batch stream with no get_next callback");
        return false;
    }

    const int rc = one_batch.get_next(&one_batch, out);

    // The array is owned independently of the stream that produced it (Arrow C ABI), which
    // is why the batch stream is released here rather than kept alongside the batch.
    if (one_batch.release != nullptr)
        one_batch.release(&one_batch);

    if (rc != 0)
    {
        std::string error = resultError(handle.stream);
        throwNative(env, error.empty() ? "failed to read the next Arrow batch from the stream" : error);
        return false;
    }
    return true;
}

// Reads the schema's children into the flat column metadata Java asks for.
bool captureColumns(JNIEnv * env, StreamHandle & handle)
{
    if (!handle.has_schema)
    {
        throwNative(env, "no Arrow schema was captured for this stream");
        return false;
    }

    const ArrowSchema & schema = handle.schema;
    const int64_t count = schema.n_children;
    if (count < 0 || schema.children == nullptr)
    {
        throwNative(env, "chDB returned an Arrow schema with no children");
        return false;
    }

    handle.column_names.reserve(static_cast<size_t>(count));
    handle.column_formats.reserve(static_cast<size_t>(count));
    handle.column_layouts.reserve(static_cast<size_t>(count));
    handle.column_nullable.reserve(static_cast<size_t>(count));

    for (int64_t i = 0; i < count; ++i)
    {
        const ArrowSchema * child = schema.children[i];
        if (child == nullptr)
        {
            throwNative(env, "chDB returned a null Arrow schema child at index " + std::to_string(i));
            return false;
        }
        // An unnamed column still needs a stable JDBC label; Java falls back to the ordinal.
        handle.column_names.emplace_back(child->name == nullptr ? "" : child->name);
        const std::string format = child->format == nullptr ? "" : child->format;
        handle.column_formats.push_back(format);
        // A dictionary-encoded column reuses its value type's format string, so the format
        // alone cannot reveal it; check the schema instead.
        handle.column_layouts.push_back(
            child->dictionary != nullptr ? ArrowColumnLayout{} : parseArrowFormat(format));
        handle.column_nullable.push_back((child->flags & ARROW_FLAG_NULLABLE) != 0 ? 1 : 0);
    }
    return true;
}

// Fetches the first batch, captures the schema and column metadata from it, and registers
// the handle. Returns its id, or 0 with a Java exception already queued.
//
// Shared by both open paths. JDBC requires getMetaData() to answer before the first next(),
// and the engine's streaming door has no schema until a batch has been produced, so the
// first batch is fetched here and kept as the pending batch rather than discarded.
jlong primeAndRegister(JNIEnv * env, const std::shared_ptr<StreamHandle> & handle)
{
    // No connection lock here: fetchBatch takes it itself, and std::mutex is not recursive.
    // The handle is not in the registry yet, so no other thread can be driving it and there
    // is nothing to take its own mutex for either.
    if (!fetchBatch(env, *handle, &handle->pending, /*want_schema=*/true))
        return 0;  // handle's destructor tears down whatever it already owns.

    if (handle->pending.release == nullptr)
    {
        std::memset(&handle->pending, 0, sizeof(handle->pending));
        handle->has_pending = false;
        handle->eos = true;
    }
    else
    {
        handle->has_pending = true;
    }

    if (!captureColumns(env, *handle))
        return 0;

    return static_cast<jlong>(HandleRegistry::instance().insert(handle));
}

// Byte length of a validity bitmap covering `offset + length` slots.
int64_t validityBytes(int64_t offset, int64_t length)
{
    const int64_t slots = offset + length;
    return slots <= 0 ? 0 : (slots + 7) / 8;
}

jobject wrapBuffer(JNIEnv * env, const void * address, int64_t bytes)
{
    if (address == nullptr || bytes <= 0)
        return nullptr;
    // const_cast: NewDirectByteBuffer has no const overload. The Java side only ever
    // exposes these through read-only accessors.
    return env->NewDirectByteBuffer(const_cast<void *>(address), static_cast<jlong>(bytes));
}

}  // namespace

// ====================================================================== identity

extern "C" JNIEXPORT jint JNICALL
Java_org_chdb_internal_ChdbNative_jniAbiVersion(JNIEnv *, jclass)
{
    return static_cast<jint>(kJniAbiVersion);
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_chdb_internal_ChdbNative_engineVersion(JNIEnv * env, jclass)
{
    const char * version = chdb_version();
    return env->NewStringUTF(version == nullptr ? "" : version);
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_chdb_internal_ChdbNative_shimBuildInfo(JNIEnv * env, jclass)
{
    // CHDB_JNI_* come from the CMake build; CHDB_VERSION is the engine header's own
    // constant, so a header/library mismatch is visible without running a query.
    std::string info;
    info += "jni.abi.version=";
    info += std::to_string(kJniAbiVersion);
    info += "\nshim.commit=";
    info += CHDB_JNI_GIT_COMMIT;
    info += "\nshim.compiler=";
    info += CHDB_JNI_COMPILER;
    info += "\nshim.built.against.engine.header=";
    info += CHDB_VERSION;
    info += "\nshim.expected.engine.version=";
    info += CHDB_JNI_EXPECTED_ENGINE_VERSION;
    info += "\n";
    return env->NewStringUTF(info.c_str());
}

// ====================================================================== signals

extern "C" JNIEXPORT jstring JNICALL
Java_org_chdb_internal_ChdbNative_signalDispositions(JNIEnv * env, jclass)
{
    try
    {
        return env->NewStringUTF(describeSignalDispositions().c_str());
    }
    catch (const std::exception & e)
    {
        throwNative(env, std::string("failed to read signal dispositions: ") + e.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_chdb_internal_ChdbNative_protectHostSignalHandlers(JNIEnv * env, jclass)
{
    try
    {
        std::vector<std::string> restored;
        {
            SignalGuard guard;
            chdb_set_signal_handlers_enabled(0);
            // Restored explicitly rather than by the destructor, because the set of signals
            // chDB clobbered is the return value and it has to be read while the guard is
            // still alive. The destructor's second restore() is then a no-op.
            for (int signum : guard.restore())
                restored.emplace_back(signalName(signum));
        }
        return toStringArray(env, restored);
    }
    catch (const std::exception & e)
    {
        throwNative(env, std::string("failed to disable chDB signal handlers: ") + e.what());
        return nullptr;
    }
}

// ====================================================================== connection

extern "C" JNIEXPORT jlong JNICALL
Java_org_chdb_internal_ChdbNative_connect(JNIEnv * env, jclass, jobjectArray argv)
{
    try
    {
        std::vector<std::string> args;
        if (!toBytesVector(env, argv, args))
            return 0;
        if (args.empty())
        {
            throwNative(env, "chdb_connect needs at least argv[0]");
            return 0;
        }

        std::vector<char *> raw;
        raw.reserve(args.size());
        for (auto & arg : args)
            raw.push_back(const_cast<char *>(arg.c_str()));

        chdb_connection * conn = nullptr;
        {
            // chdb_connect() re-runs chdb_reset_signal_handlers() whenever the opt-out flag
            // is set (chdb-core chdb.cpp lines 237 and 550), so the connect path needs the
            // same bracket as the opt-out call itself.
            SignalGuard guard;
            conn = chdb_connect(static_cast<int>(raw.size()), raw.data());
        }

        if (conn == nullptr || *conn == nullptr)
        {
            // chdb_connect() reports failure by returning NULL, with no message of its own, so
            // the causes have to be listed here. Ordered by how often they are the answer, and
            // deliberately not led by the storage-path conflict: the Java layer checks that
            // itself before calling, so by this point it has almost certainly been ruled out.
            //
            // The settings cause is first because the arguments are printed directly below it,
            // which makes it the one a reader can check without leaving the message. It is also
            // new: on engine 26.7.0 an invalid value for a known setting connected successfully
            // with the setting ignored, so it could not be the reason. On v26.7.2-rc.2 it is --
            // --max_threads=not-a-number, --max_threads=-5 and --max_memory_usage=abc all fail
            // the connect, which is what chdb.h documents. An unrecognized setting *name* is
            // still accepted and ignored, so it is not a cause.
            std::string message = "chdb_connect failed. The engine gives no reason of its own, so"
                                  " check, in this order:\n"
                                  "  - a setting below has a value the engine rejects: since"
                                  " engine 26.7.2-rc.2 an invalid value for a known setting fails"
                                  " the connect rather than being ignored\n"
                                  "  - the storage path exists as a directory, or can be created:"
                                  " a path naming an existing regular file fails here\n"
                                  "  - the process can read and write it\n"
                                  "  - there is disk space and there are free file descriptors\n"
                                  "  - no incompatible engine data is already at that path\n"
                                  "Arguments passed to the engine:";
            for (const auto & arg : args)
            {
                message += "\n  ";
                message += arg;
            }
            throwNative(env, message);
            return 0;
        }

        auto handle = std::make_shared<ConnHandle>();
        handle->conn = conn;
        return static_cast<jlong>(HandleRegistry::instance().insert(handle));
    }
    catch (const std::bad_alloc &)
    {
        throwOutOfMemory(env, "out of memory opening a chDB connection");
        return 0;
    }
    catch (const std::exception & e)
    {
        throwNative(env, std::string("chdb_connect failed: ") + e.what());
        return 0;
    }
    catch (...)
    {
        throwNative(env, "chdb_connect failed with an unknown native exception");
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_chdb_internal_ChdbNative_closeConnection(JNIEnv * env, jclass, jlong id)
{
    try
    {
        // Idempotent by construction: remove() returns null for an id that is already gone,
        // so a second close does nothing rather than double-freeing.
        auto base = HandleRegistry::instance().remove(id, kKindConnection);
        if (!base)
            return;
        auto handle = std::static_pointer_cast<ConnHandle>(base);
        std::lock_guard<std::mutex> lock(handle->mutex);
        handle->closeNow();
    }
    catch (const std::exception & e)
    {
        throwNative(env, std::string("failed to close the chDB connection: ") + e.what());
    }
    catch (...)
    {
        throwNative(env, "failed to close the chDB connection");
    }
}

// ====================================================================== non-streaming query

extern "C" JNIEXPORT jlong JNICALL
Java_org_chdb_internal_ChdbNative_query(
    JNIEnv * env,
    jclass,
    jlong connection_id,
    jbyteArray sql_bytes,
    jbyteArray format_bytes,
    jobjectArray param_name_bytes,
    jobjectArray param_value_bytes)
{
    try
    {
        auto connection = requireConnection(env, connection_id);
        if (!connection)
            return 0;

        std::string sql;
        std::string format;
        std::vector<std::string> names;
        std::vector<std::string> values;
        if (!toBytes(env, sql_bytes, sql) || !toBytes(env, format_bytes, format)
            || !toBytesVector(env, param_name_bytes, names)
            || !toBytesVector(env, param_value_bytes, values))
            return 0;

        if (names.size() != values.size())
        {
            throwNative(env, "parameter name and value arrays have different lengths");
            return 0;
        }

        chdb_result * result = nullptr;
        {
            std::lock_guard<std::mutex> lock(connection->mutex);
            if (connection->conn == nullptr)
            {
                throwNative(env, "the connection was closed while the query was starting");
                return 0;
            }
            chdb_connection conn = *connection->conn;
            if (names.empty())
            {
                result = chdb_query_n(conn, sql.data(), sql.size(), format.data(), format.size());
            }
            else
            {
                ParamArrays params = buildParams(names, values);
                result = chdb_query_with_params_n(
                    conn,
                    sql.data(),
                    sql.size(),
                    format.data(),
                    format.size(),
                    params.names.data(),
                    params.name_lengths.data(),
                    params.values.data(),
                    params.value_lengths.data(),
                    params.names.size());
            }
        }

        const std::string error = resultError(result);
        if (!error.empty())
        {
            // The result carries the message, so it has to be destroyed after reading it,
            // not before.
            chdb_destroy_query_result(result);
            throwNative(env, error);
            return 0;
        }
        if (result == nullptr)
        {
            throwNative(env, "chDB returned neither a result nor an error");
            return 0;
        }

        auto handle = std::make_shared<ResultHandle>();
        handle->result = result;
        return static_cast<jlong>(HandleRegistry::instance().insert(handle));
    }
    catch (const std::bad_alloc &)
    {
        throwOutOfMemory(env, "out of memory executing a chDB query");
        return 0;
    }
    catch (const std::exception & e)
    {
        throwNative(env, std::string("chDB query failed: ") + e.what());
        return 0;
    }
    catch (...)
    {
        throwNative(env, "chDB query failed with an unknown native exception");
        return 0;
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_chdb_internal_ChdbNative_resultBytes(JNIEnv * env, jclass, jlong id)
{
    try
    {
        auto handle = requireResult(env, id);
        if (!handle)
            return nullptr;
        std::lock_guard<std::mutex> lock(handle->mutex);
        if (handle->result == nullptr)
            return env->NewByteArray(0);

        const char * buffer = chdb_result_buffer(handle->result);
        const size_t length = chdb_result_length(handle->result);
        if (buffer == nullptr || length == 0)
            return env->NewByteArray(0);
        if (length > static_cast<size_t>(INT32_MAX))
        {
            throwNative(env, "result payload is " + std::to_string(length)
                             + " bytes, which does not fit in a Java array. Use a streaming "
                               "query for results this large.");
            return nullptr;
        }

        jbyteArray array = env->NewByteArray(static_cast<jsize>(length));
        if (array == nullptr)
            return nullptr;
        env->SetByteArrayRegion(
            array, 0, static_cast<jsize>(length), reinterpret_cast<const jbyte *>(buffer));
        return array;
    }
    catch (const std::exception & e)
    {
        throwNative(env, std::string("failed to read the result payload: ") + e.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT jdouble JNICALL
Java_org_chdb_internal_ChdbNative_resultElapsed(JNIEnv * env, jclass, jlong id)
{
    auto handle = requireResult(env, id);
    if (!handle)
        return 0.0;
    std::lock_guard<std::mutex> lock(handle->mutex);
    return handle->result == nullptr ? 0.0 : chdb_result_elapsed(handle->result);
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_chdb_internal_ChdbNative_resultRowsRead(JNIEnv * env, jclass, jlong id)
{
    auto handle = requireResult(env, id);
    if (!handle)
        return 0;
    std::lock_guard<std::mutex> lock(handle->mutex);
    return handle->result == nullptr ? 0 : static_cast<jlong>(chdb_result_rows_read(handle->result));
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_chdb_internal_ChdbNative_resultRowsWritten(JNIEnv * env, jclass, jlong id)
{
    auto handle = requireResult(env, id);
    if (!handle)
        return 0;
    std::lock_guard<std::mutex> lock(handle->mutex);
    return handle->result == nullptr ? 0 : static_cast<jlong>(chdb_result_rows_written(handle->result));
}

extern "C" JNIEXPORT void JNICALL
Java_org_chdb_internal_ChdbNative_destroyResult(JNIEnv * env, jclass, jlong id)
{
    try
    {
        auto base = HandleRegistry::instance().remove(id, kKindResult);
        if (!base)
            return;
        auto handle = std::static_pointer_cast<ResultHandle>(base);
        std::lock_guard<std::mutex> lock(handle->mutex);
        handle->closeNow();
    }
    catch (const std::exception & e)
    {
        throwNative(env, std::string("failed to destroy the chDB result: ") + e.what());
    }
}

extern "C" JNIEXPORT jintArray JNICALL
Java_org_chdb_internal_ChdbNative_classifyQuery(JNIEnv * env, jclass, jlong connection_id, jbyteArray sql_bytes)
{
    try
    {
        // Optional symbol: present from v26.7.2-rc.2, so on the pinned v26.7.3 baseline, absent
        // on anything older. Null tells the Java side to use its own statement-shape
        // heuristic rather than failing the query.
        const auto classify = optionalApi().classify_query_n;
        if (classify == nullptr)
            return nullptr;

        auto connection = requireConnection(env, connection_id);
        if (!connection)
            return nullptr;

        std::string sql;
        if (!toBytes(env, sql_bytes, sql))
            return nullptr;

        QueryAnalysisV1 analysis;
        std::memset(&analysis, 0, sizeof(analysis));
        analysis.struct_size = sizeof(analysis);

        chdb_state state;
        {
            std::lock_guard<std::mutex> lock(connection->mutex);
            if (connection->conn == nullptr)
            {
                throwNative(env, "the connection was closed before the statement could be classified");
                return nullptr;
            }
            state = classify(*connection->conn, sql.data(), sql.size(), nullptr, 0, &analysis);
        }

        if (state != CHDBSuccess)
        {
            throwNative(env, "chdb_classify_query_n failed");
            return nullptr;
        }

        jint values[3] = {
            static_cast<jint>(analysis.query_class),
            static_cast<jint>(analysis.statement_count),
            static_cast<jint>(analysis.flags),
        };
        jintArray result = env->NewIntArray(3);
        if (result == nullptr)
            return nullptr;
        env->SetIntArrayRegion(result, 0, 3, values);
        return result;
    }
    catch (const std::exception & e)
    {
        throwNative(env, std::string("failed to classify the statement: ") + e.what());
        return nullptr;
    }
}

// ====================================================================== streaming query

extern "C" JNIEXPORT jlong JNICALL
Java_org_chdb_internal_ChdbNative_streamOpen(
    JNIEnv * env,
    jclass,
    jlong connection_id,
    jbyteArray sql_bytes,
    jobjectArray param_name_bytes,
    jobjectArray param_value_bytes,
    jboolean low_cardinality_as_dictionary,
    jboolean unsupported_as_binary,
    jboolean string_as_string)
{
    try
    {
        auto connection = requireConnection(env, connection_id);
        if (!connection)
            return 0;

        std::string sql;
        std::vector<std::string> names;
        std::vector<std::string> values;
        if (!toBytes(env, sql_bytes, sql) || !toBytesVector(env, param_name_bytes, names)
            || !toBytesVector(env, param_value_bytes, values))
            return 0;
        if (names.size() != values.size())
        {
            throwNative(env, "parameter name and value arrays have different lengths");
            return 0;
        }

        chdb_arrow_options options;
        options.unsupported_as_binary = unsupported_as_binary == JNI_TRUE ? 1 : 0;
        options.low_cardinality_as_dictionary = low_cardinality_as_dictionary == JNI_TRUE ? 1 : 0;
        options.string_as_string = string_as_string == JNI_TRUE ? 1 : 0;

        chdb_result * stream = nullptr;
        {
            std::lock_guard<std::mutex> lock(connection->mutex);
            if (connection->conn == nullptr)
            {
                throwNative(env, "the connection was closed while the streaming query was starting");
                return 0;
            }
            chdb_connection conn = *connection->conn;
            if (names.empty())
            {
                stream = chdb_stream_query_arrow_n(conn, sql.data(), sql.size(), &options);
            }
            else
            {
                ParamArrays params = buildParams(names, values);
                stream = chdb_stream_query_arrow_with_params_n(
                    conn,
                    sql.data(),
                    sql.size(),
                    &options,
                    params.names.data(),
                    params.name_lengths.data(),
                    params.values.data(),
                    params.value_lengths.data(),
                    params.names.size());
            }
        }

        const std::string error = resultError(stream);
        if (!error.empty())
        {
            chdb_destroy_query_result(stream);
            throwNative(env, error);
            return 0;
        }
        if (stream == nullptr)
        {
            throwNative(env, "chDB returned neither a stream nor an error");
            return 0;
        }

        auto handle = std::make_shared<StreamHandle>();
        handle->source = BatchSource::kEngineStream;
        handle->stream = stream;
        handle->owner = connection;

        return primeAndRegister(env, handle);
    }
    catch (const std::bad_alloc &)
    {
        throwOutOfMemory(env, "out of memory opening a chDB streaming query");
        return 0;
    }
    catch (const std::exception & e)
    {
        throwNative(env, std::string("chDB streaming query failed: ") + e.what());
        return 0;
    }
    catch (...)
    {
        throwNative(env, "chDB streaming query failed with an unknown native exception");
        return 0;
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_chdb_internal_ChdbNative_streamOpenMaterialized(
    JNIEnv * env,
    jclass,
    jlong connection_id,
    jbyteArray sql_bytes,
    jboolean low_cardinality_as_dictionary,
    jboolean unsupported_as_binary,
    jboolean string_as_string)
{
    try
    {
        auto connection = requireConnection(env, connection_id);
        if (!connection)
            return 0;

        std::string sql;
        if (!toBytes(env, sql_bytes, sql))
            return 0;

        chdb_arrow_options options;
        options.unsupported_as_binary = unsupported_as_binary == JNI_TRUE ? 1 : 0;
        options.low_cardinality_as_dictionary = low_cardinality_as_dictionary == JNI_TRUE ? 1 : 0;
        options.string_as_string = string_as_string == JNI_TRUE ? 1 : 0;

        // Built before the call because chdb_query_arrow_n writes straight into the handle's
        // ArrowArrayStream, and that memory has to already have an owner if the call fails
        // partway: the handle's destructor is what releases it.
        auto handle = std::make_shared<StreamHandle>();
        handle->source = BatchSource::kMaterializedArrow;
        handle->owner = connection;

        chdb_result * metrics = nullptr;
        {
            std::lock_guard<std::mutex> lock(connection->mutex);
            if (connection->conn == nullptr)
            {
                throwNative(env, "the connection was closed while the query was starting");
                return 0;
            }
            metrics = chdb_query_arrow_n(
                *connection->conn,
                sql.data(),
                sql.size(),
                reinterpret_cast<chdb_arrow_stream>(&handle->array_stream),
                &options);
        }

        // The metrics object and the exported stream have independent lifetimes -- the rows
        // are kept alive by the stream's private_data, not by the result -- so the result is
        // read for its error text and destroyed here. Nothing above this needs elapsed or
        // rows_read: an update count comes from chdb_query_n, and a result set reports its
        // rows by being iterated.
        const std::string error = resultError(metrics);
        chdb_destroy_query_result(metrics);
        if (!error.empty())
        {
            throwNative(env, error);
            return 0;
        }

        if (handle->array_stream.release == nullptr)
        {
            throwNative(env, "chDB reported no error but exported no Arrow stream for the "
                             "statement; the engine's Arrow output is inconsistent");
            return 0;
        }

        return primeAndRegister(env, handle);
    }
    catch (const std::bad_alloc &)
    {
        throwOutOfMemory(env, "out of memory executing a chDB Arrow query");
        return 0;
    }
    catch (const std::exception & e)
    {
        throwNative(env, std::string("chDB Arrow query failed: ") + e.what());
        return 0;
    }
    catch (...)
    {
        throwNative(env, "chDB Arrow query failed with an unknown native exception");
        return 0;
    }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_chdb_internal_ChdbNative_streamColumnNames(JNIEnv * env, jclass, jlong id)
{
    auto handle = requireStream(env, id);
    if (!handle)
        return nullptr;
    std::lock_guard<std::mutex> lock(handle->mutex);
    return toStringArray(env, handle->column_names);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_chdb_internal_ChdbNative_streamColumnFormats(JNIEnv * env, jclass, jlong id)
{
    auto handle = requireStream(env, id);
    if (!handle)
        return nullptr;
    std::lock_guard<std::mutex> lock(handle->mutex);
    return toStringArray(env, handle->column_formats);
}

extern "C" JNIEXPORT jbooleanArray JNICALL
Java_org_chdb_internal_ChdbNative_streamColumnNullable(JNIEnv * env, jclass, jlong id)
{
    auto handle = requireStream(env, id);
    if (!handle)
        return nullptr;
    std::lock_guard<std::mutex> lock(handle->mutex);

    jbooleanArray result = env->NewBooleanArray(static_cast<jsize>(handle->column_nullable.size()));
    if (result == nullptr)
        return nullptr;
    if (!handle->column_nullable.empty())
        env->SetBooleanArrayRegion(
            result,
            0,
            static_cast<jsize>(handle->column_nullable.size()),
            reinterpret_cast<const jboolean *>(handle->column_nullable.data()));
    return result;
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_chdb_internal_ChdbNative_streamAdvance(JNIEnv * env, jclass, jlong connection_id, jlong stream_id)
{
    try
    {
        auto handle = requireStream(env, stream_id);
        if (!handle)
            return -1;

        std::lock_guard<std::mutex> lock(handle->mutex);
        if (handle->closed || !handle->hasProducer())
        {
            throwNative(env, "the stream is closed");
            return -1;
        }

        // Owner check (work plan section 5.5): a stream may only be driven by the
        // connection it was opened on.
        //
        // The two ways this fails are told apart, because they are not the same finding and
        // the caller can only act on one of them. An id that is absent from the registry is a
        // connection that has been closed -- which the caller may not have done itself, since
        // Connection.abort() is defined to be called from another thread and a pool being shut
        // down calls it on connections it has lent out. Reporting that as "does not belong"
        // described a mix-up
        // of two live connections that had not happened, in a sentence about handle numbers
        // the caller has never seen. A live id that is a different connection is the real
        // ownership violation, and only then is the ownership message the answer.
        if (!handle->owner)
        {
            throwNative(env, "the stream has no connection to read from; it was closed");
            return -1;
        }
        auto connection = HandleRegistry::instance().get(connection_id, kKindConnection);
        if (!connection)
        {
            throwNative(env, "the connection this stream was opened on (handle "
                             + std::to_string(connection_id) + ") has been closed, so the rest of "
                             "the result cannot be read");
            return -1;
        }
        if (connection != handle->owner)
        {
            throwNative(env, "stream handle " + std::to_string(stream_id)
                             + " does not belong to connection handle " + std::to_string(connection_id));
            return -1;
        }

        if (handle->cancelled)
        {
            throwNative(env, "the query was cancelled");
            return -1;
        }

        // Releasing before fetching is what bounds memory: exactly one batch is live at a
        // time. Java has already dropped its buffer references by the time it calls this.
        handle->releaseCurrent();

        if (handle->has_pending)
        {
            handle->current = handle->pending;
            std::memset(&handle->pending, 0, sizeof(handle->pending));
            handle->has_pending = false;
            handle->has_current = true;
            return static_cast<jlong>(handle->current.length);
        }

        if (handle->eos)
            return -1;

        ArrowArray next;
        if (!fetchBatch(env, *handle, &next, /*want_schema=*/false))
            return -1;

        if (next.release == nullptr)
        {
            handle->eos = true;
            return -1;
        }

        handle->current = next;
        handle->has_current = true;
        return static_cast<jlong>(handle->current.length);
    }
    catch (const std::exception & e)
    {
        throwNative(env, std::string("failed to advance the chDB stream: ") + e.what());
        return -1;
    }
    catch (...)
    {
        throwNative(env, "failed to advance the chDB stream");
        return -1;
    }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_chdb_internal_ChdbNative_streamBatchColumns(JNIEnv * env, jclass, jlong id)
{
    try
    {
        auto handle = requireStream(env, id);
        if (!handle)
            return nullptr;

        std::lock_guard<std::mutex> lock(handle->mutex);
        if (!handle->has_current)
        {
            throwNative(env, "there is no current batch; call streamAdvance first");
            return nullptr;
        }

        const ArrowArray & batch = handle->current;
        const size_t columns = handle->column_formats.size();
        if (batch.n_children < static_cast<int64_t>(columns) || batch.children == nullptr)
        {
            throwNative(env, "the Arrow batch has " + std::to_string(batch.n_children)
                             + " children but the schema declares " + std::to_string(columns)
                             + " columns");
            return nullptr;
        }

        jclass object_class = env->FindClass("java/lang/Object");
        if (object_class == nullptr)
            return nullptr;
        jobjectArray result = env->NewObjectArray(static_cast<jsize>(columns), object_class, nullptr);
        if (result == nullptr)
        {
            env->DeleteLocalRef(object_class);
            return nullptr;
        }

        for (size_t i = 0; i < columns; ++i)
        {
            const ArrowArray * column = batch.children[i];
            if (column == nullptr)
            {
                env->DeleteLocalRef(object_class);
                throwNative(env, "the Arrow batch has a null child at index " + std::to_string(i));
                return nullptr;
            }

            // A record batch is exported as a struct array. The logical position of row r
            // in a child is child->offset + batch.offset + r, so the two offsets are folded
            // together here and Java only ever sees one.
            const int64_t offset = column->offset + batch.offset;
            const int64_t length = column->length;

            jlongArray meta = env->NewLongArray(3);
            if (meta == nullptr)
            {
                env->DeleteLocalRef(object_class);
                return nullptr;
            }
            const jlong meta_values[3] = {
                static_cast<jlong>(length),
                static_cast<jlong>(offset),
                static_cast<jlong>(column->null_count),
            };
            env->SetLongArrayRegion(meta, 0, 3, meta_values);

            jobject validity = nullptr;
            jobject buffer1 = nullptr;
            jobject buffer2 = nullptr;

            const ArrowColumnLayout & layout = handle->column_layouts[i];
            const int64_t buffer_count = column->n_buffers;
            const void ** buffers = column->buffers;

            if (buffers != nullptr && buffer_count >= 1)
                validity = wrapBuffer(env, buffers[0], validityBytes(offset, length));

            switch (layout.layout)
            {
                case ArrowLayout::kBitmap:
                    if (buffers != nullptr && buffer_count >= 2)
                        buffer1 = wrapBuffer(env, buffers[1], validityBytes(offset, length));
                    break;

                case ArrowLayout::kFixedWidth:
                    if (buffers != nullptr && buffer_count >= 2)
                        buffer1 = wrapBuffer(
                            env, buffers[1], (offset + length) * layout.element_bytes);
                    break;

                case ArrowLayout::kVarBinary32:
                case ArrowLayout::kVarBinary64:
                {
                    if (buffers == nullptr || buffer_count < 3)
                        break;
                    const bool wide = layout.layout == ArrowLayout::kVarBinary64;
                    const int64_t index_bytes = wide ? 8 : 4;
                    // Offsets hold one more entry than there are slots: the end of the last
                    // value.
                    const int64_t offsets_bytes = (offset + length + 1) * index_bytes;
                    buffer1 = wrapBuffer(env, buffers[1], offsets_bytes);
                    // The Arrow C ABI carries no buffer sizes, so the data buffer's length
                    // is whatever the last offset says it is.
                    int64_t data_bytes = 0;
                    if (buffers[1] != nullptr && (offset + length) >= 0)
                    {
                        if (wide)
                            data_bytes = reinterpret_cast<const int64_t *>(buffers[1])[offset + length];
                        else
                            data_bytes = reinterpret_cast<const int32_t *>(buffers[1])[offset + length];
                    }
                    buffer2 = wrapBuffer(env, buffers[2], data_bytes);
                    break;
                }

                case ArrowLayout::kUnsupported:
                    // Deliberately hands over no data buffers. Java raises a typed
                    // "unsupported column type" error naming the column and its format
                    // rather than reading buffers it cannot interpret.
                    break;
            }

            jobjectArray entry = env->NewObjectArray(4, object_class, nullptr);
            if (entry == nullptr)
            {
                env->DeleteLocalRef(object_class);
                return nullptr;
            }
            env->SetObjectArrayElement(entry, 0, meta);
            env->SetObjectArrayElement(entry, 1, validity);
            env->SetObjectArrayElement(entry, 2, buffer1);
            env->SetObjectArrayElement(entry, 3, buffer2);
            env->SetObjectArrayElement(result, static_cast<jsize>(i), entry);

            env->DeleteLocalRef(meta);
            if (validity != nullptr)
                env->DeleteLocalRef(validity);
            if (buffer1 != nullptr)
                env->DeleteLocalRef(buffer1);
            if (buffer2 != nullptr)
                env->DeleteLocalRef(buffer2);
            env->DeleteLocalRef(entry);
        }

        env->DeleteLocalRef(object_class);
        return result;
    }
    catch (const std::exception & e)
    {
        throwNative(env, std::string("failed to expose the Arrow batch: ") + e.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_chdb_internal_ChdbNative_streamCancel(JNIEnv * env, jclass, jlong connection_id, jlong stream_id)
{
    try
    {
        // A cancel is a request to stop something, so anything already stopped is success, not
        // an error -- including a stream or a connection this registry no longer has. That is
        // sound here and nowhere else because ids are never reused within a process (see
        // HandleRegistry and "ids are not reused" in the shim's tests): an absent id can only
        // mean "that one is gone", never "that one is now somebody else's", so ignoring it
        // cannot cancel the wrong query. Rejecting it instead is what a caller cannot use --
        // Statement.cancel() may be called from another thread, so "it finished a moment
        // before you asked" is an ordinary outcome of the race the method exists for, and
        // reporting it as a failure is what got a pooled connection evicted mid-query.
        auto base = HandleRegistry::instance().get(stream_id, kKindStream);
        if (!base)
            return;
        auto handle = std::static_pointer_cast<StreamHandle>(base);

        auto connection = HandleRegistry::instance().get(connection_id, kKindConnection);
        if (!connection)
            return;  // The connection is closed, which closed the query too.
        if (connection != handle->owner)
        {
            // A live connection that is not this stream's: a genuine ownership violation, and
            // the only case the message is about.
            throwNative(env, "stream handle " + std::to_string(stream_id)
                             + " does not belong to connection handle " + std::to_string(connection_id));
            return;
        }

        std::lock_guard<std::mutex> lock(handle->mutex);
        if (handle->closed || !handle->hasProducer() || !handle->owner)
            return;  // Already finished or torn down: cancel is a no-op, not an error.

        if (handle->cancelled)
            return;  // Idempotent: cancelling twice must not reach the engine twice.

        if (handle->source == BatchSource::kMaterializedArrow)
        {
            // There is no engine-side query left to interrupt: chdb_query_arrow_n ran the
            // statement to completion before this handle existed, and what remains is a
            // buffer to copy out of. chdb_stream_cancel_query must not be reached with it --
            // it reinterpret_casts its argument to StreamQueryResult, which this is not.
            //
            // The flag is still set, so the next advance reports the cancel rather than
            // handing out the rest of the rows. That is the honest answer for a caller that
            // asked to stop: it distinguishes "you told me to stop" from "there was no more
            // data", which is the same reason the streaming path keeps the flag.
            handle->cancelled = true;
            return;
        }

        // The connection lock, inside the stream's, held across the engine call -- the same
        // reason as in fetchBatch: chdb_close_conn() frees what handle->owner->conn points at,
        // so testing it and then dereferencing it without the lock is a use-after-free rather
        // than only a data race on a non-atomic member. Lock order is in chdb_jni_handles.h.
        //
        // Taking it here does not make cancel wait for the query it is meant to interrupt:
        // the open paths hold the connection lock only for the call that starts the query, and
        // fetchBatch holds it only for one batch.
        std::lock_guard<std::mutex> connection_lock(handle->owner->mutex);
        if (handle->owner->conn == nullptr)
            return;  // Closed underneath us: nothing to cancel, and not an error.

        chdb_stream_cancel_query(*handle->owner->conn, handle->stream);
        handle->cancelled = true;
    }
    catch (const std::exception & e)
    {
        throwNative(env, std::string("failed to cancel the chDB stream: ") + e.what());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_chdb_internal_ChdbNative_streamClose(JNIEnv * env, jclass, jlong id)
{
    try
    {
        auto base = HandleRegistry::instance().remove(id, kKindStream);
        if (!base)
            return;
        auto handle = std::static_pointer_cast<StreamHandle>(base);
        std::lock_guard<std::mutex> lock(handle->mutex);
        handle->closeNow();
    }
    catch (const std::exception & e)
    {
        throwNative(env, std::string("failed to close the chDB stream: ") + e.what());
    }
}

// ====================================================================== diagnostics

extern "C" JNIEXPORT jlong JNICALL
Java_org_chdb_internal_ChdbNative_openHandleCount(JNIEnv *, jclass, jint kind)
{
    return static_cast<jlong>(HandleRegistry::instance().openCount(static_cast<int32_t>(kind)));
}

extern "C" JNIEXPORT jint JNICALL
Java_org_chdb_internal_ChdbNative_shutdown(JNIEnv * env, jclass)
{
    try
    {
        // Optional symbol, like the classifier. 2 means "this engine has no chdb_shutdown",
        // which is not a failure: skipping it is as safe as it always was for a process
        // that simply exits.
        const auto shutdown_fn = optionalApi().shutdown;
        if (shutdown_fn == nullptr)
            return 2;
        return shutdown_fn() == CHDBSuccess ? 0 : 1;
    }
    catch (const std::exception & e)
    {
        throwNative(env, std::string("chdb_shutdown failed: ") + e.what());
        return 1;
    }
}
