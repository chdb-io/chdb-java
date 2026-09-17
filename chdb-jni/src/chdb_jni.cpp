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










// ====================================================================== RowBinary streaming
//
// The type-carrying path. Where the Arrow entry points above hand over columnar buffers whose
// Arrow types are a lossy projection of ClickHouse's -- Enum8 arriving as Int8 with its labels
// gone, Int128 and IPv6 and UUID all arriving as sixteen bytes of fixed-size binary -- these
// hand over the bytes of a RowBinaryWithNamesAndTypes stream, whose header carries every type
// as the engine declared it. All of the decoding is on the Java side; the shim only moves
// bytes and owns the engine handles.

namespace
{

struct RowBinaryHandle : HandleBase
{
    RowBinaryHandle() : HandleBase(kKindRowBinary) { }

    ~RowBinaryHandle() override { closeNow(); }

    void closeNow()
    {
        if (stream != nullptr)
        {
            chdb_destroy_query_result(stream);
            stream = nullptr;
        }
        finished = true;
    }

    std::mutex mutex;
    chdb_result * stream = nullptr;
    bool finished = false;
    std::shared_ptr<ConnHandle> owner;
};

std::shared_ptr<RowBinaryHandle> requireRowBinary(JNIEnv * env, jlong id)
{
    auto base = HandleRegistry::instance().get(id, kKindRowBinary);
    if (!base)
    {
        throwNative(env, "RowBinary stream handle " + std::to_string(id) + " is not open (it was "
                    "closed, or it belongs to a different kind of object)");
        return nullptr;
    }
    return std::static_pointer_cast<RowBinaryHandle>(base);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_org_chdb_internal_ChdbNative_rowBinaryOpen(
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

        chdb_result * stream = nullptr;
        {
            std::lock_guard<std::mutex> lock(connection->mutex);
            if (connection->conn == nullptr)
            {
                throwNative(env, "the connection was closed while the streaming query was starting");
                return 0;
            }
            chdb_connection conn = *connection->conn;
            // Always the _with_params_n form, even with no parameters: it is the only variant
            // that takes explicit lengths for both the query and the format, and a query is
            // allowed to contain a NUL byte.
            ParamArrays params = buildParams(names, values);
            stream = chdb_stream_query_with_params_n(
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

        auto handle = std::make_shared<RowBinaryHandle>();
        handle->stream = stream;
        handle->owner = connection;
        return static_cast<jlong>(HandleRegistry::instance().insert(handle));
    }
    catch (const std::exception & e)
    {
        throwNative(env, std::string("failed to start the RowBinary stream: ") + e.what());
        return 0;
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_chdb_internal_ChdbNative_rowBinaryFetch(
    JNIEnv * env, jclass, jlong connection_id, jlong id)
{
    try
    {
        auto handle = requireRowBinary(env, id);
        if (!handle)
            return nullptr;
        auto connection = requireConnection(env, connection_id);
        if (!connection)
            return nullptr;

        std::string bytes;
        {
            // Lock order: the stream's mutex before the connection's, as declared in
            // chdb_jni_handles.h.
            std::lock_guard<std::mutex> stream_lock(handle->mutex);
            if (handle->finished || handle->stream == nullptr)
                return nullptr;

            std::lock_guard<std::mutex> conn_lock(connection->mutex);
            if (connection->conn == nullptr)
            {
                throwNative(env, "the connection was closed while the stream was being read");
                return nullptr;
            }

            chdb_result * chunk = chdb_stream_fetch_result(*connection->conn, handle->stream);
            const std::string error = resultError(chunk);
            if (!error.empty())
            {
                chdb_destroy_query_result(chunk);
                handle->finished = true;
                throwNative(env, error);
                return nullptr;
            }
            const char * buffer = chunk == nullptr ? nullptr : chdb_result_buffer(chunk);
            const size_t length = chunk == nullptr ? 0 : chdb_result_length(chunk);
            if (buffer == nullptr || length == 0)
            {
                // An empty chunk is how the stream says it is done.
                chdb_destroy_query_result(chunk);
                handle->finished = true;
                return nullptr;
            }
            // Copied out before the chunk is destroyed: the buffer belongs to it, and holding
            // the pointer past the destroy would be a use-after-free the JVM cannot catch.
            bytes.assign(buffer, length);
            chdb_destroy_query_result(chunk);
        }

        jbyteArray out = env->NewByteArray(static_cast<jsize>(bytes.size()));
        if (out == nullptr)
            return nullptr;
        env->SetByteArrayRegion(
            out, 0, static_cast<jsize>(bytes.size()), reinterpret_cast<const jbyte *>(bytes.data()));
        return out;
    }
    catch (const std::exception & e)
    {
        throwNative(env, std::string("failed to fetch from the RowBinary stream: ") + e.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_chdb_internal_ChdbNative_rowBinaryCancel(
    JNIEnv * env, jclass, jlong connection_id, jlong id)
{
    try
    {
        auto handle = requireRowBinary(env, id);
        if (!handle)
            return;
        auto connection = requireConnection(env, connection_id);
        if (!connection)
            return;

        std::lock_guard<std::mutex> stream_lock(handle->mutex);
        if (handle->finished || handle->stream == nullptr)
            return;
        std::lock_guard<std::mutex> conn_lock(connection->mutex);
        if (connection->conn == nullptr)
            return;
        chdb_stream_cancel_query(*connection->conn, handle->stream);
        handle->finished = true;
    }
    catch (const std::exception & e)
    {
        throwNative(env, std::string("failed to cancel the RowBinary stream: ") + e.what());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_chdb_internal_ChdbNative_rowBinaryClose(JNIEnv * env, jclass, jlong id)
{
    try
    {
        auto base = HandleRegistry::instance().remove(id, kKindRowBinary);
        if (!base)
            return;
        auto handle = std::static_pointer_cast<RowBinaryHandle>(base);
        std::lock_guard<std::mutex> lock(handle->mutex);
        handle->closeNow();
    }
    catch (const std::exception & e)
    {
        throwNative(env, std::string("failed to close the RowBinary stream: ") + e.what());
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
