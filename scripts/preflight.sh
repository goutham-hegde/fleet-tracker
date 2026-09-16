#!/usr/bin/env bash
# Verify every prerequisite is present and report its version.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

log "Checking prerequisites"

fail=0
check() {
  local name="$1" cmd="$2"
  if command -v "$name" >/dev/null 2>&1; then
    ok "$(printf '%-10s %s' "$name" "$(eval "$cmd" 2>&1 | head -1)")"
  else
    warn "$(printf '%-10s MISSING' "$name")"
    fail=1
  fi
}

# What the local platform needs: stack-up.sh, the seed scripts (mongosh) and the walkthrough (node).
check java      'java -version 2>&1 | head -1'
check docker    'docker --version'
check kubectl   'kubectl version --client 2>/dev/null | head -1'
check kind      'kind --version'
check mongosh   'mongosh --version'
check node      'node --version'
check curl      'curl --version'
check git       'git --version'

# Only the AWS half (terraform, aws) and pull requests (gh) need these. The local platform runs
# without any of them; the archiver waits for AWS and nothing else notices.
for opt in terraform aws gh; do
  if command -v "$opt" >/dev/null 2>&1; then
    ok "$(printf '%-10s %s' "$opt" "$($opt --version 2>&1 | head -1)")"
  else
    warn "$(printf '%-10s missing (optional: terraform and aws for AWS, gh for pull requests)' "$opt")"
  fi
done

echo
if ! docker info >/dev/null 2>&1; then
  die "Docker daemon is not responding. Start Docker Desktop."
fi
ok "Docker daemon responding"

mem=$(docker info --format '{{.MemTotal}}' 2>/dev/null || echo 0)
mem_gb=$(( mem / 1024 / 1024 / 1024 ))
if [ "$mem_gb" -lt 10 ]; then
  warn "Docker has ${mem_gb}GB. Kafka, MongoDB and eight workloads want 10-12GB."
  warn "Raise it in Docker Desktop > Settings > Resources."
else
  ok "Docker memory ${mem_gb}GB"
fi

[ "$fail" -eq 0 ] || die "Some required tools are missing."
echo
ok "Preflight passed."
