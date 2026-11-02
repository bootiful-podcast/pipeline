#!/usr/bin/env bash
# pushes bootiful podcast's apps to cloud foundry. see README.md.
#
#   bin/deploy.sh [--vars vars/<foundation>.yml] [app...]
#
# with no apps, it pushes every app in manifest.yml, from its image. cf has to be logged in
# already, as a user that can push to the org and space the vars file names.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
VARS_FILE="$ROOT_DIR/vars/typical.yml"
APPS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --vars) VARS_FILE="$2"; shift 2 ;;
    *) APPS+=("$1"); shift ;;
  esac
done
# every app in the manifest, in order
ALL_APPS=()
while read -r app; do ALL_APPS+=("$app"); done < <(awk '/^  - name:/ {print $3}' "$ROOT_DIR/manifest.yml")
[ ${#APPS[@]} -gt 0 ] || APPS=("${ALL_APPS[@]}")

# the images are private. cf hands the cell a pull credential: username _json_key, and a json
# key of the read-only service account bootiful-podcast-cf-puller, kept in the bitwarden
# item bootiful-podcast-cf-image-puller-key.
if [ -z "${CF_DOCKER_PASSWORD:-}" ]; then
  bw unlock --check >/dev/null 2>&1 </dev/null || { echo "bitwarden is locked: run bw-unlock first" >&2; exit 1; }
  CF_DOCKER_PASSWORD="$(bw get item bootiful-podcast-cf-image-puller-key </dev/null | jq -r '.notes // .login.password // empty')"
  export CF_DOCKER_PASSWORD
fi
jq -e '.type == "service_account"' <<<"$CF_DOCKER_PASSWORD" >/dev/null 2>&1 ||
  { echo "CF_DOCKER_PASSWORD is not a service account's json key" >&2; exit 1; }

var(){ awk -v k="$1:" '$1 == k {print $2}' "$VARS_FILE"; }
REGISTRY="$(var registry)"
ORG="$(var org)"
SPACE="$(var space)"

cf oauth-token >/dev/null || { echo "cf is not logged in" >&2; exit 1; }
cf target -o "$ORG" -s "$SPACE"

DIGESTS_FILE="$(mktemp)"
trap 'rm -f "$DIGESTS_FILE"' EXIT

# pin what :latest is right now, so a restart months from now runs the image that was deployed
# and not whatever was pushed since. cf interpolates the whole manifest even to push one app, so
# every app needs its digest. tags list needs only Artifact Registry access.
for app in "${ALL_APPS[@]}"; do
  DIGEST="$(gcloud artifacts docker tags list "$REGISTRY/$app" --format=json |
    jq -er '[.[] | select(.tag | endswith("/tags/latest")) | .version | split("/")[-1]] |
      if length == 1 then .[0] else error("expected exactly one latest tag") end')"
  echo "$app-digest: $DIGEST" >>"$DIGESTS_FILE"
done

for app in "${APPS[@]}"; do
  echo "------------------ $app $(awk -v k="$app-digest:" '$1 == k {print $2}' "$DIGESTS_FILE")"
  cf push "$app" -f "$ROOT_DIR/manifest.yml" --vars-file "$VARS_FILE" --vars-file "$DIGESTS_FILE" \
    --strategy rolling
done
