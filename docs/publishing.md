# Publishing runbook

Everything needed to put `org.chdb:chdb-jdbc` on Maven Central, in the order it has to happen,
marked by who can do it. The short version: the mechanics are done and tested, the namespace
needs one DNS record, and one licence question needs somebody with authority to answer it.

---

## Status

| | |
|---|---|
| POM metadata Central requires | **done** — `<scm>`, `<developers>`, `<licenses>`, `<organization>`, `<issueManagement>` |
| Sources and javadoc jars | **done** — real ones for `chdb-jdbc`, empty ones for the native packages |
| GPG signing | **done and verified** — signs jar, sources, javadoc and pom |
| Publishing plugin | **done** — `central-publishing-maven-plugin`, `autoPublish=false` |
| Third-party licence inventory | **done** — generated from the engine, shipped in the package, drift-tested |
| `org.chdb` namespace | **not started** — needs a DNS TXT record, and there is no fallback |
| A way to stage all four platforms for one release | **not started** — issue #15 |
| GPG key for the project | **not started** — needs a decision about whose key |
| Position on the LGPL components | **not started** — needs chdb-io |

---

## 1. The release profile

```bash
# Once, before any of the below: the shim compiles against javac -h output, and
# build-native.sh refuses to run without it.
mvn -pl chdb-jdbc compile

# On each platform's own machine, in one shared checkout:
scripts/build-native.sh macos-aarch64                     # on an Apple Silicon Mac
scripts/build-native.sh macos-x86_64                      # on an Intel Mac

# Linux, from either host. JAVA_HOME must be a *Linux* JDK: jni.h includes jni_md.h from an
# OS-named subdirectory, so a macOS JDK has include/darwin and the helper rejects it.
JAVA_HOME=/path/to/linux-jdk scripts/build-native-in-container.sh linux-x86_64-gnu
JAVA_HOME=/path/to/linux-jdk scripts/build-native-in-container.sh linux-aarch64-gnu

mvn versions:set -DnewVersion=26.7.0.1     # a release, not the -SNAPSHOT in the POM
mvn -Prelease deploy
```

**Staging is not optional and Maven does not do it.** The two shared libraries are put into
`target/native/` by `scripts/build-native.sh`, and the native modules pick them up as a resource
directory. Run `mvn -Prelease deploy` on a clean checkout and each native module packages an
empty jar — which would then be published to Central, where nothing can be unpublished. The
release profile now fails the build rather than allowing that:

```
chdb-native-macos-x86_64 has not been staged: no .../macos/x86_64/libchdb.so.
Run scripts/build-native.sh macos-x86_64 on a macos-x86_64 machine first
```

It also refuses a package staged with `--local-engine`. That path skips the pinned checksum on
purpose, so nothing knows which engine it is, and the licence inventory this repository holds
describes the pinned release rather than that binary — `build-native.sh` therefore ships no
inventory for a local engine and stamps `engine.source=local` in the manifest, which the
release profile rejects.

**And no single machine can stage all four.** `build-native.sh` refuses to cross-build, on
purpose: a shim linked for another architecture fails at `System.load()` in a user's JVM rather
than at build time. Linux is covered from either host by the container helper — given a Linux
JDK to point `JAVA_HOME` at, which a macOS machine does not have lying around — but macOS
x86_64 needs an Intel Mac. CI already builds all four on their own runners and does not currently
upload the packaged jars, so assembling a release means either four machines or a release
workflow. That gap is issue #15 and should be closed before the first release rather than
worked around by hand.

The version matters too: `deploy` on the `26.7.0.1-SNAPSHOT` currently in the POM publishes a
snapshot, which goes to a different place and is never validated or promoted. A Central release
bundle needs a non-`SNAPSHOT` version.

Nothing in the profile runs during an ordinary build, because signing prompts for a passphrase
and the publishing plugin talks to Sonatype. What it adds:

- `maven-gpg-plugin` at `verify`, with `--pinentry-mode loopback` so the passphrase can come
  from a CI secret rather than a terminal.
- `central-publishing-maven-plugin` with `autoPublish=false`. A release here is ~510 MB across
  six artifacts and cannot be unpublished; a human confirming the bundle in the portal is worth
  the extra step, at least until several releases have gone out uneventfully.
- Sources and javadoc jars. `chdb-jdbc` produces real ones. The four native packages produce
  empty ones — Central requires both for every non-`pom` artifact, and a package whose whole
  content is two shared libraries has no Java to document. They are empty by construction
  rather than by accident: `maven-source-plugin` would emit nothing at all and the upload would
  be rejected for a missing file.

Verified end to end with a disposable key: `mvn -Prelease verify` produced
`chdb-jdbc-*.jar.asc`, `-sources.jar.asc`, `-javadoc.jar.asc` and `.pom.asc`.

Note it is `verify`, not `package` — the signing execution is bound to `verify`, and
`mvn package` silently produces no signatures.

## 2. The GPG key — self-service, but decide whose it is

```bash
gpg --full-generate-key            # RSA 4096, long or no expiry
gpg --list-secret-keys --keyid-format=long
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
gpg --keyserver keys.openpgp.org  --send-keys <KEY_ID>
```

Central verifies signatures against a public keyserver, so the public half must be on at least
one; propagation takes a few minutes.

**The decision that is not technical:** whose key signs chdb-io's artifacts. A personal key
means releases stop when that person is unavailable and the key leaves with them. A project key
in the CI secret store is the usual answer for an organisation, at the cost of the key being
only as protected as the secret store.

For CI, two secrets — the ASCII-armoured private key and its passphrase:

```yaml
- run: echo "$GPG_PRIVATE_KEY" | gpg --batch --import
- run: mvn -Prelease deploy -Dgpg.passphrase="$GPG_PASSPHRASE"
```

Signing the first release from a laptop is fine and is the faster way to find out whether the
rest works.

## 3. The namespace — needs DNS, approved automatically

`org.chdb` is **unclaimed**. So are `io.chdb`, `com.chdb` and `io.github.chdb-io`; Maven Central
returns zero artifacts for all four. There is no conflict to resolve, only a claim to make.

1. Register at <https://central.sonatype.com>.
2. Start a namespace claim for `org.chdb`.
3. Add the TXT record Sonatype names to the `chdb.org` zone. `chdb.org` resolves, so whoever
   administers that zone can do this.
4. Sonatype's checker verifies it. No human review, no queue.
5. Generate a portal token and put it in `~/.m2/settings.xml`:

```xml
<server>
  <id>central</id>
  <username>TOKEN_USERNAME</username>
  <password>TOKEN_PASSWORD</password>
</server>
```

The `id` must be `central` — that is what `publishingServerId` in the release profile expects.

**There is no GitHub fallback for an organisation.** An earlier version of this document
offered `io.github.chdb-io`, verified by creating a repository under the `chdb-io` org. That is
not available — Sonatype's wording:

> Currently, we only support the GitHub username that you used to sign up, so
> `io.github.<github organization name>` is not available as an automatically registered
> namespace.

GitHub verification would give `io.github.<maintainer-username>`, which is a personal
coordinate and the wrong thing to publish an organisation's driver under. **DNS access to
`chdb.org` is therefore a hard prerequisite**, not a convenience, and it is worth confirming
who has it before anything else in this document is started.

## 4. Publishing limits — check before the first release

Central enforces organisation-level limits from **1 October 2026**. The measurement window is
the calendar month, and Sonatype is explicit that it is not smoothed:

> Each metric resets at the start of the month, and usage is compared directly against that
> month's threshold, not averaged across multiple months.

The published percentiles:

| Percentile | Files | Release size | Releases |
|---|---|---|---|
| 90th | 1,167 | 78 MB | 7 |
| 93rd | 1,852 | 150 MB | 9 |
| 95th | 2,805 | 247 MB | 13 |
| 96th | 3,824 | 349 MB | 15 |
| 97th | 5,188 | 522 MB | 19 |
| 98th | 8,030 | 877 MB | 27 |
| 99th | 17,724 | 1,808 MB | 50 |

Ours, measured rather than estimated:

- **Release size ≈ 510 MB** in any month we release. The four native packages are
  98 + 114 + 129 + 166 MB — each jar tracks its engine tarball almost exactly — plus ~6 MB of
  driver javadoc and ~1 MB of everything else.
- **File count ≈ 100.** Six artifacts × (jar, sources, javadoc, pom) × (file + `.asc` + `.sha1`
  + `.md5`). Below even the 90th percentile of 1,167.
- **Release count: 1.**

So in a release month we are at roughly the 97th percentile on size, and near the floor on the
other two. **Cadence does not reduce that number** — publishing every three months does not
make a release month cheaper, it makes the other two months empty. Anyone reasoning from a
three-month average will get this wrong.

What cadence does change is the thing enforcement actually looks at. Sonatype's own words:

> enforcement targets organizations that remain over the free thresholds repeatedly or on a
> sustained basis

and the exception form asks specifically "whether usage is sustained, occasional, or
event-driven". A ~510 MB release every two to three months, with nothing in between, is the
textbook occasional pattern rather than a sustained one. That is the case to make, and it is a
much better case than a smoothed average would have been.

**Do this before the first release, not after a rejection.** Once the namespace exists, read the
real numbers at <https://central.sonatype.com/publishing/usage>, then mail
`central-support@sonatype.com` with what the exception form asks for:

- organisation and namespace (`org.chdb`)
- what it is: four per-platform packages of an embedded analytical database engine, where the
  size is a ~326 MB prebuilt native library per platform and cannot meaningfully be reduced
- the publishing pattern: event-driven, one release every two to three months, tracking chDB
  engine releases
- what we are asking for: a release-size threshold that accommodates ~510 MB in a release month

**Confirm rather than assume:** from 1 October 2026 artifacts of a *commercial nature* require
Publisher Pro regardless of volume. Whether chDB counts is for whoever owns the relationship.

## 5. Licences

### Our code
Apache-2.0, `LICENSE` in the repository, declared in the POM. Nothing to do.

### The redistributed engine

Each platform package ships a ~326 MB `libchdb.so` verbatim from a chdb-core release. It
statically links ClickHouse and **968 third-party components**.

That number, and the licence of each one, comes from the engine itself:

```sql
SELECT DISTINCT library_name, license_type FROM system.licenses
```

ClickHouse generates `system.licenses` at build time, so querying the binary we actually ship is
authoritative — better than scanning a source checkout, which describes a checkout rather than
an artifact, and much better than reading filenames, which calls every dual-licensed component
GPL. The result is committed at `licenses/engine-third-party-<version>.tsv`, shipped in the
package as `META-INF/licenses/engine-third-party.tsv`, and guarded by `LicenseInventoryIT`,
which fails if the file stops matching the engine. An engine bump therefore cannot quietly
change what we redistribute.

**Regenerating it** after an engine bump: run the query above against the new engine and write
`library_name<TAB>license_type`, distinct, ordered by `lower(library_name), license_type`, to
`licenses/engine-third-party-<new-version>.tsv`. `LicenseInventoryIT` will tell you if it is
wrong.

The file is generated on whichever platform the person doing the bump has, but it is asserted
on all four: `LicenseInventoryIT` runs in every platform job and queries that job's own engine,
so a component set that differs between platforms fails CI rather than shipping.

Done once so far. The v26.7.0 → v26.7.2-rc.2 bump produced a **byte-identical** inventory —
same 968 entries, same licence strings, same SHA-256 — so the redistribution position did not
move, and the count above still describes the engine we ship. That is the expected shape of a
patch-level bump, not something to rely on: the whole point of keying the file by version is
that the next one may differ.

### The one open question

Reading the inventory rather than guessing at it: most components are permissive, and several
that a filename scan calls GPL are dual licensed with the permissive half in force — `zstd` is
BSD-3, `rocksdb` is Apache-2.0, `liburing` is MIT, `ittapi` is `GPL-2.0-only OR BSD-3-Clause`.

Twelve carry a copyleft licence with no permissive alternative, in two families:

```
lemmagen-c   libgsasl   libssh   mariadb-connector-c   numactl   xz          (LGPL)
cbindgen   defer-drop   fortanix-sgx-abi   shuffling-allocator   timer   webpki-roots
                                                                            (MPL-2.0)
```

LGPL carries obligations under **static** linking that it does not under dynamic, and
`libchdb.so` links everything statically — its only shared dependencies are `libc`, `libm`,
`libdl`, `librt`, `libpthread` and the loader. MPL-2.0 is weaker: file-level copyleft, with no
relinking requirement, so the obligation is to offer the source of any modified MPL file.

Several of the MPL components are plainly build-time Rust tooling — `cbindgen` generates
headers, `fortanix-sgx-abi` is for SGX enclaves — and are unlikely to be in a chDB build at
all. But *unlikely* is not *established*, which is the same problem as with the LGPL six:
whether any of the twelve is actually in the binary cannot be settled from the binary. It is
stripped to 582 dynamic symbols and links statically. `nm` finds no `ssh_`, `mysql_`, `numa_`,
`lzma_` or `gsasl_` symbols, which proves nothing either way. That question belongs to the
engine build configuration.

**And it is not a new question.** The identical `libchdb.so` is already redistributed on PyPI
and npm by chdb-io. Whatever notice and offer-of-source obligations attach, attach there too.
So the task is to **inherit and verify**, not to derive an answer here:

1. Ask chdb-core maintainers for the notice bundle their PyPI and npm releases ship, and the
   position taken on the six LGPL components. If there is one, mirror it.
2. If there is not, that is a finding about all three distributions, and should be raised as
   such rather than solved inside this repository.

`LicenseInventoryIT.copyleftComponentsAreTheKnownSet` pins the set to exactly those twelve, so a
thirteenth appearing in a future engine fails CI rather than shipping unnoticed. It matches a
copyleft *family* pattern — AGPL, GPL, LGPL, MPL, EPL, CDDL, CPL, OSL, SSPL, EUPL, CeCILL, QPL —
and then subtracts an explicit allowlist of dual-licensed strings, so a new component under a
licence nobody here anticipated is caught rather than ignored. An earlier version asked only for
`license_type IN ('LGPL', 'GPL')` and silently missed the six MPL-2.0 components that were
already there.

## 6. Order of operations

Each step can invalidate the next, so:

1. **Claim the namespace and read the Usage Center.** Arrange an exception if the numbers look
   tight. Enforcement starts 1 October 2026.
2. **Get the licence position on the engine** from whoever owns it.
3. **Enable SNAPSHOTs, publish a snapshot, and have somebody consume it.** Snapshot publishing
   is opt-in per namespace: select **Enable SNAPSHOTs** in the portal first, or the upload is
   rejected. The consuming project then needs the snapshot repository declared, because Central's
   snapshots are not served from the release repository:

   ```xml
   <repository>
     <id>central-snapshots</id>
     <url>https://central.sonatype.com/repository/maven-snapshots/</url>
     <snapshots><enabled>true</enabled></snapshots>
   </repository>
   ```

   With both in place this is the cheap step that finds everything the reactor build cannot:
   whether the POMs resolve from a repository, whether the BOM behaves, whether declaring one
   platform package yields a working driver, whether a 166 MB artifact uploads. Tracked as
   issue #10.
4. Then the first real release, with `autoPublish=false` so the bundle is reviewed in the portal
   before it becomes permanent.

## 7. Who can do what

| | Who | Needs approval |
|---|---|---|
| Release profile, signing config, licence inventory | done | no |
| Generate and publish a GPG key | any maintainer | no |
| Decide *whose* key signs | chdb-io | a decision, not an approval |
| `org.chdb` namespace claim | whoever runs `chdb.org` DNS | automatic once the TXT record is up |
| Sonatype account and portal token | any maintainer | no |
| A publishing-limit exception | Sonatype support | yes, if the numbers need it |
| **Position on the twelve copyleft components** | **chdb-io / ClickHouse legal** | **yes — the only real gate** |
| Whether chDB is "commercial" for Central | whoever owns the Sonatype relationship | yes |
