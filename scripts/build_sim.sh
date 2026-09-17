#!/usr/bin/env bash
# Builds the Verilator simulation binary.
#   ./scripts/build_sim.sh                 -> generated/obj_dir/VMain
#   DAWN_PGO=1 ./scripts/build_sim.sh      -> generated/obj_dir_pgo/VMain  (~1.3x faster)
#
# Called by simulate.sh and sim_bin.sh; safe to run on its own.
set -e

cd "$(dirname "$0")/.."
ROOT="$PWD"
SIM_CPP="$ROOT/simulation/simulate_program.cpp"   # absolute: the PGO pass rebuilds from generated/

# nix's cc wrapper strips -march=native by default to keep builds reproducible.
# We want the native build here.
export NIX_ENFORCE_NO_NATIVE=0

# Override if you need a portable binary, e.g. DAWN_ARCH=x86-64-v3
DAWN_ARCH="${DAWN_ARCH:-native}"

# -DDAWN_SLOW_CLOCK falls back to the portable two-eval-per-cycle clocking if
# a Verilator upgrade ever renames the internal edge-detect variable.
DAWN_CXXFLAGS="${DAWN_CXXFLAGS:--O3 -march=$DAWN_ARCH -fno-stack-protector -fomit-frame-pointer}"

# --expand-limit: the ReorderBuffer's 192-entry VecInit becomes a 6144-bit
#   concat chain. Above --expand-limit words (default 64) Verilator emits it as
#   chained VL_CONCAT_WWI calls, each of which zeroes its whole output then
#   copies the accumulated prefix back in -- O(n^2) memset+memmove that profiled
#   at ~76% of run time. Past 192 words it emits plain word assignments that gcc
#   folds away. Same code size, same build time, ~8x faster sim.
# --output-split-cfuncs: without it the NBA region is one ~12000-instruction
#   function and gcc's schedulers and register allocator give up on it. 700 was
#   the measured optimum (50/100/200/300/500/700/1000/2000 all tried); it is
#   also what makes PGO pay off -- with one huge function PGO is a net loss.
VERILATOR_ARGS=(
  --cc --exe --build -j 0
  -O3
  --expand-limit 1024
  --output-split-cfuncs 700
  --x-assign unique --x-initial unique
  "$SIM_CPP" -f filelist.f --top Main
)

cd "$ROOT/generated"

if [ -z "${DAWN_PGO:-}" ]; then
  exec verilator "${VERILATOR_ARGS[@]}" --Mdir obj_dir -o VMain -CFLAGS "$DAWN_CXXFLAGS"
fi

# ---- profile-guided build -------------------------------------------------
# Worth ~1.3x: it does not remove work, it roughly lifts IPC from 2.2 to 3.0 by
# laying out the enormous NBA switch/branch trees the way the workload actually
# runs them. The profile is cached in generated/pgo, so only the first build
# pays for instrumentation and a training run. Delete that directory (or set
# DAWN_PGO=regen) after RTL changes. -Wno-coverage-mismatch is load-bearing:
# without it gcc makes a profile that no longer matches the source a hard
# build error. With it, changed functions just fall back to static heuristics,
# so a stale profile costs some speed but never correctness or a broken build.
PROF_DIR="$ROOT/generated/pgo"
[ "$DAWN_PGO" = regen ] && rm -rf "$PROF_DIR"

if [ ! -d "$PROF_DIR" ] || [ -z "$(ls -A "$PROF_DIR" 2>/dev/null)" ]; then
  TRAIN="${DAWN_PGO_TRAIN:-}"
  if [ -z "$TRAIN" ] || [ ! -f "$TRAIN" ]; then
    echo "build_sim.sh: DAWN_PGO needs a training binary." >&2
    echo "  set DAWN_PGO_TRAIN=/path/to/program.bin, or just run sim_bin.sh/simulate.sh with DAWN_PGO=1" >&2
    exit 1
  fi
  TRAIN="$(realpath "$TRAIN")"
  rm -rf obj_dir_pgo; mkdir -p "$PROF_DIR"

  echo "[pgo 1/3] instrumented build"
  verilator "${VERILATOR_ARGS[@]}" --Mdir obj_dir_pgo -o VMain \
    -CFLAGS "$DAWN_CXXFLAGS -fprofile-generate=$PROF_DIR -fprofile-update=single" \
    -LDFLAGS "-fprofile-generate=$PROF_DIR"

  echo "[pgo 2/3] training on $(basename "$TRAIN") for ${DAWN_PGO_CYCLES:-1500000} cycles"
  ./obj_dir_pgo/VMain "$TRAIN" "${DAWN_PGO_CYCLES:-1500000}" > /dev/null

  echo "[pgo 3/3] optimized build"
fi

exec verilator "${VERILATOR_ARGS[@]}" --Mdir obj_dir_pgo -o VMain \
  -CFLAGS "$DAWN_CXXFLAGS -fprofile-use=$PROF_DIR -fprofile-correction -Wno-missing-profile -Wno-coverage-mismatch"
