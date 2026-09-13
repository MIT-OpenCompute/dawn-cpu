./xpack-riscv-none-elf-gcc-15.2.0-1/bin/riscv-none-elf-gcc -c -O3 -march=rv32i -mabi=ilp32 $1 -o ./generated/program.o
./xpack-riscv-none-elf-gcc-15.2.0-1/bin/riscv-none-elf-gcc -march=rv32i -mabi=ilp32 -nostdlib "-Wl,--section-start=.text=0x0,--entry=_start" -o ./generated/program.elf ./generated/program.o -lgcc
./xpack-riscv-none-elf-gcc-15.2.0-1/bin/riscv-none-elf-objcopy -O binary ./generated/program.elf ./generated/program.bin
