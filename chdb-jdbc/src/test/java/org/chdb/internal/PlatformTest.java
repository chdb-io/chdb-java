package org.chdb.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    @DisplayName("resourcePrefixFor covers every V1 id and agrees with the detected platform's own")
    void resourcePrefixForEveryId() {
        // Two ways of computing the same path -- one from the detected triple, one from an id
        // for a machine this JVM is not -- and the diagnostic that tells a consumer they
        // declared the wrong platform package depends on them matching. A new platform added
        // to ALL_IDS without a case in the switch fails here rather than in a message.
        assertEquals(4, Platform.ALL_IDS.length);
        for (String id : Platform.ALL_IDS) {
            String prefix = Platform.resourcePrefixFor(id);
            assertTrue(prefix.startsWith("META-INF/chdb/native/"), prefix);
        }
        Platform platform = Platform.current();
        assertEquals(platform.resourcePrefix(), Platform.resourcePrefixFor(platform.id()));
    }

    @Test
    @DisplayName("resourcePrefixFor rejects an id that is not a V1 platform")
    void resourcePrefixForRejectsUnknown() {
        assertThrows(IllegalArgumentException.class, () -> Platform.resourcePrefixFor("windows-x86_64"));
    }
}
