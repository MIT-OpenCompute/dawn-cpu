#!/usr/bin/env bash
# Build the simulator and run a prebuilt raw binary on it.
#   ./scripts/sim_bin.sh /your/bin/here.bin [cycle-limit]
#   DAWN_PGO=1 ./scripts/sim_bin.sh /your/bin/here.bin      (~1.3x faster sim)
set -e

cd "$(dirname "$0")/.."

BIN="$(realpath "$1")"
shift
DAWN_PGO_TRAIN="${DAWN_PGO_TRAIN:-$BIN}" ./scripts/build_sim.sh

cd generated
OBJ=obj_dir; [ -n "${DAWN_PGO:-}" ] && OBJ=obj_dir_pgo
exec "./$OBJ/VMain" "$BIN" "$@"
