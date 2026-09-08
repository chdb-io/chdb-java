# Builds libchdb_java_jni, the small JNI shim between org.chdb.internal.ChdbNative and the
# chDB stable C ABI.
#
# Work plan section 5.2/5.3: the shim and libchdb stay two separate shared libraries, the
# shim finds libchdb next to itself at runtime ($ORIGIN / @loader_path), and no system
# jni.h is vendored -- JNI headers come from find_package(JNI) and the generated
# org_chdb_internal_ChdbNative.h comes from javac -h.
#
# Required inputs:
#   CHDB_ENGINE_DIR       directory holding the pinned libchdb to link against
#   CHDB_ENGINE_VERSION   the pinned engine version, compiled in for the load-time check
# Optional:
#   CHDB_JNI_HEADER_DIR   javac -h output; when set, the generated header is compiled in so
#                         a Java/native signature drift is a build error, not a link error
#   CHDB_JNI_SANITIZE     comma-separated sanitizers, e.g. "address,undefined" (work plan
#                         section 5.11). Builds an instrumented shim for CI to run the test
#                         suite under; never used for a published package
#   CHDB_JNI_BUILD_TESTS  ON to also build chdb_jni_test, a JVM-free and engine-free harness
#                         for the shim's standalone logic. Needed because ASan cannot be
#                         preloaded into a process holding a release libchdb (see test/)

cmake_minimum_required(VERSION 3.16)

# Pinned before project(), which is when the compiler is probed and the target baked in.
#
# Without this the deployment target follows the build machine's SDK, so a shim built on a
# macOS 26 runner demands macOS 26 while the engine beside it runs on macOS 11, and the platform
# package silently stops working for everyone on an older release.
#
# scripts/build-native.sh passes the value from scripts/engine.properties, which is the source
# of truth and knows which platform package it is building. What follows is only the fallback
# for a hand-run cmake, and it asks uname rather than CMake: APPLE is set this early, but
# CMAKE_HOST_SYSTEM_PROCESSOR is not -- it is filled in by project(). Reading it here silently
# yields the empty string, which is how an earlier version of this block gave every
# architecture the arm64 target and produced an x86_64 shim requiring macOS 11 beside an engine
# that runs on 10.15.
if(APPLE AND NOT CMAKE_OSX_DEPLOYMENT_TARGET)
  execute_process(
    COMMAND uname -m
    OUTPUT_VARIABLE _host_arch
    OUTPUT_STRIP_TRAILING_WHITESPACE)
  if(_host_arch STREQUAL "x86_64")
    set(CMAKE_OSX_DEPLOYMENT_TARGET "10.15" CACHE STRING "macOS deployment target")
  else()
    # arm64 macOS does not exist before 11.0.
    set(CMAKE_OSX_DEPLOYMENT_TARGET "11.0" CACHE STRING "macOS deployment target")
  endif()
endif()

project(chdb_java_jni CXX)

if(APPLE)
  message(STATUS "chdb_java_jni: macOS deployment target ${CMAKE_OSX_DEPLOYMENT_TARGET}")
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(NOT CHDB_ENGINE_DIR)
  message(FATAL_ERROR "CHDB_ENGINE_DIR is required: point it at the directory holding the "
                      "pinned libchdb (scripts/fetch-libchdb.sh puts one in target/engine/<platform>)")
endif()
if(NOT CHDB_ENGINE_VERSION)
  message(FATAL_ERROR "CHDB_ENGINE_VERSION is required (see scripts/engine.properties)")
endif()

# JNI headers. The old prototype committed jni.h and jni_md.h into the repo, which pinned
# the build to one JDK's internals; discovering them is what makes the shim buildable
# against whatever JDK the platform build runs on.
#
# Headers only, on purpose. find_package(JNI REQUIRED) also insists on libjvm and libjawt,
# and a JNI shim links neither: it is loaded *by* a JVM, and it draws nothing from AWT.
# Requiring them breaks on perfectly good JDK layouts -- Homebrew's openjdk keeps the real
# bundle under libexec/openjdk.jdk/Contents/Home and exposes a flattened include/ that
# FindJNI cannot match -- so the requirement is dropped rather than worked around.
find_package(JNI QUIET)

set(CHDB_JNI_INCLUDE_DIRS "")
if(JNI_INCLUDE_DIRS)
  set(CHDB_JNI_INCLUDE_DIRS ${JNI_INCLUDE_DIRS})
else()
  set(_jdk_home "$ENV{JAVA_HOME}")
  if(NOT _jdk_home)
    message(FATAL_ERROR
      "Could not locate the JNI headers. Set JAVA_HOME to a JDK, or pass "
      "-DJAVA_HOME=<jdk> so find_package(JNI) can pick it up.")
  endif()
  # Homebrew's openjdk@N symlink is not itself a JDK home; the bundle inside it is.
  if(EXISTS "${_jdk_home}/libexec/openjdk.jdk/Contents/Home/include/jni.h")
    set(_jdk_home "${_jdk_home}/libexec/openjdk.jdk/Contents/Home")
  endif()
  if(NOT EXISTS "${_jdk_home}/include/jni.h")
    message(FATAL_ERROR "No include/jni.h under JAVA_HOME=${_jdk_home}")
  endif()
  set(CHDB_JNI_INCLUDE_DIRS "${_jdk_home}/include")
  # jni_md.h lives in an OS-named subdirectory that jni.h includes unqualified.
  foreach(_md_dir darwin linux)
    if(EXISTS "${_jdk_home}/include/${_md_dir}/jni_md.h")
      list(APPEND CHDB_JNI_INCLUDE_DIRS "${_jdk_home}/include/${_md_dir}")
    endif()
  endforeach()
endif()
message(STATUS "chdb_java_jni: JNI headers from ${CHDB_JNI_INCLUDE_DIRS}")

find_library(CHDB_LIBRARY
  NAMES chdb libchdb.so libchdb.dylib
  PATHS "${CHDB_ENGINE_DIR}"
  NO_DEFAULT_PATH
  REQUIRED)
message(STATUS "chdb_java_jni: linking against ${CHDB_LIBRARY} (engine ${CHDB_ENGINE_VERSION})")

# Build provenance, compiled in so that a mismatched pair of libraries can be diagnosed from
# a bug report without access to the build machine.
find_package(Git QUIET)
set(CHDB_JNI_GIT_COMMIT "unknown")
if(GIT_FOUND)
  execute_process(
    COMMAND "${GIT_EXECUTABLE}" rev-parse --short=12 HEAD
    WORKING_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}"
    OUTPUT_VARIABLE CHDB_JNI_GIT_COMMIT
    OUTPUT_STRIP_TRAILING_WHITESPACE
    ERROR_QUIET)
endif()

add_library(chdb_java_jni SHARED
  src/chdb_jni.cpp
  src/chdb_jni_arrow.cpp
  src/chdb_jni_signals.cpp
  src/chdb_optional_api.cpp)

target_include_directories(chdb_java_jni PRIVATE
  "${CMAKE_CURRENT_SOURCE_DIR}/src"
  # The engine header pinned to CHDB_ENGINE_VERSION, vendored under include/. The release
  # tarball ships only the shared library, so the header has to travel with the binding.
  "${CMAKE_CURRENT_SOURCE_DIR}/include"
  ${CHDB_JNI_INCLUDE_DIRS})

if(CHDB_JNI_HEADER_DIR)
  target_include_directories(chdb_java_jni PRIVATE "${CHDB_JNI_HEADER_DIR}")
  target_compile_definitions(chdb_java_jni PRIVATE CHDB_JNI_HAVE_GENERATED_HEADER=1)
endif()

target_compile_definitions(chdb_java_jni PRIVATE
  CHDB_JNI_GIT_COMMIT="${CHDB_JNI_GIT_COMMIT}"
  CHDB_JNI_COMPILER="${CMAKE_CXX_COMPILER_ID} ${CMAKE_CXX_COMPILER_VERSION}"
  CHDB_JNI_EXPECTED_ENGINE_VERSION="${CHDB_ENGINE_VERSION}")

target_compile_options(chdb_java_jni PRIVATE
  -Wall -Wextra -Wpedantic
  # A JNI entry point that silently drops an error is the failure mode this shim exists to
  # prevent, so the warnings that catch it are errors.
  -Werror=return-type
  -Werror=uninitialized
  -Werror=unused-result
  # JNIEXPORT already makes the entry points visible; everything else stays internal so the
  # shim cannot accidentally interpose on a libchdb symbol.
  -fvisibility=hidden)

if(CHDB_JNI_BUILD_TESTS)
  # Links only the translation units that need neither JNI nor the engine: the Arrow layout
  # parser, the signal guard, and the header-only handle registry. Not linking libchdb is the
  # entire point -- its allocator is what makes a preloaded ASan crash.
  add_executable(chdb_jni_test
    test/chdb_jni_test.cpp
    src/chdb_jni_arrow.cpp
    src/chdb_jni_signals.cpp)
  target_include_directories(chdb_jni_test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src")
  target_compile_options(chdb_jni_test PRIVATE -Wall -Wextra -Wpedantic -g)
  if(CHDB_JNI_SANITIZE)
    target_compile_options(chdb_jni_test PRIVATE
      "-fsanitize=${CHDB_JNI_SANITIZE}" -fno-omit-frame-pointer -fno-optimize-sibling-calls)
    target_link_options(chdb_jni_test PRIVATE "-fsanitize=${CHDB_JNI_SANITIZE}")
    if(CHDB_JNI_SANITIZE MATCHES "undefined")
      target_compile_options(chdb_jni_test PRIVATE -fno-sanitize-recover=undefined)
    endif()
  endif()
  find_package(Threads REQUIRED)
  target_link_libraries(chdb_jni_test PRIVATE Threads::Threads)
endif()

if(CHDB_JNI_SANITIZE)
  message(STATUS "chdb_java_jni: instrumenting with -fsanitize=${CHDB_JNI_SANITIZE}")
  # Only the shim is instrumented. libchdb comes from a release tarball and is not, so the
  # sanitizers see the shim's own arithmetic, indexing and lifetimes -- which is exactly the
  # code this project owns and where a mistake becomes a JVM crash. Instrumenting the engine
  # too needs a sanitizer build of chdb-core and is tracked separately in the work plan.
  target_compile_options(chdb_java_jni PRIVATE
    "-fsanitize=${CHDB_JNI_SANITIZE}"
    # A report that does not name a line is not worth having.
    -fno-omit-frame-pointer
    -fno-optimize-sibling-calls
    -g)
  target_link_options(chdb_java_jni PRIVATE "-fsanitize=${CHDB_JNI_SANITIZE}")
  # Undefined behaviour must fail the test rather than print and carry on, or a CI run stays
  # green while reporting the defect in its log.
  if(CHDB_JNI_SANITIZE MATCHES "undefined")
    target_compile_options(chdb_java_jni PRIVATE -fno-sanitize-recover=undefined)
  endif()
endif()

set_target_properties(chdb_java_jni PROPERTIES
  # No "lib" prefix juggling: System.load() takes an absolute path, and the platform package
  # records the exact file name in manifest.properties.
  PREFIX "lib"
  CXX_VISIBILITY_PRESET hidden
  VISIBILITY_INLINES_HIDDEN ON)

target_link_libraries(chdb_java_jni PRIVATE "${CHDB_LIBRARY}")

if(APPLE)
  # libchdb's install_name is @rpath/libchdb.so, so the shim needs an LC_RPATH of
  # @loader_path to find the copy the loader extracted next to it. No absolute build-machine
  # path is recorded.
  set_target_properties(chdb_java_jni PROPERTIES
    SUFFIX ".dylib"
    INSTALL_RPATH "@loader_path"
    BUILD_WITH_INSTALL_RPATH ON)
  # No -undefined,error here: it is the default on Apple's linker and passing it
  # explicitly now draws a deprecation warning.
else()
  set_target_properties(chdb_java_jni PROPERTIES
    SUFFIX ".so"
    INSTALL_RPATH "$ORIGIN"
    BUILD_WITH_INSTALL_RPATH ON)
  target_link_options(chdb_java_jni PRIVATE
    # RUNPATH rather than the legacy DT_RPATH: RUNPATH is what LD_LIBRARY_PATH can be
    # overridden against, and it is what modern linkers emit by default anyway.
    "LINKER:--enable-new-dtags"
    "LINKER:-z,defs"
    "LINKER:--no-undefined")
endif()
