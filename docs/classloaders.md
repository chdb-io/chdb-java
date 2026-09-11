# ClassLoaders

## The rule

**One JVM holds one copy of the chDB native runtime, and it belongs to the ClassLoader that
loaded it.** A second, isolated ClassLoader cannot load it too.

That is a JNI rule, not a driver choice: `System.load` on a library already loaded elsewhere
raises `UnsatisfiedLinkError: ... already loaded in another classloader`.

The usual workaround — copy the library to a fresh path so each loader gets its own — is
explicitly out of scope for V1. The engine is 350 MB; two copies is 700 MB of mapped engine for
no benefit. The driver refuses rather than doing that silently.

## What is supported

Multiple child ClassLoaders sharing one runtime through their common parent:

```
System / Common / Shared parent ClassLoader
├── chdb-jdbc + chdb-native-<platform>     <-- the one native runtime
├── child ClassLoader A   (uses chDB through parent delegation)
└── child ClassLoader B   (uses chDB through parent delegation)
```

Both children get the same driver classes and the same runtime. This works, and is tested.

What is **not** supported is two isolated ClassLoaders each owning a copy. "Multiple
ClassLoaders" means children sharing a parent's runtime, not children each having their own.

## Where to put the driver

The rule: **`chdb-jdbc` and the platform native package go in a ClassLoader that is a parent of
every user of chDB, and nowhere else.** Remove them from the child applications.

| | Put it here | Not here |
|---|---|---|
| **Tomcat** | `$CATALINA_HOME/lib` | `WEB-INF/lib` |
| **Spark** | `--driver-class-path` / `spark.executor.extraClassPath` | `--jars` |
| **Flink** | `lib/` | the job JAR |
| **Plain app** | the application classpath | — |
| **Spring Boot fat JAR** | the application classpath (one loader; nothing to do) | — |

For Tomcat specifically: a JDBC driver in `WEB-INF/lib` is per-webapp, so two webapps using
chDB would each try to load the engine and the second would fail. Put it in `$CATALINA_HOME/lib`
and the driver is loaded once by the common loader; both webapps reach it by delegation.

## The error you get

```
The chDB native runtime is already loaded in a different ClassLoader, so this
ClassLoader cannot load /tmp/chdb-java/26.7.3-.../libchdb.so as well. A JVM holds
one copy of a JNI library and it belongs to whichever ClassLoader loaded it.

Already loaded by:
  owner = org.apache.catalina.loader.ParallelWebappClassLoader@4b1c1ea6
  platform = linux-x86_64-gnu
  engine.version = 26.7.3
  jni.abi.version = 2
  source = native-jar
  engine.path = /tmp/chdb-java/26.7.3-linux-x86_64-gnu-.../libchdb.so
  jni.path = ...
Loading from:
  org.apache.catalina.loader.ParallelWebappClassLoader@7a3e0c11

Fix it one of these ways:
  1. Move chdb-jdbc and the chdb-native-* package to a ClassLoader that is a parent of
     every user of chDB, and remove them from the child ClassLoaders. ...
  2. Run the second application in its own JVM.
```

The driver publishes an owner marker — strings only, so it can be read across a ClassLoader
boundary without either loader seeing the other's classes, and without pinning the writer's
loader in memory — so the second loader can say who has the runtime, not just that someone
does.

It is a diagnostic, not a safety mechanism. The JNI and OS loaders remain the real boundary;
the marker only turns their error into an actionable one.

## JPMS

`chdb-jdbc` carries `Automatic-Module-Name: org.chdb.jdbc`, and each native package carries
`org.chdb.runtime.<os>.<arch>`. There is no `module-info.java`, which would raise the
compilation floor above Java 11.

On the module path, JNI access may need to be permitted explicitly on newer JDKs:

```
--enable-native-access=org.chdb.jdbc
```

Without it, JDK 24 and later warn; a future release will deny by default. The warning names
`org.chdb.jdbc` regardless of which application module called in, because the shim is loaded by
the driver's own module.

## Shaded and fat JARs

The driver is found through `META-INF/services/java.sql.Driver`. A shade or shadow step that
overwrites rather than merges service files will drop it, and `DriverManager.getConnection`
will then report no suitable driver.

Maven Shade:

```xml
<transformers>
  <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
</transformers>
```

Gradle Shadow calls `mergeServiceFiles()`.

Do not shade the native package. Relocating or repacking `META-INF/chdb/native/**` breaks the
resource paths the loader reads, and there is nothing to gain: it contains no classes.

## Redeploying

An application redeploy that discards a webapp ClassLoader does not unload the native library —
the JVM has no way to. After redeploying:

- if the driver is in the shared parent loader, it is untouched and the new deployment uses it
  by delegation. This is the reason to put it there;
- if it is in the webapp loader, the new deployment cannot load it and fails with the error
  above. Only a JVM restart clears that.

Close your `Connection`s on shutdown regardless. A leaked connection holds the engine's storage
path binding, so a redeploy that leaks one cannot open a different path.
