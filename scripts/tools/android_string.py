#!/usr/bin/env python3
"""Escape teks agar aman sebagai isi <string> resource Android (aapt2).

Aturan aapt: backslash, petik dua, dan petik satu harus di-escape dengan backslash; '@' atau '?'
di awal dianggap referensi resource; lalu &, <, > di-escape untuk XML.
"""
import html
import sys


def escape(text: str) -> str:
    out = text.replace("\\", "\\\\").replace('"', '\\"').replace("'", "\\'").replace("\n", "\\n")
    if out[:1] in ("@", "?"):
        out = "\\" + out
    return html.escape(out, quote=False)


if __name__ == "__main__":
    print(escape(sys.argv[1]))
