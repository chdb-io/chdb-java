#include "chdb_jni_arrow.h"

#include <cstdlib>

namespace chdb_jni
{

namespace
{

ArrowColumnLayout fixedWidth(int32_t bytes)
{
    ArrowColumnLayout layout;
    layout.layout = ArrowLayout::kFixedWidth;
    layout.element_bytes = bytes;
    return layout;
}

ArrowColumnLayout simple(ArrowLayout kind)
{
    ArrowColumnLayout layout;
    layout.layout = kind;
    return layout;
}

}  // namespace

ArrowColumnLayout parseArrowFormat(const std::string & format)
{
    if (format.empty())
        return simple(ArrowLayout::kUnsupported);

    // Single-character primitives.
    if (format.size() == 1)
    {
        switch (format[0])
        {
            case 'b':
                return simple(ArrowLayout::kBitmap);
            case 'c':  // int8
            case 'C':  // uint8
                return fixedWidth(1);
            case 's':  // int16
            case 'S':  // uint16
            case 'e':  // float16
                return fixedWidth(2);
            case 'i':  // int32
            case 'I':  // uint32
            case 'f':  // float32
                return fixedWidth(4);
            case 'l':  // int64
            case 'L':  // uint64
            case 'g':  // float64
                return fixedWidth(8);
            case 'u':  // utf8
            case 'z':  // binary
                return simple(ArrowLayout::kVarBinary32);
            case 'U':  // large_utf8
            case 'Z':  // large_binary
                return simple(ArrowLayout::kVarBinary64);
            default:
                return simple(ArrowLayout::kUnsupported);
        }
    }

    // decimal: "d:precision,scale" is decimal128, "d:precision,scale,bitwidth" names the
    // width explicitly. ClickHouse Decimal256 arrives as the three-field form.
    if (format.rfind("d:", 0) == 0)
    {
        const size_t second_comma = format.find(',', format.find(',') + 1);
        if (second_comma == std::string::npos)
            return fixedWidth(16);
        const int bits = std::atoi(format.c_str() + second_comma + 1);
        if (bits == 256)
            return fixedWidth(32);
        if (bits == 128)
            return fixedWidth(16);
        return simple(ArrowLayout::kUnsupported);
    }

    // fixed_size_binary: "w:<byte width>". ClickHouse FixedString(N) and, with
    // output_format_arrow_fixed_string_as_fixed_byte_array, UUID as w:16.
    if (format.rfind("w:", 0) == 0)
    {
        const int width = std::atoi(format.c_str() + 2);
        if (width <= 0)
            return simple(ArrowLayout::kUnsupported);
        return fixedWidth(width);
    }

    // Temporal types. The unit and timezone matter to Java, not to the buffer layout;
    // only the storage width does.
    if (format.rfind("td", 0) == 0)
    {
        if (format == "tdD")  // date32, days
            return fixedWidth(4);
        if (format == "tdm")  // date64, milliseconds
            return fixedWidth(8);
        return simple(ArrowLayout::kUnsupported);
    }
    if (format.rfind("ts", 0) == 0)  // timestamp: tss:/tsm:/tsu:/tsn: with optional tz
        return fixedWidth(8);
    if (format.rfind("tt", 0) == 0)  // time
    {
        if (format == "tts" || format == "ttm")
            return fixedWidth(4);
        if (format == "ttu" || format == "ttn")
            return fixedWidth(8);
        return simple(ArrowLayout::kUnsupported);
    }
    if (format == "tDs" || format == "tDm" || format == "tDu" || format == "tDn")  // duration
        return fixedWidth(8);

    // Nested, dictionary, union, interval, run-end encoded: not in V1's flat path.
    return simple(ArrowLayout::kUnsupported);
}

}  // namespace chdb_jni
