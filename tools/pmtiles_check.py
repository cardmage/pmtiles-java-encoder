#!/usr/bin/env python3
"""Dependency-free PMTiles v3 structure checker, written from the spec text
(independent of the Java writer). Streams leaf by leaf, so it also works on
archives with tens of millions of tiles.

  python3 tools/pmtiles_check.py out.pmtiles            # structure only
  python3 tools/pmtiles_check.py out.pmtiles --zxy 97   # also check every 97th
                                                        # payload == "z/x/y"
                                                        # (BoundedMemoryDemo output)
"""
import argparse
import gzip
import json
import struct


def hilbert_d2xy(n, d):
    x = y = 0
    s, t = 1, d
    while s < n:
        rx = 1 & (t // 2)
        ry = 1 & (t ^ rx)
        if ry == 0:
            if rx == 1:
                x, y = s - 1 - x, s - 1 - y
            x, y = y, x
        x += s * rx
        y += s * ry
        t //= 4
        s *= 2
    return x, y


def id_to_zxy(tid):
    acc = 0
    for z in range(32):
        num = 1 << (2 * z)
        if tid < acc + num:
            return (z, *hilbert_d2xy(1 << z, tid - acc))
        acc += num
    raise ValueError(tid)


def uvarint(buf, pos):
    shift = res = 0
    while True:
        b = buf[pos]
        pos += 1
        res |= (b & 0x7F) << shift
        if b < 0x80:
            return res, pos
        shift += 7


def check(path, zxy_every):
    data = open(path, 'rb').read()
    assert data[:7] == b'PMTiles' and data[7] == 3, 'not PMTiles v3'
    (root_off, root_len, meta_off, meta_len, leaf_off, leaf_len, tile_off, tile_len,
     addressed, n_entries, n_contents) = struct.unpack_from('<11Q', data, 8)
    clustered, ic, _tc, _tt, minz, maxz = struct.unpack_from('<6B', data, 96)
    assert root_off + root_len <= 16384, 'root directory not within first 16 KiB'
    for off, ln, name in [(root_off, root_len, 'root'), (meta_off, meta_len, 'metadata'),
                          (leaf_off, leaf_len, 'leaves'), (tile_off, tile_len, 'tile data')]:
        assert off + ln <= len(data), name + ' out of bounds'
    assert ic in (1, 2), 'checker supports internal compression none/gzip only'
    dec = gzip.decompress if ic == 2 else (lambda b: b)

    def read_dir(off, ln):
        raw = dec(data[off:off + ln])
        n, p = uvarint(raw, 0)
        assert n > 0, 'empty directory'
        ids, offs, lens, runs = [0] * n, [0] * n, [0] * n, [0] * n
        last = 0
        for i in range(n):
            v, p = uvarint(raw, p)
            last += v
            ids[i] = last
        for col in (runs, lens):
            for i in range(n):
                col[i], p = uvarint(raw, p)
        for i in range(n):
            v, p = uvarint(raw, p)
            offs[i] = offs[i - 1] + lens[i - 1] if (v == 0 and i > 0) else v - 1
        assert p == len(raw), 'trailing bytes in directory'
        return zip(ids, offs, lens, runs)

    seen = bytearray(tile_len // 8 + 1)
    entries = run_total = contents = checked = k = 0
    prev_end, end, first, zmin, zmax = -1, 0, True, None, None
    for tid, off, ln, run in read_dir(root_off, root_len):
        group = read_dir(leaf_off + off, ln) if run == 0 else [(tid, off, ln, run)]
        for t, o, l, r in group:
            assert r > 0, 'leaf directory nested deeper than one level'
            assert l > 0 and o + l <= tile_len, 'bad tile offset/length'
            assert t >= prev_end, 'entries unsorted or overlapping at %d' % t
            prev_end = t + r
            entries += 1
            run_total += r
            if not (seen[o >> 3] >> (o & 7)) & 1:
                seen[o >> 3] |= 1 << (o & 7)
                contents += 1
            if clustered:
                if first:
                    assert o == 0, 'clustered archive must start at offset 0'
                if o == end:
                    end += l
                else:
                    assert o + l <= end, 'clustered flag set but data not in tile ID order'
            if first:
                zmin = id_to_zxy(t)[0]
            first = False
            if zxy_every and k % zxy_every == 0:
                z, x, y = id_to_zxy(t)
                assert data[tile_off + o:tile_off + o + l] == f'{z}/{x}/{y}'.encode(), (z, x, y)
                checked += 1
            k += 1
    zmax = id_to_zxy(prev_end - 1)[0]
    assert entries == n_entries, 'header tile entries %d != %d' % (n_entries, entries)
    assert run_total == addressed, 'header addressed tiles mismatch'
    assert contents == n_contents, 'header tile contents %d != %d' % (n_contents, contents)
    if clustered:
        assert end == tile_len, 'tile data not fully referenced'
    assert (zmin, zmax) == (minz, maxz), 'header zoom range mismatch'
    json.loads(dec(data[meta_off:meta_off + meta_len]))
    print(f'OK {path}: tiles={addressed} entries={entries} contents={contents} '
          f'clustered={clustered} z{minz}-{maxz} payloads-checked={checked}')


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('path')
    ap.add_argument('--zxy', type=int, default=0, metavar='N',
                    help='check every Nth payload equals "z/x/y"')
    a = ap.parse_args()
    check(a.path, a.zxy)
