#!/usr/bin/env bash
# Shared helpers. Source this, don't execute it.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLUSTER_NAME="fleet-tracking"
KIND_CONFIG="$REPO_ROOT/deploy/kind-cluster.yaml"

# winget installs tools into per-package directories and updates the *user* PATH,
# which existing shells do not see until they restart. Add them here so scripts
# work in the same shell session an install happened in.
#
# $LOCALAPPDATA is a Windows path with backslashes, which bash cannot use as a
# directory, so convert it with cygpath and fall back to $HOME otherwise.
if [ -n "${LOCALAPPDATA:-}" ] && command -v cygpath >/dev/null 2>&1; then
  _localappdata="$(cygpath -u "$LOCALAPPDATA")"
else
  _localappdata="$HOME/AppData/Local"
fi
_winget_pkgs="$_localappdata/Microsoft/WinGet/Packages"

for _d in \
  "$_winget_pkgs/Kubernetes.kind_Microsoft.Winget.Source_8wekyb3d8bbwe" \
  "$_winget_pkgs/Helm.Helm_Microsoft.Winget.Source_8wekyb3d8bbwe/windows-amd64" \
  "$_winget_pkgs/Hashicorp.Terraform_Microsoft.Winget.Source_8wekyb3d8bbwe" \
  "$_localappdata/Programs/mongosh" \
  "/c/Program Files/GitHub CLI" \
  "/c/Program Files/Amazon/AWSCLIV2" ; do
  if [ -d "$_d" ]; then
    case ":$PATH:" in *":$_d:"*) ;; *) PATH="$PATH:$_d" ;; esac
  fi
done
export PATH

log()  { printf '\033[0;36m==>\033[0m %s\n' "$*"; }
ok()   { printf '\033[0;32m  ok\033[0m %s\n' "$*"; }
warn() { printf '\033[0;33m  !!\033[0m %s\n' "$*"; }
die()  { printf '\033[0;31m ERR\033[0m %s\n' "$*" >&2; exit 1; }

require() {
  command -v "$1" >/dev/null 2>&1 || die "'$1' not found on PATH. $2"
}
