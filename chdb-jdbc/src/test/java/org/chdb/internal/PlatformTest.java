package org.chdb.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PlatformTest {

    @ParameterizedTest
    @DisplayName("os.name spellings normalize to linux or macos")
    @CsvSource({
        "Linux, linux",
        "linux, linux",
        "Mac OS X, macos",
        "Darwin, macos",
        "macOS, macos",
    })
    void normalizesOs(String rawOs, String expected) {
        assertEquals(expected, Platform.normalizeOs(rawOs));
    }

    @ParameterizedTest
    @DisplayName("unsupported operating systems normalize to null, not to a guess")
    @ValueSource(strings = {"Windows 11", "SunOS", "AIX", "FreeBSD", ""})
    void rejectsOtherOs(String rawOs) {
        assertNull(Platform.normalizeOs(rawOs));
    }

    @ParameterizedTest
    @DisplayName("os.arch spellings normalize across JVMs and operating systems")
    @CsvSource({
        "x86_64, x86_64",
        "x86-64, x86_64",
        "amd64, x86_64",
        "em64t, x86_64",
        "aarch64, aarch64",
        "arm64, aarch64",
        "AARCH64, aarch64",
    })
    void normalizesArch(String rawArch, String expected) {
        assertEquals(expected, Platform.normalizeArch(rawArch));
    }

    @ParameterizedTest
    @DisplayName("32-bit and other architectures normalize to null")
    @ValueSource(strings = {"i386", "x86", "arm", "armv7l", "ppc64le", "s390x", "riscv64", ""})
    void rejectsOtherArch(String rawArch) {
        assertNull(Platform.normalizeArch(rawArch));
    }

    @Test
    @DisplayName("the current platform is one of the four V1 targets and describes itself fully")
    void currentPlatform() {
        Platform platform = Platform.current();
        assertNotNull(platform);
        assertTrue(
                platform.id().equals(Platform.MACOS_AARCH64)
                        || platform.id().equals(Platform.MACOS_X86_64)
                        || platform.id().equals(Platform.LINUX_X86_64_GNU)
                        || platform.id().equals(Platform.LINUX_AARCH64_GNU),
                platform.id());
        assertEquals("libchdb.so", platform.engineLibraryName());
        assertTrue(platform.jniLibraryName().startsWith("libchdb_java_jni."));
        assertEquals(
                "META-INF/chdb/native/" + platform.os() + "/" + platform.arch(),
                platform.resourcePrefix());
        // Detection is cached, so repeated calls must agree.
        assertEquals(platform.id(), Platform.current().id());
    }
}
