#include "chdb_jni.h"
#include "chdb.h"
#include <string>
#include <vector>
#include <iostream>

local_result_v2 * queryToBuffer(
        const std::string & queryStr,
        const std::string & output_format = "CSV",
        const std::string & path = {},
        const std::string & udfPath = {})
{
    std::vector<std::string> argv = {"clickhouse", "--multiquery"};

    if (output_format == "Debug" || output_format == "debug")
    {
        argv.push_back("--verbose");
        argv.push_back("--log-level=trace");
        argv.push_back("--output-format=CSV");
    }
    else
    {
        argv.push_back("--output-format=" + output_format);
    }

    if (!path.empty())
    {
        argv.push_back("--path=" + path);
    }

    argv.push_back("--query=" + queryStr);

    if (!udfPath.empty())
    {
        argv.push_back("--");
        argv.push_back("--user_scripts_path=" + udfPath);
        argv.push_back("--user_defined_executable_functions_config=" + udfPath + "/*.xml");
    }

    std::vector<char *> argv_char;
    for (auto & arg : argv)
    {
        argv_char.push_back(&arg[0]);
    }
    argv_char.push_back(nullptr);

    return query_stable_v2(static_cast<int>(argv_char.size() - 1), argv_char.data());
}


JNIEXPORT jobject JNICALL Java_org_chdb_jdbc_ChdbJniUtil_executeQuery(JNIEnv *env, jclass clazz, jstring query) {
    // 1. Convert Java String to C++ string

    std::cout << "call func: ChdbJniUtil_executeQuery!" << std::endl;

    const char *queryStr = env->GetStringUTFChars(query, nullptr);
    if (queryStr == nullptr) {
        std::cerr << "Error: Failed to convert Java string to C++ string" << std::endl;
        return nullptr;
    }

    // 2. Call the native query function
    local_result_v2 *result = queryToBuffer(queryStr);

   //  3. Release the Java string resources
    env->ReleaseStringUTFChars(query, queryStr);

    // 4. Check if the result is null (indicates an error)
    if (result == nullptr) {
        std::cerr << "Error: result is null" << std::endl;
        return nullptr;
    }

    // 5. Find the Java class and its constructor
    jclass resultClass = env->FindClass("org/chdb/jdbc/LocalResultV2");
    if (resultClass == nullptr) {
        std::cerr << "Error: resultClass is null" << std::endl;
        free_result_v2(result);  // Ensure to free the result even on error
        return nullptr;
    }

    jmethodID constructor = env->GetMethodID(resultClass, "<init>", "(Ljava/nio/ByteBuffer;JJDLjava/lang/String;)V");
    if (constructor == nullptr) {
        std::cerr << "Error: constructor is null" << std::endl;
        free_result_v2(result);  // Ensure to free the result even on error
        return nullptr;
    }

    // 6. Create a direct ByteBuffer for the result buffer
    jobject buffer = env->NewDirectByteBuffer(result->buf, result->len);
    if (buffer == nullptr) {
        std::cerr << "Error: Failed to create ByteBuffer" << std::endl;
        free_result_v2(result);
        return nullptr;
    }

    // 7. Create the Java String for the error message, if present
    jstring errorMessage = result->error_message ? env->NewStringUTF(result->error_message) : nullptr;

    // 8. Create a new Java object to hold the result
    jobject resultObj = env->NewObject(resultClass, constructor, buffer, result->rows_read, result->bytes_read, result->elapsed, errorMessage);
    if (resultObj == nullptr) {
        std::cerr << "Error: Failed to create result object" << std::endl;
        free_result_v2(result);
        return nullptr;
    }

    // 9. Free the native result structure
    free_result_v2(result);

    // 10. Return the Java object
    return resultObj;
}
