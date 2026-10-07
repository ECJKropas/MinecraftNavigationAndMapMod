#!/usr/bin/env bash
#
# Wayfarer headless checker - runs without Minecraft, Fabric or gradle.
#
#   ./run.sh [repo-root]
#
# repo-root defaults to the checkout this directory lives in. The script compiles the real navigation core
# (client/road/{model,nav,spatial}) against the headless stand-ins in src/stub and runs every suite in src/checks.
# Exit code: 0 all checks passed, 1 at least one check failed, 2 the repository was not found.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="${1:-$(cd "$HERE/../.." && pwd)}"
REAL="$REPO/src/main/java/com/ecjkim/wayfarer/client/road"
OUT="$HERE/out"

if [ ! -d "$REAL/nav" ]; then
    echo "real sources not found under $REAL (usage: $0 [repo-root])" >&2
    exit 2
fi

mkdir -p "$OUT"

# Only model/nav/spatial come from the repository: the stand-ins in src/stub replace the real client/road/data
# database and the malilib-backed WayfarerConfig so the core stays loadable off-game.
echo "compiling: repo client/road/{model,nav,spatial} + headless stubs"
# Tool-generated backup copies (`Name_YYYYMMDD_HHMMSS_mmm.java`) are skipped: they hold a stale duplicate of a class
# that is already in the tree and would otherwise break the compile for everyone.
if ! find "$HERE/src" "$REAL/model" "$REAL/nav" "$REAL/spatial" -name '*.java' \
    -not -name '*_[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]_[0-9][0-9][0-9][0-9][0-9][0-9]_[0-9][0-9][0-9].java' \
    -print0 | xargs -0 javac -d "$OUT" 2>"$OUT/javac.log"; then
    echo "compile failed: the working tree is not in a buildable state (full log: $OUT/javac.log)" >&2
    tail -n 20 "$OUT/javac.log" >&2
    exit 3
fi

java -cp "$OUT" checks.Master "$REPO"
