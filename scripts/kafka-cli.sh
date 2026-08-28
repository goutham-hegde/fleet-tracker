#!/usr/bin/env bash
# Run one of Kafka's console tools from the Windows host against the cluster.
#
#   ./scripts/kafka-cli.sh kafka-topics.sh --bootstrap-server localhost:19092 --list
#
# The tools are plain shell wrappers around a Java classpath, so they need the
# Kafka distribution on disk. It is downloaded on first use into .tools/, which
# is gitignored -- a 100 MB tarball does not belong in the repository, and it is
# reproducible from this script.
#
# Note: kafka-run-class.sh detects MSYS/Git Bash and converts the classpath to
# Windows form with cygpath, so the .sh launchers work here. The bin/windows/*.bat
# equivalents exist but are not needed.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

KAFKA_VERSION="4.3.1"
SCALA_VERSION="2.13"
DIST="kafka_${SCALA_VERSION}-${KAFKA_VERSION}"
TOOLS_DIR="$REPO_ROOT/.tools"
KAFKA_HOME="$TOOLS_DIR/$DIST"

if [ ! -x "$KAFKA_HOME/bin/kafka-topics.sh" ]; then
  log "Kafka $KAFKA_VERSION CLI not present; downloading into .tools/"
  mkdir -p "$TOOLS_DIR"
  tarball="$TOOLS_DIR/$DIST.tgz"
  if [ ! -s "$tarball" ]; then
    # downloads.apache.org carries only current releases; archive.apache.org
    # keeps every release forever, so it is the fallback once this version ages.
    curl -fsSL -o "$tarball" "https://downloads.apache.org/kafka/$KAFKA_VERSION/$DIST.tgz" \
      || curl -fsSL -o "$tarball" "https://archive.apache.org/dist/kafka/$KAFKA_VERSION/$DIST.tgz" \
      || die "Could not download Kafka $KAFKA_VERSION."
  fi
  tar -xzf "$tarball" -C "$TOOLS_DIR"
  ok "Kafka CLI at .tools/$DIST"
fi

[ $# -ge 1 ] || die "Usage: $0 <kafka-tool.sh> [args...]   e.g. kafka-topics.sh --bootstrap-server localhost:19092 --list"

tool="$1"; shift
[ -x "$KAFKA_HOME/bin/$tool" ] || die "No such Kafka tool: $tool"

# The launcher points log4j at $base_dir/config/tools-log4j2.yaml. Under Git
# Bash that resolves to a Windows path, and log4j reads a bare path containing
# a colon as a URI -- so "G:/..." becomes "unknown protocol: g" and a stack
# trace on every single command. Handing it a proper file: URI avoids that.
if [ -f "$KAFKA_HOME/config/tools-log4j2.yaml" ] && command -v cygpath >/dev/null 2>&1; then
  export KAFKA_LOG4J_OPTS="-Dlog4j2.configurationFile=file:///$(cygpath -m "$KAFKA_HOME/config/tools-log4j2.yaml")"
fi

exec "$KAFKA_HOME/bin/$tool" "$@"
