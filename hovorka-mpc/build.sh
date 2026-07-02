#!/usr/bin/env bash
# Compile + run the standalone HovorkaMPC in-silico validation harness (no Android/AAPS needed).
# Self-locating: DIR is this script's directory. TOOLS points at a local JDK 21 + kotlinc; override via
# env if your toolchain lives elsewhere:  TOOLS=/path/to/tools ./build.sh
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOLS="${TOOLS:-/home/alex/projects/camaps/tools}"
export JAVA_HOME="$TOOLS/jdk-21.0.5+11"
export PATH=$JAVA_HOME/bin:$PATH
KOTLINC="$TOOLS/kotlinc/bin/kotlinc"
OUT=$DIR/out
rm -rf "$OUT"; mkdir -p "$OUT"

SRC=$(find "$DIR/src/main/kotlin" -name '*.kt')
"$KOTLINC" $SRC -include-runtime -d "$OUT/hovorka-mpc.jar" 2>&1 | grep -vE 'warning: .*restricted method|WARNING: A restricted' || true

echo "=== run ==="
"$JAVA_HOME/bin/java" -cp "$OUT/hovorka-mpc.jar" hovorka.mpc.DemoKt
