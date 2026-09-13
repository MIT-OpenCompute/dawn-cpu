#!/usr/bin/env python3
"""
RISC-V program loader for Basys3 / Urbana
Sends a .hex file over UART to the hardware program loader.
Automatically pads data to 128-bit (16-byte) cache line boundaries.

Usage:
    python load_program.py program.hex
    python load_program.py program.hex --port COM3
    python load_program.py program.hex --port COM3 --baud 115200
"""

import serial
import time
import sys
import argparse

# -------------------------------------------------------
# Argument parsing
# -------------------------------------------------------
parser = argparse.ArgumentParser(description="Load a RIsSC-V hex program onto FPGA over UART")
parser.add_argument("hex_file",              help="Path to the .hex program file")
parser.add_argument("--port",  default="COM3", help="Serial port (default: COM3)")
parser.add_argument("--baud",  default=6000000, type=int, help="Baud rate (default: 115200)")
parser.add_argument("--delay", default=0.00,  type=float, help="Delay between words in seconds (default: 0.01)")
args = parser.parse_args()

# -------------------------------------------------------
# Read hex file
# -------------------------------------------------------
words = []
try:
    with open(args.hex_file, 'r') as f:
        for lineno, line in enumerate(f, 1):
            line = line.strip()
            if not line or line.startswith("//") or line.startswith("#"):
                continue
            try:
                word = int(line, 16)
   
                    
                words.append(word)
            except ValueError:
                print(f"Warning: skipping invalid line {lineno}: '{line}'")
except FileNotFoundError:
    print(f"Error: file '{args.hex_file}' not found")
    sys.exit(1)

if not words:
    print("Error: no valid words found in hex file")
    sys.exit(1)


remainder = len(words) % 16
if remainder != 0:
    padding_needed = 16 - remainder
    words.extend([0x00000000] * padding_needed)
    print(f"Note: Padded program with {padding_needed} empty word(s) to align to 16-byte Cache Line boundaries.")

print(f"Loaded {len(words)} words ({len(words) * 4} bytes) from '{args.hex_file}'")


try:
    with serial.Serial(args.port, args.baud, timeout=2) as ser:
        print(f"Opened {args.port} at {args.baud} baud")
        print("Sending program...")

        for i, word in enumerate(words):
            data = word.to_bytes(4, byteorder='little')
            ser.write(data)
            time.sleep(args.delay)

            # Progress bar
            pct = (i + 1) / len(words) * 100
            bar = '#' * int(pct / 2) + '-' * (50 - int(pct / 2))
            print(f"\r[{bar}] {pct:.1f}% ({i+1}/{len(words)} words)", end='', flush=True)

        print()  # newline after progress bar

        print("Sending end sequence (0xFF 0xFF 0xFF 0xFF) to boot CPU...")
        ser.write(bytes([0xFE, 0xFE, 0xFE, 0xFE, 0xFE, 0xFE]*8))
        time.sleep(0.1)

        print("Done! CPU should now be running your program.")

except serial.SerialException as e:
    print(f"Serial error: {e}")
    print(f"Make sure the board is connected and '{args.port}' is correct")
    sys.exit(1)