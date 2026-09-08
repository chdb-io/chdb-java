#!/usr/bin/env bash
#
# Runs scripts/build-native.sh inside a manylinux_2_28 container, so the Linux shim's glibc
# floor is the one this project targets rather than whatever the CI image happens to ship.
#
# Why this exists
# ---------------
# Built on ubuntu-22.04 the shim requires glibc 2.34, because glibc 2.34 merged libpthread,
# libdl and librt into libc and anything linked there references symbols versioned at 2.34.
# That floor is the package's, and it is far above the engine's own (2.4 on x86_64, 2.17 on
# aarch64). Of the platforms it shuts out, one is still supported: the RHEL 8 family, on glibc
# 2.28, maintained until May 2029 and part of the distribution family with the largest
# enterprise Linux share.
#
# manylinux_2_28 is AlmaLinux 8 with gcc 14, so it builds the same C++17 and produces a shim
# that runs there. Measured: the floor drops from 2.34 to 2.25.
#
# Why docker run rather than a job `container:`
# ---------------------------------------------
# GitHub Actions needs its own Node runtime inside a job container, and pinning that against an
# AlmaLinux 8 glibc is a fight with a moving target -- the runners have already moved from Node
# 20 to Node 24 once. Running the container as a step keeps Actions on the host, where it works,
# and puts only the compiler in the container, which is all that needs to be old.
#
# Usage:
#   scripts/build-native-in-container.sh <platform-id>
#
# Requires docker, a JDK in JAVA_HOME, and the engine already fetched (fetch it on the host so
# the CI download cache is used; the script's own fetch would be a cache miss inside the
# container).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

die() { printf 'build-native-in-container: %s\n' "$*" >&2; exit 1; }

PLATFORM="${1:-}"
[ -n "$PLATFORM" ] || die "usage: $0 <platform-id>"

case "$PLATFORM" in
  linux-x86_64-gnu)  IMAGE_ARCH=x86_64 ;;
  linux-aarch64-gnu) IMAGE_ARCH=aarch64 ;;
  macos-*) die "macOS is not built in a container; its floor is pinned by CMAKE_OSX_DEPLOYMENT_TARGET" ;;
  *) die "unknown platform '${PLATFORM}'" ;;
esac

IMAGE="${CHDB_BUILD_IMAGE:-quay.io/pypa/manylinux_2_28_${IMAGE_ARCH}}"

command -v docker >/dev/null 2>&1 || die "docker is required"
[ -n "${JAVA_HOME:-}" ] || die "JAVA_HOME must point at a JDK; its include/ supplies the JNI headers"
[ -f "${JAVA_HOME}/include/jni.h" ] || die "no include/jni.h under JAVA_HOME=${JAVA_HOME}"

# It has to be a *Linux* JDK. jni.h includes jni_md.h unqualified from an OS-named
# subdirectory, so a macOS JDK would hand the build darwin's typedefs -- which happen to agree
# on 64-bit and would compile, quietly, against the wrong header. CI satisfies this naturally
# because setup-java on a Linux runner installs a Linux JDK; running this from a macOS host
# needs a Linux JDK to point at.
[ -f "${JAVA_HOME}/include/linux/jni_md.h" ] || die \
  "JAVA_HOME=${JAVA_HOME} is not a Linux JDK: no include/linux/jni_md.h.
The shim is compiled for Linux and needs Linux JNI headers. On a macOS host, point JAVA_HOME
at an extracted Linux JDK, or install one inside the image and build there directly."

printf 'build-native-in-container: %s in %s\n' "$PLATFORM" "$IMAGE"

# --user keeps the build output owned by the caller, so later steps on the host can still write
# to target/. HOME is redirected because a non-root user has none in the image, and git wants
# somewhere to put the safe.directory setting.
docker run --rm \
  --user "$(id -u):$(id -g)" \
  -e HOME=/tmp \
  -e JAVA_HOME=/jdk \
  -v "${ROOT}:/work" \
  -v "${JAVA_HOME}:/jdk:ro" \
  -w /work \
  "$IMAGE" \
  bash -c '
    set -euo pipefail
    # The repository is bind-mounted from another uid, which git refuses to read without this.
    # build-native.sh only wants the commit hash for provenance.
    git config --global --add safe.directory /work 2>/dev/null || true
    exec scripts/build-native.sh '"$PLATFORM"'
  '
