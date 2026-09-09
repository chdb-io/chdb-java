#!/usr/bin/env bash
#
# Answers one question: does the tag `v<version>` exist, and does it point at <commit>?
#
# A release is a commit that carries the release version in its POMs plus a tag on that commit,
# so that `git show <tag>` can say what was published. This is what makes that true rather than
# documented, and .github/workflows/release.yml runs it twice on purpose:
#
#   - in `preflight`, so a mistake costs nothing -- it fails before 1.3 GB of native libraries
#     are built and moved across the network;
#   - in `deploy`, immediately before `mvn -Prelease deploy`, because between the two there is
#     four-platform staging (ten minutes and more) and a manual approval gate, and a tag can be
#     force-moved or deleted inside that window. Publishing is irreversible; a check that ran a
#     quarter of an hour before the upload does not describe the upload.
#
# Neither call replaces protecting the release tags so they cannot be moved at all. This closes
# the window down to the seconds between the check and the upload; only an immutable tag closes
# it entirely. See docs/release-readiness.md, running order step 8.
#
# Usage:
#   scripts/check-release-tag.sh <owner/repo> <version> <commit-sha>
#
# Needs `gh` authenticated for a read of the git ref namespace, and `jq`. Read-only: it makes
# GET requests and nothing else.

set -euo pipefail

fail() {
  # Annotated when a workflow is reading, plain when a person is.
  if [ -n "${GITHUB_ACTIONS:-}" ]; then
    printf '::error::%s\n' "$*"
  else
    printf 'check-release-tag: %s\n' "$*" >&2
  fi
  exit 1
}

REPO="${1:-}"
VERSION="${2:-}"
COMMIT="${3:-}"
if [ -z "$REPO" ] || [ -z "$VERSION" ] || [ -z "$COMMIT" ]; then
  printf 'usage: %s <owner/repo> <version> <commit-sha>\n' "$0" >&2
  exit 2
fi

TAG="v${VERSION}"

# Through the API rather than git: actions/checkout fetches the ref that triggered the run and
# not the tag namespace, so `git rev-parse` in a workflow would report a tag that is simply not
# in the local clone as missing -- and would be wrong in the one direction that matters.
if ! REF=$(gh api "repos/${REPO}/git/ref/tags/${TAG}" 2>/dev/null); then
  fail "no tag ${TAG} in ${REPO}. A release is a commit carrying the release version in its POMs plus a tag on that commit; create the tag first."
fi

TYPE=$(printf '%s' "$REF" | jq -r '.object.type')
SHA=$(printf '%s' "$REF" | jq -r '.object.sha')

# An annotated tag -- which is what `git tag -a` creates, and what the runbook in
# docs/release-readiness.md tells you to create -- has a ref pointing at a *tag object*, not at
# a commit. Without this hop the check would reject every release cut the recommended way.
if [ "$TYPE" = "tag" ]; then
  SHA=$(gh api "repos/${REPO}/git/tags/${SHA}" --jq '.object.sha')
fi

printf 'check-release-tag: %s -> %s (%s ref)\n' "$TAG" "$SHA" "$TYPE"
printf 'check-release-tag: this run -> %s\n' "$COMMIT"

if [ "$SHA" != "$COMMIT" ]; then
  fail "${TAG} points at ${SHA}, but this run is on ${COMMIT}. Release ${VERSION} from the commit its tag names, or move the tag and start again."
fi

printf 'check-release-tag: %s names this commit\n' "$TAG"
