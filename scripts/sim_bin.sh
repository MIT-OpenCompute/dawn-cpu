cd generated
verilator --cc --exe --build -j 0 \
--x-assign unique --x-initial unique \
-CFLAGS "-g -O3" \
../simulation/simulate_program.cpp -f filelist.f --top Main
./obj_dir/VMain $1