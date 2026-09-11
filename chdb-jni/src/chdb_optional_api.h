// chDB C API entry points the shim resolves at runtime rather than linking.
//
// The baseline is chdb-core v26.7.3 (work plan section 2.1). Both of these arrived in
// v26.7.2-rc.2 -- neither is in v26.7.0 or v26.7.1-rc.1, whose export lists and headers have
// neither name -- and both are exported by the current baseline, checked in its release
// library rather than in an export list:
//
//   chdb_classify_query_n   since v26.7.2-rc.2  says whether a statement has a result set,
//                                               using the engine's own parser
//   chdb_shutdown           since v26.7.2-rc.2  joins every engine thread before host teardown
//
// They stay here, resolved with dlsym and absent-but-fine, rather than moving to the linked
// set now that the baseline has them. Work plan section 5.1 asks for the C API to be split
// into required and optional symbols, and what makes these optional is not the baseline: a
// user can point the loader at another libchdb of the same version, and an absent symbol has
// to degrade rather than fail the load. The required half is everything chdb_jni.cpp calls
// directly, which the linker checks at build time and the loader re-checks at startup.
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

// Mirrors chdb_query_analysis_v1. Declared here rather than used from chdb.h, which does now
// carry it, because this side has to keep working against an engine whose header it was not
// built from: the struct is size-versioned by its own first field, so a newer engine fills
// only the fields this definition has room for. Keep the two in step.
struct QueryAnalysisV1
{
    uint32_t struct_size;
    uint32_t statement_count;
    uint32_t flags;
    uint32_t query_class;
};

// chdb_query_class values, as of v26.7.3. Kept in sync with ChdbNative.classifyQuery's
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
