# chDB public Maven repository gateway

This Vercel project gives RC consumers the stable, anonymous repository URL
`https://maven.chdb.io`. Maven files live in the public `chdb-maven-blob` store; the gateway
rewrites `/com/...` to that store.

The gateway deliberately disables Vercel's external-rewrite cache. Vercel Blob already caches
the immutable artifacts, while a second cache at the rewrite layer can incorrectly reuse one
HTTP Range response for a different range of the same large JAR.

## Deploy the gateway

The local directory is linked to the ClickHouse team project `chdb-maven`:

```console
cd deploy/vercel-maven
npx --yes vercel@latest build --prod --scope clickhouse
npx --yes vercel@latest deploy --prebuilt --prod --scope clickhouse
```

## Publish an RC

The `Maven RC release` workflow builds a `maven-repository` artifact. Download and publish it
from an account with Developer access to the ClickHouse Vercel team:

```console
gh run download RUN_ID --name maven-repository --dir /tmp/chdb-maven-repository
cd deploy/vercel-maven
npx --yes vercel@latest link --project chdb-maven --scope clickhouse
npx --yes vercel@latest env pull .env.local --environment=development
cd ../..
scripts/publish-rc-repository.sh /tmp/chdb-maven-repository
```

The environment file is ignored by Git. It supplies a short-lived Vercel OIDC credential; no
personal or long-lived Blob token is stored in GitHub. RC paths are immutable, and the upload
script refuses to overwrite an existing file. Before writing anything, it checks every destination
path. If an upload fails or is interrupted, it removes the blobs written by that run so the complete
RC can be retried safely. If rollback itself fails, remove the paths reported by the script before
retrying; the next preflight will refuse to overwrite them.
