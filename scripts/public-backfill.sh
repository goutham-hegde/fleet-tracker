#!/usr/bin/env bash
# Index archive files that are already in S3 into the public view's table.
#
# The S3 notification indexes every file the archiver writes from the moment it exists. This covers
# what it cannot: files archived before the notification was created, and any file whose indexing
# failed three times (Lambda retries an asynchronous invocation twice, then gives up).
#
# Safe to run as often as anybody likes. Every write the indexer makes is conditional on being newer
# than what the table holds, so indexing a file twice, or out of order, ends in the same table. The
# output says so: a second run reports every row as "already newer".
#
# The listing is done here, as whoever is signed in, so that the indexer's role never needs to list
# the archive: it reads the one file it is told about. Keys are sent as S3 stores them, in batches,
# using the indexer's second input shape ({"bucket": ..., "keys": [...]}); see IndexHandler.java.
#
# Usage: ./scripts/public-backfill.sh [YYYY-MM-DD]
#   With a date, only that UTC day's hours. Without, everything archived (positions keep 3 days,
#   the rest 30).
# Needs: a signed-in AWS CLI (aws login --region ap-south-1) that may invoke the function.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

require aws "winget install Amazon.AWSCLI"
require python "Python 3 (used to build the request JSON)"

DAY="${1:-}"
FUNCTION="fleet-tracker-public-index"
TOPICS=(position.events.v1 shipment.derived.v1 exceptions.v1)
BATCH=5

if [ -z "${ARCHIVE_BUCKET:-}" ]; then
  require terraform "winget install Hashicorp.Terraform"
  state_bucket="$(terraform -chdir="$REPO_ROOT/infra/bootstrap" output -raw state_bucket 2>/dev/null || true)"
  [ -n "$state_bucket" ] || die "No ARCHIVE_BUCKET set and no bootstrap state to read it from."
  terraform -chdir="$REPO_ROOT/infra/cloud" init -input=false -reconfigure \
    -backend-config="bucket=$state_bucket" >/dev/null
  ARCHIVE_BUCKET="$(terraform -chdir="$REPO_ROOT/infra/cloud" output -raw archive_bucket 2>/dev/null || true)"
  [ -n "$ARCHIVE_BUCKET" ] || die "The cloud stack has no archive bucket. Apply infra/cloud first."
fi

response="$(mktemp)"
trap 'rm -f "$response"' EXIT

files=0
written=0
older=0
unreadable=0

index_batch() {
  local payload
  payload="$(python -c 'import json,sys; print(json.dumps({"bucket": sys.argv[1], "keys": sys.argv[2:]}))' \
    "$ARCHIVE_BUCKET" "$@" | tr -d '\r')"
  # Synchronous, so the counts come back. The CLI's own read timeout is 60s by default, less than
  # the function's 120s, and a batch of position hours can take longer than a minute cold.
  aws lambda invoke --function-name "$FUNCTION" --cli-binary-format raw-in-base64-out \
    --cli-read-timeout 180 --payload "$payload" "$response" >/dev/null \
    || die "Invoking $FUNCTION failed"
  if grep -q '"errorMessage"' "$response"; then
    die "$FUNCTION reported an error: $(cat "$response")"
  fi
  # One line per file: key, written, older, unreadable.
  while IFS=$'\t' read -r key w o u; do
    files=$((files + 1)); written=$((written + w)); older=$((older + o)); unreadable=$((unreadable + u))
    if [ "$u" = 0 ]; then
      ok "$key: $w written, $o already newer"
    else
      warn "$key: $w written, $o already newer, $u unreadable"
    fi
  # tr: Python on Windows ends each line with \r\n, and bash's arithmetic refuses "0\r" as a number.
  done < <(python -c '
import json, sys
for f in json.load(open(sys.argv[1]))["files"]:
    print("\t".join([f["key"], str(f["written"]), str(f["older"]), str(f["unreadable"])]))
' "$response" | tr -d '\r')
}

for topic in "${TOPICS[@]}"; do
  prefix="archive/$topic/"
  [ -n "$DAY" ] && prefix="${prefix}dt=$DAY/"
  log "Listing s3://$ARCHIVE_BUCKET/$prefix"
  # Paged by the CLI; --output text gives the keys tab-separated, "None" when there are none.
  keys="$(aws s3api list-objects-v2 --bucket "$ARCHIVE_BUCKET" --prefix "$prefix" \
    --query 'Contents[].Key' --output text)"
  if [ -z "$keys" ] || [ "$keys" = "None" ]; then
    warn "nothing archived under $prefix"
    continue
  fi
  batch=()
  for key in $keys; do
    batch+=("$key")
    if [ "${#batch[@]}" -ge "$BATCH" ]; then
      index_batch "${batch[@]}"
      batch=()
    fi
  done
  [ "${#batch[@]}" -gt 0 ] && index_batch "${batch[@]}"
done

echo
ok "$files file(s) indexed: $written row(s) written, $older already newer, $unreadable unreadable line(s)"
