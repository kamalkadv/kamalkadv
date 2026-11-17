#!/usr/bin/env python3
"""Buat R.java sederhana dari folder res/ (hanya untuk pengecekan compile cepat, bukan build).

Pemakaian: gen_r.py <folder res> <file R.java keluaran> [package]
"""
import os, re, sys, xml.etree.ElementTree as ET
res, out = sys.argv[1], sys.argv[2]
pkg = sys.argv[3] if len(sys.argv) > 3 else 'id.kioskos.lock'
types = {}
def add(t, n): types.setdefault(t, set()).add(n.replace('.', '_'))
for d in os.listdir(res):
    p = os.path.join(res, d)
    base = d.split('-')[0]
    for f in os.listdir(p):
        fp = os.path.join(p, f)
        if base == 'values':
            for e in ET.parse(fp).getroot():
                if 'name' in e.attrib: add(e.tag, e.attrib['name'])
        else:
            add(base, os.path.splitext(f)[0])
            for m in re.findall(r'@\+id/([A-Za-z0-9_]+)', open(fp).read()): add('id', m)
i = 0x7f000000
lines = [f'package {pkg};', 'public final class R {']
for t, names in sorted(types.items()):
    lines.append(f'  public static final class {t} {{')
    for n in sorted(names):
        i += 1; lines.append(f'    public static final int {n} = {i};')
    lines.append('  }')
lines.append('}')
os.makedirs(os.path.dirname(out), exist_ok=True); open(out, 'w').write('\n'.join(lines))
