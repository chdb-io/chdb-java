#!/usr/bin/env bash

# Upload an already-staged Maven repository to the public chDB Vercel Blob store.
# Run `vercel env pull .env.local --environment=development` from
# deploy/vercel-maven immediately before this command so the short-lived OIDC
# credential is fresh. Published RC paths are immutable: this script never
# overwrites an existing blob.

set -euo pipefail

usage() {
  printf 'Usage: %s [--dry-run] <maven-repository-directory>\n' "$(basename "$0")" >&2
  exit 2
}

DRY_RUN=false
if [[ ${1:-} == --dry-run ]]; then
  DRY_RUN=true
  shift
fi
[[ $# -eq 1 ]] || usage

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_DIR=$(cd "$SCRIPT_DIR/.." && pwd)
GATEWAY_DIR="$PROJECT_DIR/deploy/vercel-maven"
REPOSITORY_DIR=$(cd "$1" && pwd)
ENV_FILE=${CHDB_VERCEL_ENV_FILE:-"$GATEWAY_DIR/.env.local"}

[[ -d "$REPOSITORY_DIR/com/clickhouse/chdb" ]] || {
  printf 'Expected com/clickhouse/chdb below %s\n' "$REPOSITORY_DIR" >&2
  exit 1
}

if find "$REPOSITORY_DIR" -type l -print -quit | grep -q .; then
  printf 'Refusing to publish a repository containing symbolic links\n' >&2
  exit 1
fi

file_count=0
while IFS= read -r -d '' file; do
  relative=${file#"$REPOSITORY_DIR/"}
  if [[ ! "$relative" =~ ^com/clickhouse/chdb/[^/]+/[^/]+/[^/]+\.(jar|pom)(\.(md5|sha1|sha256|sha512))?$ ]]; then
    printf 'Refusing to publish a non-versioned Maven artifact path: %s\n' "$relative" >&2
    exit 1
  fi
  file_count=$((file_count + 1))
done < <(find "$REPOSITORY_DIR" -type f -print0)

(( file_count > 0 )) || {
  printf 'No files found below %s\n' "$REPOSITORY_DIR" >&2
  exit 1
}

if [[ "$DRY_RUN" == true ]]; then
  printf 'Validated %d immutable Maven files below %s\n' "$file_count" "$REPOSITORY_DIR"
  exit 0
fi

[[ -f "$ENV_FILE" ]] || {
  printf 'Missing %s. Run these commands first:\n' "$ENV_FILE" >&2
  printf '  cd %q\n' "$GATEWAY_DIR" >&2
  printf '  npx --yes vercel@latest link --project chdb-maven --scope clickhouse\n' >&2
  printf '  npx --yes vercel@latest env pull .env.local --environment=development\n' >&2
  exit 1
}

set -a
# shellcheck disable=SC1090
. "$ENV_FILE"
set +a

: "${BLOB_STORE_ID:?BLOB_STORE_ID is missing from $ENV_FILE}"
: "${VERCEL_OIDC_TOKEN:?VERCEL_OIDC_TOKEN is missing from $ENV_FILE}"

if command -v vercel >/dev/null 2>&1; then
  VERCEL=(vercel)
else
  VERCEL=(npx --yes vercel@latest)
fi

printf 'Uploading %d immutable files to store %s\n' "$file_count" "$BLOB_STORE_ID"

while IFS= read -r -d '' file; do
  relative=${file#"$REPOSITORY_DIR/"}
  "${VERCEL[@]}" blob put "$file" \
    --pathname "$relative" \
    --access public \
    --add-random-suffix false \
    --allow-overwrite false \
    --cache-control-max-age 31536000 \
    --multipart true \
    --store-id "$BLOB_STORE_ID" \
    --oidc-token "$VERCEL_OIDC_TOKEN"
done < <(find "$REPOSITORY_DIR" -type f -print0)

printf 'Published %d files. Verify the RC from https://maven.chdb.io with a fresh Maven cache.\n' "$file_count"
