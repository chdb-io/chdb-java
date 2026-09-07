// Buffer layout for one Arrow column, derived from its C Data Interface format string.
//
// The Arrow C ABI deliberately carries no buffer lengths: a consumer is expected to know
// the layout of the type it asked for. The JNI shim has to know them anyway, because
// JNI's NewDirectByteBuffer takes a capacity, and handing Java a buffer longer than the
// producer's allocation would turn an out-of-range read into a segfault instead of an
// IndexOutOfBoundsException.
//
// So the format string is parsed here rather than only on the Java side. The shim decides
// how many bytes each buffer holds; Java decides what the values mean (decimal scale,
// timestamp unit and timezone, UUID vs fixed-size binary), reading the same format string.

#pragma once

#include <cstdint>
#include <string>

namespace chdb_jni
{

enum class ArrowLayout
{
    // Not representable in V1's flat data path: nested (+s, +l, +m), dictionary-encoded,
    // union, interval. The shim still reports the column so the Java side can raise a
    // precise "unsupported type" error naming it, rather than mis-slicing its buffers.
    kUnsupported,
    // validity bitmap + a bit-packed data buffer. Boolean only.
    kBitmap,
    // validity bitmap + a fixed-width data buffer of `element_bytes` per slot.
    kFixedWidth,
    // validity bitmap + int32 offsets + a variable-length data buffer.
    kVarBinary32,
    // validity bitmap + int64 offsets + a variable-length data buffer.
    kVarBinary64,
};

struct ArrowColumnLayout
{
    ArrowLayout layout = ArrowLayout::kUnsupported;
    // Bytes per element for kFixedWidth; ignored otherwise.
    int32_t element_bytes = 0;
};

// Parses an Arrow C Data Interface format string into a buffer layout.
// Unrecognized formats come back as kUnsupported rather than as an error: reporting the
// column and refusing to read it beats failing the whole result set.
ArrowColumnLayout parseArrowFormat(const std::string & format);

}  // namespace chdb_jni
