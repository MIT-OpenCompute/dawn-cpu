#!/bin/bash
if [ -z "$1" ]; then
echo "Usage: $0 <.bin>"
exit 1
fi

BIN=$1
HEX="/tmp/build_out.hex"
echo "Converting..."
python ./scripts/convert.py "$BIN" "$HEX" || exit 1
echo "Loading program..."
python ./scripts/load_program.py "$HEX" --port /dev/ttyUSB1
echo "Cleaning up..."
rm -f "$HEX"
echo "Done."