// Arrow C Data Interface and C Stream Interface struct definitions.
//
// Copied verbatim from the Apache Arrow specification (arrow/c/abi.h, Apache-2.0).
// Vendored on purpose: these three structs are the entire ABI contract chDB's
// chdb_query_arrow / chdb_stream_fetch_arrow family speaks, and vendoring them keeps
// the JNI shim free of any Arrow C++ or Arrow Java dependency (work plan section 3.2).
//
// Do not extend this file with helpers. It is a transcription of a frozen ABI.

#pragma once

#include <cstdint>

extern "C" {

#define ARROW_FLAG_DICTIONARY_ORDERED 1
#define ARROW_FLAG_NULLABLE 2
#define ARROW_FLAG_MAP_KEYS_SORTED 4

struct ArrowSchema
{
    // Array type description
    const char * format;
    const char * name;
    const char * metadata;
    int64_t flags;
    int64_t n_children;
    struct ArrowSchema ** children;
    struct ArrowSchema * dictionary;

    // Release callback
    void (*release)(struct ArrowSchema *);
    // Opaque producer-specific data
    void * private_data;
};

struct ArrowArray
{
    // Array data description
    int64_t length;
    int64_t null_count;
    int64_t offset;
    int64_t n_buffers;
    int64_t n_children;
    const void ** buffers;
    struct ArrowArray ** children;
    struct ArrowArray * dictionary;

    // Release callback
    void (*release)(struct ArrowArray *);
    // Opaque producer-specific data
    void * private_data;
};

struct ArrowArrayStream
{
    // Callback to get the stream type (will be the same for all arrays in the stream).
    // Return value: 0 if successful, an `errno`-compatible error code otherwise.
    int (*get_schema)(struct ArrowArrayStream *, struct ArrowSchema * out);

    // Callback to get the next array.
    // Return value: 0 if successful, an `errno`-compatible error code otherwise.
    // On end of stream, `out` is marked released (out->release == NULL).
    int (*get_next)(struct ArrowArrayStream *, struct ArrowArray * out);

    // Callback to get optional detailed error information.
    const char * (*get_last_error)(struct ArrowArrayStream *);

    // Release callback
    void (*release)(struct ArrowArrayStream *);
    // Opaque producer-specific data
    void * private_data;
};

}  // extern "C"
