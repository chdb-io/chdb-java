cmake_minimum_required(VERSION 3.10)
project(chdb_java)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED True)

# Set the path to the shared library and header files
set(LIBRARY_PATH "${CMAKE_SOURCE_DIR}/")
set(HEADER_PATH "${CMAKE_SOURCE_DIR}/src")

# Find the shared library
find_library(CHDB_LIB NAMES chdb PATHS ${LIBRARY_PATH})

# Include the header files
include_directories(${HEADER_PATH})

# Add the source files
set(SOURCE_FILES src/jni/chdb_jni.cpp)

# Add the shared library
add_library(chdbjni SHARED ${SOURCE_FILES})

set_target_properties(chdbjni PROPERTIES LIBRARY_OUTPUT_DIRECTORY ${LIBRARY_PATH})

# Link the shared library
target_link_libraries(chdbjni ${CHDB_LIB})
