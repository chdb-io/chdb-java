# Getting to a published release

What stands between the current `main` and an artifact on Maven Central, who has to do each
part, and which parts need someone else's approval.

Written as three separate tracks because they proceed independently and have different
blockers: **signing and publishing** is mechanical and self-service, **the namespace** needs
DNS access, and **licences** need a person with authority to say yes.

---

## Track 1 — Signing and publishing mechanics

**Nobody's approval needed. All of it can be done by whoever holds the repository and one DNS
record.**

### 1.1 What Maven Central requires that we do not have

Checked against the current `main`:

| Requirement | Status |
|---|---|
| `sources` JAR | declared in `pluginManagement` only, so **not produced** |
| `javadoc` JAR | same, **not produced** |
| GPG signature (`.asc`) per file | **absent** |
| `<scm>` in the POM | **absent**, and Central rejects without it |
| `<developers>` in the POM | **absent**, likewise |
| `<licenses>` | present |
| `<name>`, `<description>`, `<url>` | present |
| Publishing plugin and credentials | **absent** |

None of this is hard; it is simply not started. It is roughly a day of work and one CI secret.

### 1.2 The GPG key — self-service, but decide whose key it is

Generating a key needs no approval:

```bash
gpg --full-generate-key          # RSA 4096, no expiry or a long one
gpg --list-secret-keys --keyid-format=long
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
gpg --keyserver keys.openpgp.org  --send-keys <KEY_ID>
```

Central verifies the signature against a public keyserver, so the public half must be
published to at least one and propagation takes a few minutes.

**The decision that is not technical:** whose key signs chdb-io's artifacts. A personal key
means releases stop when that person is unavailable and the key follows them if they leave. A
project key shared through the CI secret store is the usual answer for an organisation, at the
cost of the key being only as protected as the secret store.

Whichever is chosen, CI needs two secrets — the ASCII-armoured private key and its passphrase —
and the release job needs `maven-gpg-plugin` bound to `verify`. Signing locally on a laptop for
the first release is fine and is the faster path to finding out whether the rest works.

### 1.3 The namespace — needs DNS access, approved automatically

`org.chdb` is **unclaimed**: Maven Central returns zero artifacts for it, and for `io.chdb`,
`com.chdb` and `io.github.chdb-io` as well. So there is no conflict to resolve, only a claim to
make.

Verification is by DNS. `chdb.org` resolves (13.248.169.48), so whoever administers that zone
adds a TXT record Sonatype names during registration, Sonatype's checker sees it, and the
namespace is granted. No human review, no waiting on a queue.

If DNS access is awkward, `io.github.chdb-io` is verified by creating a repository whose name
is the verification code under the `chdb-io` GitHub org — no DNS involved. It is the fallback,
not the preference: `org.chdb` is the coordinate already in every POM, README and document, and
changing it later is a breaking change for consumers.

**Action:** register at <https://central.sonatype.com>, start the `org.chdb` namespace claim,
and hand the TXT record to whoever runs `chdb.org` DNS.

### 1.4 Publishing limits — check the org's standing before the first release

Maven Central introduced organisation-level monthly limits on **file count**, **release size**
and **release count**, informational since 16 June 2026 and **enforced from 1 October 2026**.
The comparison is a rolling three-month average. Published percentile table:

| Percentile | File count | Release size (MB) | Release count |
|---|---|---|---|
| 90th | 1,167 | 78 | 7 |
| 95th | 2,805 | 247 | 13 |
| 97th | 5,188 | 522 | 19 |
| 99th | 17,724 | 1,808 | 50 |

Sonatype has not said which percentile is the line; the Usage Center shows an organisation
where it stands.

Our numbers, measured:

- **Release size: 537 MB of native packages** (112 + 128 + 130 + 167) plus about 10 MB of
  everything else, so **~547 MB per release**.
- **File count: roughly 100.** Six artifacts × (jar, sources, javadoc, pom) × (file + `.asc` +
  `.sha1` + `.md5`). Nowhere near even the 90th percentile of 1,167.
- **Release count: whatever the cadence is.**

Release size is the only metric in play, and cadence decides it because the comparison is a
three-month average:

| Cadence | 3-month average | Roughly |
|---|---|---|
| Monthly | 547 MB | above the 97th percentile |
| **Every 2 months** | **274 MB** | **just above the 95th** |
| **Every 3 months** | **182 MB** | **between the 93rd and 94th** |

At the 2–3 month cadence the other chDB bindings use, we land around the 93rd–95th percentile
on one of three metrics and near the median on the other two. That is a very different position
from the one a monthly cadence would put us in, and probably fine — but "probably" is doing work
there, because the actual threshold is not public.

**Action, and it is cheap:** log into the Usage Center at
<https://central.sonatype.com/publishing/usage> once the namespace exists and read the real
numbers. If they are tight, email `central-support@sonatype.com` before the first release
rather than after a rejection, describing the pattern — four platform packages of an embedded
database engine, published every two to three months. Sonatype's own documentation says
exceptions are granted as a permanent adjustment once the pattern is understood.

**One thing to confirm rather than assume:** from 1 October 2026, artifacts of a *commercial
nature* require Maven Central Publisher Pro regardless of volume. Whether chDB counts is a
question for whoever owns the relationship, not a technical one.

### 1.5 What to do first

Publish a **snapshot**, before any of the release polish. It costs an afternoon and is the only
thing that tests the parts nothing else can: whether the POMs resolve, whether the BOM does what
it should, whether a consumer declaring one platform package really gets a working driver, and
whether a 167 MB artifact uploads at all. Everything else in this track is easier to fix after
that has been seen to work.

---

## Track 2 — Licences

**This one needs a person with authority. The engineering part is small; the sign-off is not
ours to give.**

### 2.1 Our own code

`chdb-java` is Apache-2.0, written here, and `LICENSE` is in the repository. Nothing to do.

### 2.2 The redistributed engine — inherited, not new

Each platform package ships `libchdb.so` verbatim from a chdb-core release. That library
statically links the ClickHouse tree, which carries **156 contrib submodules**. A scan of their
licence files:

| Licence | Submodules |
|---|---|
| Apache-2.0 | 58 |
| MIT | 19 |
| LGPL | 7 |
| GPL | 8 |
| Boost, BSD, CC0, zlib, Unlicense | ~10 |

**The GPL count needs reading carefully rather than reacting to.** Most are dual-licensed and
the permissive half is the one in force — `zstd` is BSD-3 *or* GPL-2, `rocksdb` is Apache-2.0
*or* GPL-2, `liburing` is MIT *or* LGPL — and in `xz` the GPL covers the command-line tools
while `liblzma` itself is public domain. Several of the rest are not linked into a chDB build
at all.

The ones that do warrant a look are the LGPL components with no permissive alternative:
`libssh`, `mariadb-connector-c`, `libgsasl`, `lemmagen-c`, and `libnuma` from `numactl`. LGPL
carries obligations under **static** linking that it does not under dynamic, and `libchdb.so`
links everything statically — we measured it: its only shared dependencies are `libc`, `libm`,
`libdl`, `librt`, `libpthread` and the loader.

**The decisive point: this is not a new question.** The identical `libchdb.so` is already
redistributed on PyPI and npm by chdb-io. Whatever notice and offer-of-source obligations
attach, attach there too, and someone has already had to answer them — or has not, in which
case the Java package is not where that gets discovered.

So the task is **inherit and verify**, not analyse from scratch:

1. Ask chdb-core maintainers for the notice bundle their PyPI and npm releases ship, and what
   position was taken on the LGPL components. If there is one, mirror it.
2. If there is not, that is a finding about all three distributions and should be raised as
   such rather than solved inside this repository.

### 2.3 What we can produce mechanically

ClickHouse ships `utils/list-licenses/list-licenses.sh`, which walks `contrib` and emits the
inventory. Run it against the exact engine tag and ship the output in the package:

```bash
# in a chdb-core checkout at the pinned tag
utils/list-licenses/list-licenses.sh > /tmp/engine-licenses.txt
```

`scripts/build-native.sh` already creates `META-INF/licenses/` with our licence and a README
pointing at the engine's release, and `META-INF/sbom/bom.json` in CycloneDX form listing both
libraries with their checksums. The gap is that the SBOM lists the engine as one component
rather than enumerating what is inside it. Folding in the generated inventory closes that, and
is half a day.

### 2.4 Who decides what

| Item | Who | Approval needed |
|---|---|---|
| Apache-2.0 on our own code | already done | no |
| Generating and publishing a GPG key | any maintainer | no |
| `org.chdb` namespace claim | whoever runs `chdb.org` DNS | automatic once the TXT record is up |
| Shipping the engine's licence inventory | any maintainer | no |
| **Whether redistributing `libchdb.so` under Apache-2.0 discharges the LGPL obligations** | **chdb-io / ClickHouse legal** | **yes, and it is the only real gate in this track** |
| Whether chDB counts as "commercial nature" for Central | whoever owns the Sonatype relationship | yes |

---

## Track 3 — What is still untested

Closing these does not need anyone's permission, only time. Ordered by what a first release
would most regret missing.

| Gap | Why it matters | Effort |
|---|---|---|
| **Soak test, 1–6 hours** | An explicit gate. The 1000-query plateau is a proxy, not the thing. | a day, mostly waiting |
| **External consumption of a snapshot** | The gate says two external projects. Nothing has ever consumed this as a dependency. | depends on others |
| **OpenJ9 / Semeru smoke test** | The signal-handler guard is written against HotSpot's behaviour and has never met another JVM. | half a day |
| **`noexec` /tmp** | The loader has a specific message for it that has never been executed. | an hour, in a container |
| **Corrupted library, architecture mismatch** | Named in the plan; the checksum path is tested, these two are not. | half a day |
| **JPMS module path** | `Automatic-Module-Name` is set; nothing has run on the module path. | half a day |
| **Spring `JdbcTemplate`** | HikariCP, MyBatis and jOOQ are done and each found something. This one is lower yield. | half a day |
| **ShardingSphere** | Not closable here: `StandardJdbcUrlParser` rejects every `jdbc:chdb:` form, so no config reaches the driver. Pinned by `ShardingSphereIT` so it flips when upstream fixes it. | upstream |
| **The §3.3 batch-access benchmark** | The plan requires three approaches measured before the data path is fixed. One was chosen by reasoning. | a day |
| **Full-process ASan and LSan** | Blocked on a sanitizer build of chdb-core. Not ours to close. | upstream |

### What is already covered

253 tests across sixteen platform-and-JDK combinations, a 199-check native sanitizer harness,
UBSan over the whole suite, the full suite again on AlmaLinux 8 to demonstrate the glibc floor,
and a container test for the memory-limit gate. The type matrix, parameter binding, streaming
memory, cancellation, handle lifetime, the storage-path rule, five loader failure paths,
connection pooling, ClassLoader isolation and process exit behaviour all have tests that were
written to fail if the behaviour regressed — and several of them found real defects when first
run.

---

## The short answer

**Not ready, and the gating item is not code.**

Three things have to happen in this order, because each can invalidate the next:

1. **Confirm Maven Central will take it** — namespace claimed, Usage Center read, and an
   exception arranged if the numbers are tight. Enforcement starts 1 October 2026.
2. **Get the licence position on the engine** from whoever owns it, inherited from the existing
   PyPI and npm distributions rather than derived here.
3. **Publish a snapshot and have somebody consume it.** Everything else in Track 1 is easier to
   finish once that has worked once.

Track 3 can proceed in parallel and does not block; the soak test is the only item there that a
first release would be embarrassed to skip.
