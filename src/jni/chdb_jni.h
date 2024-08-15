#pragma once

#include "jni.h"
#include "chdb.h"

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT jobject JNICALL Java_org_chdb_jdbc_ChdbJniUtil_executeQuery(JNIEnv *, jclass, jstring);
//JNIEXPORT jstring JNICALL Java_org_chdb_jdbc_ChdbJniUtil_executeQuery(JNIEnv *, jclass, jstring);

#ifdef __cplusplus
}
#endif
