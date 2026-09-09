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
| `org.chdb` namespace | **not started** — needs a DNS TXT record |
| GPG key for the project | **not started** — needs a decision about whose key |
| Position on the LGPL components | **not started** — needs chdb-io |

---

## 1. The release profile

```bash
mvn -Prelease deploy
```

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

**Fallback if DNS access is awkward:** `io.github.chdb-io` is verified by creating a repository
under the `chdb-io` org whose name is the verification code, no DNS involved. It is a fallback,
not a preference: `org.chdb` is the coordinate in every POM, README and document here, and
changing it later is a breaking change for consumers.

## 4. Publishing limits — check before the first release

Central enforces organisation-level monthly limits from **1 October 2026**, compared against a
rolling three-month average. The published percentiles:

| Percentile | Files | Release size | Releases |
|---|---|---|---|
| 90th | 1,167 | 78 MB | 7 |
| 95th | 2,805 | 247 MB | 13 |
| 97th | 5,188 | 522 MB | 19 |
| 99th | 17,724 | 1,808 MB | 50 |

Ours, measured rather than estimated:

- **Release size ≈ 510 MB.** The four native packages are 98 + 114 + 129 + 166 MB (each jar
  tracks its engine tarball almost exactly), plus ~6 MB of driver javadoc and ~1 MB of
  everything else.
- **File count ≈ 100.** Six artifacts × (jar, sources, javadoc, pom) × (file + `.asc` + `.sha1`
  + `.md5`). Below even the 90th percentile of 1,167.
- **Release count** = the cadence.

Release size is the only metric in play, and cadence decides it:

| Cadence | 3-month average | Roughly |
|---|---|---|
| Monthly | 510 MB | just under the 97th |
| **Every 2 months** | **255 MB** | **just above the 95th** |
| **Every 3 months** | **170 MB** | **between the 93rd and 94th** |

At the two-to-three-month cadence the other chDB bindings use, we sit around the 93rd–95th
percentile on one metric of three and near the median on the other two. Probably fine — but
Sonatype has not published where the line is, so "probably" is doing work.

**Cheap insurance:** once the namespace exists, read the real numbers at
<https://central.sonatype.com/publishing/usage>. If they look tight, mail
`central-support@sonatype.com` *before* the first release rather than after a rejection,
describing the pattern: four platform packages of an embedded database engine, published every
two to three months. Sonatype grants these as a permanent adjustment once the pattern is
understood.

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

### The one open question

Reading the inventory rather than guessing at it: most components are permissive, and several
that a filename scan calls GPL are dual licensed with the permissive half in force — `zstd` is
BSD-3, `rocksdb` is Apache-2.0, `liburing` is MIT, `ittapi` is `GPL-2.0-only OR BSD-3-Clause`.

Six carry a copyleft licence with no permissive alternative:

```
lemmagen-c   libgsasl   libssh   mariadb-connector-c   numactl   xz     (LGPL)
```

LGPL carries obligations under **static** linking that it does not under dynamic, and
`libchdb.so` links everything statically — its only shared dependencies are `libc`, `libm`,
`libdl`, `librt`, `libpthread` and the loader.

Whether any of the six is actually *in* the binary cannot be settled from the binary: it is
stripped to 582 dynamic symbols, and static linking leaves nothing to inspect. `nm` finds no
`ssh_`, `mysql_`, `numa_`, `lzma_` or `gsasl_` symbols, which proves nothing either way. That
question belongs to the engine build configuration.

**And it is not a new question.** The identical `libchdb.so` is already redistributed on PyPI
and npm by chdb-io. Whatever notice and offer-of-source obligations attach, attach there too.
So the task is to **inherit and verify**, not to derive an answer here:

1. Ask chdb-core maintainers for the notice bundle their PyPI and npm releases ship, and the
   position taken on the six LGPL components. If there is one, mirror it.
2. If there is not, that is a finding about all three distributions, and should be raised as
   such rather than solved inside this repository.

`LicenseInventoryIT.copyleftComponentsAreTheKnownSet` pins the set to exactly those six, so a
seventh appearing in a future engine fails CI rather than shipping unnoticed.

## 6. Order of operations

Each step can invalidate the next, so:

1. **Claim the namespace and read the Usage Center.** Arrange an exception if the numbers look
   tight. Enforcement starts 1 October 2026.
2. **Get the licence position on the engine** from whoever owns it.
3. **Publish a snapshot and have somebody consume it.** This is the cheap step that finds
   everything the reactor build cannot: whether the POMs resolve from a repository, whether the
   BOM behaves, whether declaring one platform package yields a working driver, whether a
   166 MB artifact uploads. Tracked as issue #10.
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
| **Position on the six LGPL components** | **chdb-io / ClickHouse legal** | **yes — the only real gate** |
| Whether chDB is "commercial" for Central | whoever owns the Sonatype relationship | yes |
