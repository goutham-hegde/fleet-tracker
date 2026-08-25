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

check java      'java -version 2>&1 | head -1'
check docker    'docker --version'
check kubectl   'kubectl version --client 2>/dev/null | head -1'
check kind      'kind --version'
check helm      'helm version --short'
check terraform 'terraform version | head -1'
check node      'node --version'
check git       'git --version'

# aws and gh are only needed from M8 / for pushing, so they are advisory.
for opt in aws gh; do
  if command -v "$opt" >/dev/null 2>&1; then
    ok "$(printf '%-10s %s' "$opt" "$($opt --version 2>&1 | head -1)")"
  else
    warn "$(printf '%-10s missing (needed later: aws=M8, gh=push)' "$opt")"
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
  warn "Docker has ${mem_gb}GB. Kafka + Mongo + 5 JVMs + ArgoCD wants 10-12GB."
  warn "Raise it in Docker Desktop > Settings > Resources."
else
  ok "Docker memory ${mem_gb}GB"
fi

[ "$fail" -eq 0 ] || die "Some required tools are missing."
echo
ok "Preflight passed."
