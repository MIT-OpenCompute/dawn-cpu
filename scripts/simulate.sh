#!/usr/bin/env bash
# Compile a C program for the core, build the simulator, and run it.
#   ./scripts/simulate.sh ./programs/pong.c [cycle-limit]
#   DAWN_PGO=1 ./scripts/simulate.sh ./programs/pong.c      (~1.3x faster sim)
set -e

cd "$(dirname "$0")/.."

./xpack-riscv-none-elf-gcc-15.2.0-1/bin/riscv-none-elf-gcc -c -O3 -march=rv32i -mabi=ilp32 "$1" -o ./generated/program.o
./xpack-riscv-none-elf-gcc-15.2.0-1/bin/riscv-none-elf-gcc -march=rv32i -mabi=ilp32 -nostdlib "-Wl,--section-start=.text=0x0,--entry=_start" -o ./generated/program.elf ./generated/program.o -lgcc
./xpack-riscv-none-elf-gcc-15.2.0-1/bin/riscv-none-elf-objcopy -O binary ./generated/program.elf ./generated/program.bin

shift

DAWN_PGO_TRAIN="${DAWN_PGO_TRAIN:-$PWD/generated/program.bin}" ./scripts/build_sim.sh

cd generated
OBJ=obj_dir; [ -n "${DAWN_PGO:-}" ] && OBJ=obj_dir_pgo
exec "./$OBJ/VMain" ./program.bin "$@"
