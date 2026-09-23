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

FILES=()
RELATIVE_PATHS=()
while IFS= read -r -d '' file; do
  relative=${file#"$REPOSITORY_DIR/"}
  if [[ ! "$relative" =~ ^com/clickhouse/chdb/[^/]+/[^/]+/[^/]+\.(jar|pom)(\.(md5|sha1|sha256|sha512))?$ ]]; then
    printf 'Refusing to publish a non-versioned Maven artifact path: %s\n' "$relative" >&2
    exit 1
  fi
  FILES+=("$file")
  RELATIVE_PATHS+=("$relative")
done < <(find "$REPOSITORY_DIR" -type f -print0)

file_count=${#FILES[@]}
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

BLOB_AUTH_ARGS=(
  --store-id "$BLOB_STORE_ID"
  --oidc-token "$VERCEL_OIDC_TOKEN"
)

blob_exists() {
  local target=$1
  local output

  if ! output=$("${VERCEL[@]}" blob list \
    --prefix "$target" \
    --limit 1000 \
    --mode expanded \
    --no-color \
    "${BLOB_AUTH_ARGS[@]}"); then
    printf 'Failed to check whether %s already exists\n' "$target" >&2
    return 2
  fi

  awk -v target="$target" '
    {
      for (field = 1; field <= NF; field++) {
        if ($field == target) {
          found = 1
        }
      }
    }
    END { exit found ? 0 : 1 }
  ' <<<"$output"
}

printf 'Checking %d destination paths before upload\n' "$file_count"
conflict_count=0
for relative in "${RELATIVE_PATHS[@]}"; do
  if blob_exists "$relative"; then
    printf 'Refusing to overwrite existing blob: %s\n' "$relative" >&2
    conflict_count=$((conflict_count + 1))
  else
    result=$?
    (( result == 1 )) || exit "$result"
  fi
done

(( conflict_count == 0 )) || {
  printf 'Preflight found %d existing destination path(s); nothing was uploaded\n' \
    "$conflict_count" >&2
  exit 1
}

UPLOADED_PATHS=()
uploaded_count=0
rollback() {
  local status=$1
  local rollback_failed=false

  trap - ERR HUP INT TERM
  set +e

  if (( uploaded_count > 0 )); then
    printf 'Upload did not complete; removing %d blob(s) written by this run\n' \
      "$uploaded_count" >&2

    for relative in "${UPLOADED_PATHS[@]}"; do
      if ! "${VERCEL[@]}" blob del "$relative" "${BLOB_AUTH_ARGS[@]}"; then
        printf 'Rollback failed for %s; remove it before retrying\n' "$relative" >&2
        rollback_failed=true
      fi
    done
  fi

  if [[ "$rollback_failed" == true ]]; then
    printf 'Rollback was incomplete; the next preflight will refuse to publish over remaining blobs\n' >&2
  fi

  exit "$status"
}

trap 'rollback $?' ERR
trap 'rollback 129' HUP
trap 'rollback 130' INT
trap 'rollback 143' TERM

printf 'Uploading %d immutable files to store %s\n' "$file_count" "$BLOB_STORE_ID"

for index in "${!FILES[@]}"; do
  file=${FILES[$index]}
  relative=${RELATIVE_PATHS[$index]}
  "${VERCEL[@]}" blob put "$file" \
    --pathname "$relative" \
    --access public \
    --add-random-suffix false \
    --allow-overwrite false \
    --cache-control-max-age 31536000 \
    --multipart true \
    "${BLOB_AUTH_ARGS[@]}"
  UPLOADED_PATHS+=("$relative")
  uploaded_count=$((uploaded_count + 1))
done

trap - ERR HUP INT TERM

printf 'Published %d files. Verify the RC from https://maven.chdb.io with a fresh Maven cache.\n' "$file_count"
