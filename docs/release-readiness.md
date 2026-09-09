# Getting to a published release

What stands between the current `main` and an artifact on Maven Central, who has to do each
part, and which parts need someone else's approval.

Written as three separate tracks because they proceed independently and have different
blockers: **signing and publishing** is mechanical and self-service, **the namespace** needs
DNS access, and **licences** need a person with authority to say yes.

`docs/publishing.md` is the reference — the release profile, the namespace claim, the
publishing limits, the licence inventory — and is the only place any of those numbers live.
This file is the **running order and the state**: what is left, in what sequence, and how to
know each step worked. Where the two could disagree, this one points there rather than
restating.

---

## Running order

The tracks below say *what* and *who*. This says *when*, because each step can invalidate the
next. Eight steps. Four of them need somebody outside this repository — 1 needs DNS access to
`chdb.org`, 2 may need Sonatype support, 5 needs chdb-io, and 6 needs two projects willing to
depend on us — so those are the ones to start asking about early. Steps 3, 4, 7 and 8 are
self-service.

### 1. Claim the `org.chdb` namespace — needs DNS access to `chdb.org`

Register at <https://central.sonatype.com>, start a namespace claim for `org.chdb`, and hand
the TXT record Sonatype names to whoever administers the `chdb.org` zone. Verified
automatically, no human review, no queue. §1.3 has the detail, including why there is no
fallback.

**Done when:** `org.chdb` shows as verified in the portal.

### 2. Read the Usage Center, and ask for an exception if the numbers are tight

Enforcement of the organisation-level publishing limits starts 1 October 2026.
`docs/publishing.md` §4 has our measured numbers and the text of the case to make.

**Done when:** <https://central.sonatype.com/publishing/usage> has been read, and **either** the
numbers there are comfortably inside the thresholds **or** Sonatype has granted an exception
that covers the numbers we actually publish.

Not "an email has been sent", and not "Sonatype replied" — a refusal is a reply, and so is a
request for more detail. Either of those leaves the release liable to be rejected at upload
time, which is the thing this step exists to find out about in advance. If the answer is no,
that is a real finding and the next question is what to do about ~510 MB in a release month,
not whether to carry on.

### 3. Decide whose GPG key signs, generate it, and load the CI secrets

§1.2 for the decision, which is not technical. Then four secrets, in a GitHub environment named
`maven-central` so `.github/workflows/release.yml`'s deploy job can require a reviewer:

| Secret | What |
|---|---|
| `GPG_PRIVATE_KEY` | the ASCII-armoured private key |
| `GPG_PASSPHRASE` | its passphrase |
| `CENTRAL_TOKEN_USERNAME` | portal token username |
| `CENTRAL_TOKEN_PASSWORD` | portal token password |

**Done when:** `release.yml` run with `channel=snapshot` and `dry_run=true` produces a `.asc`
for every artifact. That exercises staging on all four platforms, signing and packaging, and
uploads nothing.

### 4. Enable SNAPSHOTs for the namespace and publish a snapshot

Snapshot publishing is opt-in per namespace — **Enable SNAPSHOTs** in the portal, or the upload
is rejected. Then `release.yml` with `channel=snapshot` and `dry_run=false`.

Worth doing before any release polish. §1.5 says what it answers that nothing local can.

**Done when:** the snapshot resolves from
`https://central.sonatype.com/repository/maven-snapshots/` in a project that is not this one.

### 5. Get the licence position on the engine — needs chdb-io

Track 2. Inherit the position taken for the existing PyPI and npm distributions of the same
`libchdb.so` rather than deriving one here.

**Done when:** there is a written answer from someone who can give it, and whatever notice it
requires ships in `META-INF/licenses/`.

### 6. Have two external projects consume the snapshot

The V1 gate, and the reason step 4 comes before the release polish. Candidates worth asking: a
chDB cookbook example, the ADBC driver work, any internal tool that wants an embedded
analytical engine on the JVM.

**Done when:** two projects outside this repository depend on a published coordinate, and what
broke is written down. The value of this gate is that list.

### 7. Run the concurrent soak

Work plan §5.11: one to six hours of *concurrent* querying. Blocking — see §3.1, which says why
a single-threaded run does not discharge it.

**Done when:** the run is clean and the RSS/PSS slope over its length is written down.

### 8. Cut the release

```bash
# The version lives in git, not in CI: the tag has to name bytes a commit describes.
mvn versions:set -DnewVersion=<release version> -DgenerateBackupPoms=false
git commit -am "release <release version>"
git tag -a v<release version> -m "..."
git push origin main                        # let build.yml go green on the commit first
git push origin v<release version>
```

`release.yml` then stages each platform on its own runner, reassembles the four in one deploy
job, signs, and uploads a bundle that **waits in the portal**. Confirm it by hand at
<https://central.sonatype.com/publishing/deployments>: a release cannot be unpublished.

Before it stages anything it refuses to continue unless the POMs are at a non-`SNAPSHOT`
version, a tag named `v<that version>` exists and points at the commit being built, and
`build` concluded successfully for that same commit. The tag check applies to a manual
`channel=release` run too, so there is no path that publishes an untagged commit, and it runs
a second time as the last step before Maven publishes.

**Protect the release tags, once, before the first release.** The second check exists because
staging takes ten minutes and more and the `maven-central` approval gate can hold a run for
much longer, and a tag can be force-moved or deleted inside that window — so a run could
otherwise publish one commit under a version whose tag now names another, permanently. Checking
twice shrinks that window to the seconds between the last check and the upload; it does not
close it. Closing it is an organisational setting rather than a workflow one:

- a tag protection rule on `v*` in the repository's rulesets, forbidding update and deletion,
  which makes a release tag immutable once pushed;
- and, if the plan is releases from `main` only, restricting who can create those tags.

Do this as part of step 3, when the `maven-central` environment is being set up, because it is
the same conversation with the same person. The workflow does not verify that the rule exists —
it cannot tell a protected tag from an unprotected one — so it is on this checklist rather than
in CI.

Afterwards, the ordinary "back to development" commit returns the POMs to a `-SNAPSHOT`.

---

## Track 1 — Signing and publishing mechanics

**Nobody's approval needed. All of it can be done by whoever holds the repository and one DNS
record.**

### 1.1 What Maven Central requires, and where we stand

Checked against the current `main`. An earlier version of this table listed all of the first
seven rows as absent; they were built in #13 and this is the state after it.

| Requirement | Status |
|---|---|
| `sources` JAR | **done** — a real one for `chdb-jdbc`, empty by construction for the native packages |
| `javadoc` JAR | **done**, same split |
| GPG signature (`.asc`) per file | **done**, verified end to end with a disposable key |
| `<scm>` in the POM | **done** |
| `<developers>` in the POM | **done** |
| `<licenses>` | **done** |
| `<name>`, `<description>`, `<url>` | **done** |
| Publishing plugin | **done** — `central-publishing-maven-plugin`, `autoPublish=false` |
| Third-party licence inventory | **done**, generated from the engine and drift-tested |
| Refusal to package an unstaged or `--local-engine` native module | **done** |
| A way to stage all four platforms for one release | **done** — §1.6 |
| Proof the artifacts work when consumed from a repository | **done locally** — §1.5 |
| Portal token in a CI secret | **not started** — running order step 3 |
| A GPG key that is the project's rather than a laptop's | **not started** — §1.2 |
| `org.chdb` namespace | **not started**, and the one item with no workaround — §1.3 |

So the mechanics are finished and the remaining items in this track are two credentials and
one DNS record. `docs/publishing.md` §1 is the runbook for what the profile does.

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

Whichever is chosen, CI needs two secrets — the ASCII-armoured private key and its passphrase.
`maven-gpg-plugin` is already bound to `verify` in the release profile, and
`.github/workflows/release.yml` imports the key through `setup-java`, so the only outstanding
part is the key itself and the four secrets in running-order step 3. Signing locally on a
laptop for the first release is fine and is the faster path to finding out whether the rest
works.

### 1.3 The namespace — needs DNS access, approved automatically

`org.chdb` is **unclaimed**: Maven Central returns zero artifacts for it, and for `io.chdb`,
`com.chdb` and `io.github.chdb-io` as well. So there is no conflict to resolve, only a claim to
make.

Verification is by DNS. `chdb.org` resolves (13.248.169.48), so whoever administers that zone
adds a TXT record Sonatype names during registration, Sonatype's checker sees it, and the
namespace is granted. No human review, no waiting on a queue.

**There is no fallback, contrary to what an earlier version of this section said.** It offered
`io.github.chdb-io`, verified by creating a repository under the `chdb-io` GitHub org. That is
not available; Sonatype's wording, quoted in `docs/publishing.md` §3, is that only the GitHub
username used to sign up is supported, so `io.github.<organisation>` cannot be registered
automatically. GitHub verification would grant `io.github.<maintainer-username>` — a personal
coordinate, and the wrong thing to publish an organisation's driver under.

**So DNS access to `chdb.org` is a hard prerequisite**, not a convenience, and it is worth
confirming who has it before anything else in this document is started. `org.chdb` is also the
coordinate already in every POM, README and document; changing it later is a breaking change
for consumers.

**Action:** register at <https://central.sonatype.com>, start the `org.chdb` namespace claim,
and hand the TXT record to whoever runs `chdb.org` DNS.

### 1.4 Publishing limits — check the org's standing before the first release

Maven Central introduced organisation-level limits on **file count**, **release size** and
**release count**, informational since 16 June 2026 and **enforced from 1 October 2026**.

**The numbers, the percentile table and the case to make are in `docs/publishing.md` §4 and
only there.** An earlier version of this section reproduced them and got the measurement window
wrong — it described a rolling three-month average and then reasoned that a 2–3 month cadence
divides the release size by two or three. Sonatype is explicit that each metric resets at the
start of the month and is compared directly against that month's threshold, not averaged, so
a slower cadence does not make a release month cheaper; it makes the other months empty. Two
copies of a number is how that kind of error survives, which is why this file now keeps none of
them.

The shape of the answer, from that section: release size is the only metric in play, we are
around the 97th percentile on it in a release month and near the floor on the other two, and
what enforcement actually looks for is sustained rather than occasional usage — which an
event-driven release every two to three months is not.

**Action, and it is cheap:** log into the Usage Center at
<https://central.sonatype.com/publishing/usage> once the namespace exists and read the real
numbers. If they are tight, email `central-support@sonatype.com` before the first release
rather than after a rejection, with what the exception form asks for; `docs/publishing.md` §4
has the wording. Sonatype's own documentation says exceptions are granted as a permanent
adjustment once the pattern is understood.

**One thing to confirm rather than assume:** from 1 October 2026, artifacts of a *commercial
nature* require Maven Central Publisher Pro regardless of volume. Whether chDB counts is a
question for whoever owns the relationship, not a technical one.

### 1.5 What to do first, and what it is still for

Publish a **snapshot**, before any of the release polish. That has not changed. What has changed
is why: most of what a snapshot was going to find can now be found locally, in a few minutes,
without publishing anything.

```bash
scripts/verify-consumer.sh
```

It deploys to a throwaway `file://` repository, builds a consumer project outside this checkout
with its own empty local repository, imports the BOM, declares exactly one platform package with
no version, and runs a query. It asserts that the POMs resolve from a repository, that the BOM
supplies the versions, that `chdb-jdbc` arrives transitively, that the libraries are unpacked
from the JAR rather than read from `target/`, that their checksums match what
`scripts/build-native.sh` staged, and that the "no platform package" and "wrong platform
package" errors each name the coordinate the machine actually needs. `build` runs it on all
four platforms, so the answer cannot go stale between releases.

**What the gate found on its first run**, which is the part issue #10 asks for:

- The BOM import and the transitive `chdb-jdbc` dependency were already right, and are now
  asserted rather than assumed.
- **A snapshot deploy does not write the filenames a reactor build uses.** It writes
  `chdb-jdbc-<version>-<timestamp>-1.jar` plus a `maven-metadata.xml` that maps `-SNAPSHOT` onto
  that timestamp; without the metadata a consumer gets a 404 for a version that is demonstrably
  there. Nothing exercised that indirection before. The consumer's *local* repository then ends
  up holding both names at once — the timestamped file it downloaded and a `-SNAPSHOT.jar`
  beside it — so anything that looks for the driver by file name is depending on a resolver
  detail. `scripts/verify-consumer.sh` locates it by coordinate, `org/chdb/chdb-jdbc/`, for
  that reason.
- **"Declared the wrong platform package" was indistinguishable from "declared none".** Both
  produced the same message — three locations searched, none found, here is the coordinate to
  add. The advice is right either way, but somebody looking at
  `chdb-native-linux-x86_64-gnu` in their own POM while being told to add a platform package
  has no way to work out what they did wrong. The loader now names the `chdb-native-*` packages
  that are on the classpath when they are for another machine. This was the one real defect the
  gate found.
- **`central-publishing-maven-plugin` was configured with a parameter it does not have.**
  `waitUntilValidated` is not a parameter of version 0.11.0; the mojo declares `waitUntil`,
  `waitMaxTime`, `waitPollingInterval` and `waitForPublishCompletion`. Maven drops configuration
  elements a mojo does not declare rather than failing on them, so it had been doing nothing.
  Now `<waitUntil>VALIDATED</waitUntil>`, which is also the default — so the behaviour was
  right by accident, and a release run reporting "validated" now means it.

**What is left for a real snapshot** is one thing, and it is the reason to still do it early:
whether a 98–166 MB artifact survives an upload and a download over the network. A `file://`
deploy is a copy, so it cannot fail the way a transfer can.

### 1.6 Assembling all four platforms — done, in CI

A release needs all four platform packages staged in one working directory and no machine can
produce them: `scripts/build-native.sh` refuses to cross-build on purpose, and `macos-x86_64`
needs an Intel Mac. `.github/workflows/release.yml` stages each platform on its own runner,
uploads `target/native/` as an artifact, and downloads all four into one deploy job that runs
`mvn -Prelease deploy`. It publishes snapshots from the same jobs, so the release path is
exercised long before a release depends on it.

The workflow's header records the three decisions behind it, including the measured answer on
build reproducibility: the engine half is reproducible because it is pinned by SHA-256, and the
shim's `__text`, `__cstring`, `__const` and `__data` reproduce byte for byte while `LC_UUID`,
four debug-map timestamps and the ad-hoc code signature do not. Nothing in the release gate
depends on that, and the workflow records the SHA-256 of everything it shipped instead.

---

## Track 2 — Licences

**This one needs a person with authority. The engineering part is small; the sign-off is not
ours to give.**

### 2.1 Our own code

`chdb-java` is Apache-2.0, written here, and `LICENSE` is in the repository. Nothing to do.

### 2.2 The redistributed engine — inherited, not new

Each platform package ships `libchdb.so` verbatim from a chdb-core release, and that library
statically links the ClickHouse tree.

**The inventory of what is inside it, and the licence of each component, is in
`docs/publishing.md` §5 and only there.** An earlier version of this section counted 156
contrib submodules by scanning their licence files and found seven LGPL and eight GPL. That
method has since been replaced by querying the binary we actually ship —
`SELECT DISTINCT library_name, license_type FROM system.licenses`, which ClickHouse generates
at build time — and the answer is 968 components, of which twelve carry a copyleft licence
with no permissive alternative, in two families rather than one. A filename scan calls every
dual-licensed component GPL and describes a source checkout rather than an artifact, so the
older numbers should not be reasoned from; the result is committed at
`licenses/engine-third-party-<version>.tsv`, shipped in the package, and guarded by
`LicenseInventoryIT`, which fails if it stops matching the engine.

What is unchanged, and is the point of this track: LGPL carries obligations under **static**
linking that it does not under dynamic, and `libchdb.so` links everything statically — its only
shared dependencies are `libc`, `libm`, `libdl`, `librt`, `libpthread` and the loader.

**The decisive point: this is not a new question.** The identical `libchdb.so` is already
redistributed on PyPI and npm by chdb-io. Whatever notice and offer-of-source obligations
attach, attach there too, and someone has already had to answer them — or has not, in which
case the Java package is not where that gets discovered.

So the task is **inherit and verify**, not analyse from scratch:

1. Ask chdb-core maintainers for the notice bundle their PyPI and npm releases ship, and what
   position was taken on the copyleft components. If there is one, mirror it.
2. If there is not, that is a finding about all three distributions and should be raised as
   such rather than solved inside this repository.

### 2.3 What we already produce mechanically

`scripts/build-native.sh` creates `META-INF/licenses/` with our licence, a README pointing at
the engine's release and the generated third-party inventory, plus `META-INF/sbom/bom.json` in
CycloneDX form listing both libraries with their checksums. This section previously described
the inventory as a gap and estimated half a day for it; it is done, and
`docs/publishing.md` §5 says how to regenerate it after an engine bump.

### 2.4 Who decides what

| Item | Who | Approval needed |
|---|---|---|
| Apache-2.0 on our own code | already done | no |
| Generating and publishing a GPG key | any maintainer | no |
| `org.chdb` namespace claim | whoever runs `chdb.org` DNS | automatic once the TXT record is up |
| Shipping the engine's licence inventory | already done | no |
| **Whether redistributing `libchdb.so` under Apache-2.0 discharges the obligations of the twelve copyleft components** | **chdb-io / ClickHouse legal** | **yes, and it is the only real gate in this track** |
| Whether chDB counts as "commercial nature" for Central | whoever owns the Sonatype relationship | yes |

---

## Track 3 — What is still untested

Two of these **block a release** and the rest do not, so they are in separate tables. An
earlier version of this section put the soak test in one table with the optional work and then
described the whole table as parallel and non-blocking, which let an operator read past a
required gate on the way to the sentence saying the gate was required.

Everything in §3.2 needs only time and nobody's permission. Of the two in §3.1, the soak needs
only time; external consumption needs other people, which is why it is also running-order
step 6.

### 3.1 Blocking — a release does not go out without these

| Gate | What it requires | Effort |
|---|---|---|
| **Concurrent soak, 1–6 hours** | Work plan §5.11 asks for a *concurrent* soak, not a single-threaded loop: several connections on several threads for the whole window, with the RSS/PSS slope recorded across it. A sequential run measures allocator behaviour and nothing else. The milestone it is there to discharge is that cancel, timeout, early close and cascading `Connection` close "leak nothing and never deadlock" — and a deadlock cannot occur, so cannot be ruled out, in a single thread. Existing coverage is two proxies, each missing one half: the 1000-query RSS plateau is long but sequential, and `HikariPoolIT` is concurrent but minutes rather than hours. | a day, mostly waiting |
| **External consumption of a published artifact** | The gate says two projects outside this repository. `scripts/verify-consumer.sh` closes the mechanical half locally (§1.5); this is the half that needs other people, and running-order step 6 is where it sits. | depends on others |

### 3.2 Not blocking — worth doing, in this order

Ordered by what a first release would most regret missing.

| Gap | Why it matters | Effort |
|---|---|---|
| **Upload and download of a 98–166 MB artifact** | The one question on the consumption list that a `file://` repository cannot reach, because a copy cannot fail the way a transfer can. Comes free with running-order step 4. | free with the snapshot |
| **OpenJ9 / Semeru smoke test** | The signal-handler guard is written against HotSpot's behaviour and has never met another JVM. | half a day |
| **`noexec` /tmp** | The loader has a specific message for it that has never been executed. | an hour, in a container |
| **Corrupted library, architecture mismatch** | Named in the plan; the checksum path is tested, these two are not. | half a day |
| **JPMS module path** | `Automatic-Module-Name` is set; nothing has run on the module path. | half a day |
| **Spring `JdbcTemplate`** | HikariCP, MyBatis and jOOQ are done and each found something. This one is lower yield. | half a day |
| **ShardingSphere** | Not closable here: `StandardJdbcUrlParser` rejects every `jdbc:chdb:` form, so no config reaches the driver. Pinned by `ShardingSphereIT` so it flips when upstream fixes it. | upstream |
| **The §3.3 batch-access benchmark** | The plan requires three approaches measured before the data path is fixed. One was chosen by reasoning. | a day |
| **Full-process ASan and LSan** | Blocked on a sanitizer build of chdb-core. Not ours to close. | upstream |
| **Bit-identical shim builds** | Measured, and the answer is "no, by 116 bytes of build metadata" — `.github/workflows/release.yml`'s header has the breakdown. Nothing in the release gate depends on it. | not planned |

### What is already covered

266 tests — 156 unit and 110 integration, counted from a run rather than estimated — across
sixteen platform-and-JDK combinations, a 199-check native sanitizer harness, UBSan over the
whole suite, the full suite again on AlmaLinux 8 to demonstrate the glibc floor, and a consumer
that resolves the driver from a repository rather than from the reactor.

**The memory-limit gate is covered, and it is worth naming where** because it is not a JUnit
test and reading the test list alone would suggest otherwise. `scripts/run-memory-limit-test.sh`
runs a probe in an AlmaLinux 8 container under `docker --memory=2g`, with `-Xmx256m` and a
`groupArray` over 200 million values — so all three limits the work plan asks to be combined
(`-Xmx`, the cgroup limit and chDB's `max_memory_usage`) are in play at once. It runs both with
an explicit `max_memory_usage=200MB` and without one, and requires a catchable error code 241
in both; a completed query, a different error, or a process that dies without reporting are all
failures. `.github/workflows/build.yml` invokes it in the `oldest-supported-linux` job on both
`linux-x86_64-gnu` and `linux-aarch64-gnu`, unconditionally, on every push. (`docs/v1-progress.md`
phase 10 still lists a "cgroup + `max_memory_usage` matrix" as outstanding; that line is stale,
not a second opinion.)

The type matrix, parameter binding, streaming memory, cancellation, handle lifetime, the
storage-path rule, five loader failure paths, connection pooling — `HikariPoolIT`, a real
HikariCP 5.1.0 pool over the shared in-memory database — ClassLoader isolation and process exit
behaviour all have tests that were written to fail if the behaviour regressed, and several of
them found real defects when first run.

---

## The short answer

**Not ready, and the gating items are not code.** Everything mechanical is finished: the release
profile, the signing, the licence inventory, the four-platform assembly, and a local proof that
the artifacts work when consumed from a repository rather than from the reactor.

What is left is a DNS record on `chdb.org`, a decision about whose key signs, a licence answer
from whoever owns the engine, two projects willing to depend on a snapshot, and a concurrent
soak. The **Running order** at the top of this file is the sequence, because each of those can
invalidate the next; §3.1 is the part of Track 3 that blocks, and §3.2 is the part that does
not.
