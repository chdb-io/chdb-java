#!/bin/bash

# Set relative paths
SOURCE_DIR=$(dirname "$0")
BUILD_DIR="$SOURCE_DIR/cmake-build-debug"

# Run cmake to configure the project
/opt/homebrew/bin/cmake -DCMAKE_BUILD_TYPE=Debug -DCMAKE_MAKE_PROGRAM=/opt/homebrew/bin/ninja -G Ninja -S "$SOURCE_DIR" -B "$BUILD_DIR"

# Build the project
/opt/homebrew/bin/cmake --build "$BUILD_DIR" --target chdbjni -j 12
