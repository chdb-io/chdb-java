#include "chdb_optional_api.h"

#include <dlfcn.h>

namespace chdb_jni
{

namespace
{

template <typename Fn>
Fn resolve(const char * name)
{
    // RTLD_DEFAULT searches everything already loaded, which includes libchdb: the Java
    // loader System.load()-s it before the shim, so its symbols are global by the time
    // anything here runs.
    return reinterpret_cast<Fn>(dlsym(RTLD_DEFAULT, name));
}

OptionalApi resolveAll()
{
    OptionalApi api;
    api.classify_query_n = resolve<decltype(OptionalApi::classify_query_n)>("chdb_classify_query_n");
    api.shutdown = resolve<decltype(OptionalApi::shutdown)>("chdb_shutdown");
    return api;
}

}  // namespace

const OptionalApi & optionalApi()
{
    // Function-local static: the C++11 guarantee makes initialization thread-safe without
    // a lock of our own, and it happens on first use rather than at library load, so
    // resolution cannot race libchdb still being loaded.
    static const OptionalApi api = resolveAll();
    return api;
}

}  // namespace chdb_jni
