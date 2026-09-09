# Native loading

## Where the libraries come from

Two shared libraries: the engine (`libchdb.so`, ~350 MB) and a small JNI shim
(`libchdb_java_jni.{so,dylib}`, ~130 KB). The driver looks for them in this order:

1. **`-Dchdb.library.path=<dir>`** — a directory holding both. Used as-is: no copying, no
   checksum gate. You chose these bytes.
2. **the `chdb-native-<platform>` JAR on the classpath** — unpacked into a content-addressed
   cache directory and verified. The normal path.
3. **`java.library.path`** — via `System.loadLibrary`. For a development tree, or a
   distribution that installs the libraries system-wide.

**Nothing reaches the network.** A missing platform package is a configuration error with a
Maven coordinate in the message, not something to download at runtime.

The engine is loaded first, then the shim. The shim records its dependency on the engine as
`@rpath/libchdb.so` (macOS) or `libchdb.so` with `RUNPATH=$ORIGIN` (Linux), so it resolves the
copy next to it — and loading the engine by absolute path first also puts its symbols in the
global namespace, which is how the driver probes for optional entry points.

## Unpacking

The cache directory is named after the engine version, the platform and a digest of the pair
it holds:

```
$TMPDIR/chdb-java/26.7.2-rc.2-macos-aarch64-a2152d4651113d10/
├── libchdb.so
├── libchdb_java_jni.dylib
├── manifest.properties
└── .complete
```

The digest is over the engine version and both libraries' recorded checksums, so it is specific
to one build of one platform package: the value above is from a local build and a released
package will differ. Print the real one with `NativeLibraryLoader.loadedRuntime()` rather than
deriving it.

Content-addressed, so the same bytes always land at the same absolute path — which is what lets
several JVMs and several ClassLoaders share one 350 MB copy instead of each making a private
one.

Writes go to a temporary name and are atomically renamed, and the whole directory is built
under an exclusive cross-process file lock. A JVM starting while another is unpacking either
waits or finds a finished directory; it cannot see a half-written shared library. The
`.complete` marker is written last, after both libraries are in place, so its presence is what
proves the directory is usable.

Cold start is a few seconds (unpack and verify 350 MB); warm start is around a tenth of a
second.

The cache is safe to delete. It is rebuilt on next use.

## System properties

| Property | Default | What it does |
|---|---|---|
| `chdb.library.path` | — | Directory holding both libraries; skips the JAR and the cache entirely |
| `chdb.cache.dir` | `$TMPDIR/chdb-java` | Where to unpack |
| `chdb.tmpdir` | — | Alias for `chdb.cache.dir` |
| `chdb.verifyCache` | `false` | Re-verify an already-unpacked cache against its checksums on every start |
| `chdb.skipEngineVersionCheck` | `false` | Proceed with a mismatched engine. Diagnosis only, unsupported |

### `chdb.verifyCache`

Checksums are verified when the libraries are unpacked, so this only catches a cache corrupted
or altered afterwards — at the cost of a SHA-256 pass over 350 MB on every start, roughly a
second. Off by default for that reason; the right setting when the cache lives somewhere less
trusted than the application.

### `chdb.library.path`

The escape hatch, for an engine you built yourself, a platform V1 does not package, or a
read-only filesystem where unpacking is impossible. The directory must hold **both** libraries,
named as the platform expects:

```bash
java -Dchdb.library.path=/opt/chdb -cp app.jar:chdb-jdbc.jar MyApp
# /opt/chdb/libchdb.so
# /opt/chdb/libchdb_java_jni.dylib   (macOS) or .so (Linux)
```

There is no checksum to compare against on this path, but the engine-version check still runs,
so a mismatched pair is still caught.

## Version checks, before the first query

Three things must agree, and are checked at load:

- the shim's JNI ABI version against the driver's;
- the engine's `chdb_version()` against the version the shim was built for;
- the shim's exported entry points against what the driver calls.

A mismatch fails at load with a message naming both versions and how to align them, rather than
surfacing later as a struct-layout disagreement inside a query.

A load failure is sticky: the first attempt's diagnosis is rethrown to every later caller.
Nothing that makes loading fail is transient, and retrying would replace a precise first error
with a confusing second.

## Required and optional entry points

The **required** set is every `chdb_*` function the shim calls directly — checked by the linker
at build time and re-checked at load. Missing any of them is a hard failure.

Two are **optional**, resolved with `dlsym` and fine to be absent because they postdate the
pinned baseline:

| Entry point | Since | If absent |
|---|---|---|
| `chdb_classify_query_n` | v26.7.1-rc.1 | the driver decides whether a statement returns rows from its leading keyword |
| `chdb_shutdown` | v26.7.1-rc.1 | `ChdbNative.shutdown()` returns 2; engine threads are reaped by process exit |

## Error messages

Every load failure names what was tried and what to do. The ones you are most likely to see:

**No platform package.** Lists all three locations that were searched and gives the Maven
coordinate for your platform.

**The wrong platform package.** The same message, plus a line naming the `chdb-native-*` JARs
that *are* on the classpath. Without that line the two cases read identically, which is worst
for the more common one: someone looking at a `chdb-native-linux-x86_64-gnu` dependency in
their POM being told to add a platform package. Found by `scripts/verify-consumer.sh`, which
reproduces both from a Maven repository rather than from the reactor.

**Unsupported platform.** Names the detected `os.name`/`os.arch`/libc and the supported set.
musl Linux is detected specifically — via `/proc/self/maps`, falling back to `ldd --version` —
and reported as unsupported rather than left to fail as a link error.

**`noexec` or read-only cache.** A `/tmp` mounted `noexec` makes a correct library unloadable.
The message says so and points at `chdb.cache.dir`.

**Checksum mismatch.** The package's `sha256sums.txt` disagrees with the unpacked bytes. The
message gives both digests and says not to use the package.

**Already loaded in another ClassLoader.** See [ClassLoaders](classloaders.md).

## Engine settings in the URL

Properties the driver does not recognize are forwarded to the engine as `--key=value`, which is
how ClickHouse settings are passed:

```
jdbc:chdb:/data?max_threads=4&max_memory_usage=2000000000
```

**A bad value is refused; a misspelled name is silent.** On engine 26.7.2-rc.2 an invalid
value for a setting the engine knows fails the connection — `?max_threads=not-a-number`,
`?max_threads=-5` and `?max_memory_usage=abc` all raise `SQLException` rather than connecting
with the setting ignored, which is what 26.7.0 did. A setting *name* the engine does not
recognize still connects successfully with nothing applied, so `?max_thread=4` is a working
connection and a silently absent setting. The driver cannot tell a real setting name from a
typo, so verify with `SELECT value FROM system.settings WHERE name = '...'` if it matters. See
[upstream findings §5](upstream-findings.md).

## What the driver loaded

```java
System.out.println(org.chdb.internal.NativeLibraryLoader.loadedRuntime());
// chDB native runtime: platform=macos-aarch64 source=native-jar engine=26.7.2-rc.2 jniAbi=1
//   enginePath=/var/folders/.../chdb-java/26.7.2-rc.2-macos-aarch64-a2152d4651113d10/libchdb.so
//   jniPath=...

System.out.println(org.chdb.internal.ChdbNative.shimBuildInfo());
// jni.abi.version=1
// shim.commit=d1fa978823c8
// shim.compiler=AppleClang 21.0.0.21000101
// shim.built.against.engine.header=26.7.2-rc.2
// shim.expected.engine.version=26.7.2-rc.2
```

`shim.built.against.engine.header` is the `CHDB_VERSION` constant from the header the shim
compiled against, and it agrees with `shim.expected.engine.version` here. A build where the two
disagree is a shim compiled against a header from a different release — which is what happened
on v26.7.0, where this line read `26.5.1-rc.3` because the vendored header had been taken from
chdb-core's source tree rather than from the release tarball. Either way the driver does not use
it for any decision, only for diagnosis ([upstream findings §2](upstream-findings.md)).
