#include "chdb_jni_arrow.h"

#include <cstdlib>

namespace chdb_jni
{

namespace
{

// Strict decimal parse: the whole field must be digits, with an optional leading sign.
//
// std::atoi is not usable here. It returns 0 for "a" and for "", so "d:a,b" would be read as
// precision 0 and accepted, while ArrowFieldType.parse on the Java side rejects it. The two
// parsers have to agree about which formats are supported: this one decides how many bytes of
// each buffer Java may see, and that one decides what the bytes mean. A format one accepts and
// the other does not is a latent inconsistency even when it happens to be harmless.
bool parseWholeInt(const std::string & text, int64_t & out)
{
    if (text.empty())
        return false;
    size_t i = 0;
    bool negative = false;
    if (text[0] == '+' || text[0] == '-')
    {
        negative = text[0] == '-';
        i = 1;
        if (i == text.size())
            return false;
    }
    int64_t value = 0;
    for (; i < text.size(); ++i)
    {
        const char c = text[i];
        if (c < '0' || c > '9')
            return false;
        // Bail out rather than overflow; no Arrow field legitimately needs more than this.
        if (value > 1000000000LL)
            return false;
        value = value * 10 + (c - '0');
    }
    out = negative ? -value : value;
    return true;
}

// Splits on ',' without allocating a vector of substrings per call.
size_t splitFields(const std::string & text, std::string (&fields)[3])
{
    size_t count = 0;
    size_t start = 0;
    while (count < 3)
    {
        const size_t comma = text.find(',', start);
        if (comma == std::string::npos)
        {
            fields[count++] = text.substr(start);
            return count;
        }
        fields[count++] = text.substr(start, comma - start);
        start = comma + 1;
    }
    // A fourth field means this is not a format either parser knows.
    return text.find(',', start) == std::string::npos ? count : 0;
}

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

// The unit characters Arrow uses for timestamp, time and duration: second, milli, micro, nano.
bool isTimeUnit(char c)
{
    return c == 's' || c == 'm' || c == 'u' || c == 'n';
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
        std::string fields[3];
        const size_t count = splitFields(format.substr(2), fields);
        if (count < 2)
            return simple(ArrowLayout::kUnsupported);

        int64_t precision = 0;
        int64_t scale = 0;
        if (!parseWholeInt(fields[0], precision) || precision < 0)
            return simple(ArrowLayout::kUnsupported);
        // A negative scale is legal in the Arrow spec, so only the parse has to succeed.
        if (!parseWholeInt(fields[1], scale))
            return simple(ArrowLayout::kUnsupported);

        int64_t bits = 128;
        if (count >= 3 && !parseWholeInt(fields[2], bits))
            return simple(ArrowLayout::kUnsupported);
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
        int64_t width = 0;
        if (!parseWholeInt(format.substr(2), width) || width <= 0)
            return simple(ArrowLayout::kUnsupported);
        return fixedWidth(static_cast<int32_t>(width));
    }

    // Temporal types. The unit and timezone matter to Java, not to the buffer layout;
    // only the storage width does. The unit character is still validated, so that a format
    // neither parser recognizes is unsupported on both sides.
    if (format.rfind("td", 0) == 0)
    {
        if (format == "tdD")  // date32, days
            return fixedWidth(4);
        if (format == "tdm")  // date64, milliseconds
            return fixedWidth(8);
        return simple(ArrowLayout::kUnsupported);
    }
    if (format.rfind("ts", 0) == 0)
    {
        // timestamp: "ts<unit>:<optional timezone>", unit one of s, m, u, n. The colon is
        // mandatory even when the timezone is empty.
        if (format.size() < 4 || format[3] != ':')
            return simple(ArrowLayout::kUnsupported);
        if (!isTimeUnit(format[2]))
            return simple(ArrowLayout::kUnsupported);
        return fixedWidth(8);
    }
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
