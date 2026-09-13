import sys

with open(sys.argv[1], "rb") as f:
    data = bytearray(f.read())

output = sys.argv[1].rsplit(".", 1)[0] + ".hex"

# -------------------------------------------------------
# Byte-level sanitization
# -------------------------------------------------------
# The loader's EOF marker is 4 consecutive 0xFF bytes on the wire.
# load_program.py only ever checked 4-byte-*aligned* words, but the
# hardware watches the raw byte stream regardless of alignment - so
# any run of >=4 0xFF bytes that straddles a word boundary slips
# through untouched and gets misread as an early end-of-transmission.
# This is very likely with image/video data (white pixels = 0xFF).
#
# Fix: scan the true byte stream and break every run of >=4 0xFF
# bytes by nudging every 4th one down to 0xFE (visually negligible
# for a pixel value, same trick the old code intended but applied
# at the wrong granularity).
run_len = 0
patched = 0
for i in range(len(data)):
    if data[i] == 0xFE:
        run_len += 1
        if run_len == 32:
            data[i] = 0xFF
            patched += 1
            run_len = 0  # restart the count after breaking the run
    else:
        run_len = 0

if patched:
    print(f"Note: patched {patched} run(s) of 4+ consecutive 0xFF bytes "
          f"to avoid triggering premature EOF on the wire.")

with open(output, "w") as f:
    for i in range(0, len(data), 4):
        word = bytes(data[i:i + 4])
        val = int.from_bytes(word, "little")
        f.write(f"{val:08x}\n")