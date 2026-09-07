// chDB C API entry points that the pinned baseline engine does not have.
//
// The V1 baseline is chdb-core v26.7.0 (work plan section 2.1). Two calls the shim would
// like to use landed after it:
//
//   chdb_classify_query_n   v26.7.1-rc.1  says whether a statement has a result set,
//                                         using the engine's own parser
//   chdb_shutdown           v26.7.1-rc.1  joins every engine thread before host teardown
//
// Work plan section 5.1 asks for the C API to be split into required and optional symbols.
// These are the optional half: resolved with dlsym at load time, and absent-but-fine. The
// required half is everything chdb_jni.cpp calls directly, which the linker checks at
// build time and the loader re-checks at startup.
//
// Resolution uses RTLD_DEFAULT rather than a dlopen handle. By the time any of this runs
// the Java loader has already System.load()-ed libchdb into the process, so its symbols
// are in the global namespace and there is no second handle to keep in sync.

#pragma once

#include <cstddef>
#include <cstdint>

extern "C" {
#include "chdb.h"
}

namespace chdb_jni
{

// Mirrors chdb_query_analysis_v1 from a post-baseline chdb.h. Declared here because the
// pinned header does not have it. The struct is size-versioned by its own first field, so
// a newer engine fills only the fields this definition has room for -- which is what makes
// declaring it on this side safe rather than a guess about the engine's layout.
struct QueryAnalysisV1
{
    uint32_t struct_size;
    uint32_t statement_count;
    uint32_t flags;
    uint32_t query_class;
};

// chdb_query_class values, as of v26.7.1-rc.1. Kept in sync with ChdbNative.classifyQuery's
// documented return contract.
enum QueryClass : uint32_t
{
    kQueryReadOnly = 0,
    kQueryMutating = 1,
    kQueryMutatingGlobal = 2,
    kQueryControl = 3,
    kQueryUnknown = 4,
};

struct OptionalApi
{
    chdb_state (*classify_query_n)(
        chdb_connection conn,
        const char * sql,
        size_t sql_len,
        const char * target_database,
        size_t target_database_len,
        QueryAnalysisV1 * out_analysis)
        = nullptr;

    chdb_state (*shutdown)() = nullptr;
};

// Resolved once, on first use. Safe to call from any thread.
const OptionalApi & optionalApi();

}  // namespace chdb_jni
