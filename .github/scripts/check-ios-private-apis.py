#!/usr/bin/env python3
"""Reject the removed JSC SPI in sources or in the linked app's symbols."""
import pathlib, re, subprocess, sys
BANNED = re.compile(r'JSContextGroup(?:Set|Clear)ExecutionTimeLimit')
root = pathlib.Path(__file__).resolve().parents[2]
files = list((root / 'ios/NativeCodex').rglob('*.rs')) + list((root / 'ios/NativeCodex').rglob('*.c')) + list((root / 'ios/NativeCodex').rglob('*.swift'))
for path in files:
    if 'Generated' in path.parts: continue
    if BANNED.search(path.read_text()): raise SystemExit(f'Removed private API remains in {path}')
if len(sys.argv) > 1:
    binary = sys.argv[1]
    symbols = subprocess.check_output(['xcrun', 'nm', '-u', binary], text=True)
    if BANNED.search(symbols): raise SystemExit('The binary imports a removed JavaScriptCore private API')
    print('PASS: removed JavaScriptCore SPI absent from linked app imports')
print('PASS: removed JavaScriptCore SPI absent from runtime sources')
