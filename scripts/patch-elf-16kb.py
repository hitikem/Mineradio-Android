#!/usr/bin/env python3
"""
把共享库的 LOAD 段对齐到 16KB，使其能在 16KB 内存页的安卓设备上加载。

背景
----
Android 15 起，部分新机型（Pixel 8/9 系列、部分新旗舰）内核使用 16KB 内存页。
动态链接器映射 ELF 时要求每个 PT_LOAD 满足：

    p_offset ≡ p_vaddr (mod 页大小)

不满足就会 dlopen 失败，表现为「能装上，但点开闪退/黑屏」：
    dlopen failed: ".../libnode.so" has a LOAD segment with alignment 4096,
    which is not a multiple of the page size 16384

nodejs-mobile 的 libnode.so 是 2024 年用旧 NDK 构建的，p_align 全是 4096，
在 16KB 页机型上必然加载失败。

原理
----
链接器的映射方式是：

    mmap(base + (p_vaddr & ~(P-1)), (p_vaddr & (P-1)) + p_filesz,
         prot, MAP_FIXED | MAP_PRIVATE, fd, p_offset & ~(P-1))

数据要落到正确位置，只需 `p_offset ≡ p_vaddr (mod P)`。
这是**同余**关系而非绝对对齐，所以不需要重新链接：
在每个 LOAD 段数据前插入若干填充字节，把它的文件偏移挪到与 vaddr 同余即可。
vaddr、重定位表、动态符号全部不动，各段有效数据逐字节不变（脚本会验证）。

同时更新节头表（sh_offset / e_shoff），否则 llvm-strip 之类工具会读到错位数据。

用法：
    python scripts/patch-elf-16kb.py <lib.so> [--out <out.so>] [--in-place]
"""

import argparse
import os
import shutil
import struct
import sys

PT_LOAD = 1


# ===========================================================================
# ELF 解析
# ===========================================================================
class Elf:
    def __init__(self, data):
        if data[:4] != b'\x7fELF':
            raise ValueError('不是 ELF 文件')
        self.data = data
        self.is64 = data[4] == 2
        self.e = '<' if data[5] == 1 else '>'
        self._header()
        self._phdrs()
        self._shdrs()

    def _header(self):
        d, e = self.data, self.e
        if self.is64:
            f = struct.unpack_from(e + 'HHIQQQIHHHHHH', d, 0x10)
        else:
            f = struct.unpack_from(e + 'HHIIIIIHHHHHH', d, 0x10)
        (self.e_type, self.e_machine, self.e_version, self.e_entry,
         self.e_phoff, self.e_shoff, self.e_flags, self.e_ehsize,
         self.e_phentsize, self.e_phnum, self.e_shentsize,
         self.e_shnum, self.e_shstrndx) = f

    def _phdrs(self):
        self.phdrs = []
        for i in range(self.e_phnum):
            off = self.e_phoff + i * self.e_phentsize
            if self.is64:
                v = struct.unpack_from(self.e + 'IIQQQQQQ', self.data, off)
                k = ('type', 'flags', 'offset', 'vaddr', 'paddr', 'filesz', 'memsz', 'align')
            else:
                v = struct.unpack_from(self.e + 'IIIIIIII', self.data, off)
                k = ('type', 'offset', 'vaddr', 'paddr', 'filesz', 'memsz', 'flags', 'align')
            self.phdrs.append(dict(zip(k, v)))

    def _shdrs(self):
        self.shdrs = []
        for i in range(self.e_shnum):
            off = self.e_shoff + i * self.e_shentsize
            if self.is64:
                v = struct.unpack_from(self.e + 'IIQQQQIIQQ', self.data, off)
                k = ('name', 'type', 'flags', 'addr', 'offset', 'size',
                     'link', 'info', 'addralign', 'entsize')
            else:
                v = struct.unpack_from(self.e + 'IIIIIIIIII', self.data, off)
                k = ('name', 'type', 'flags', 'addr', 'offset', 'size',
                     'link', 'info', 'addralign', 'entsize')
            self.shdrs.append(dict(zip(k, v)))

    def section_names(self):
        if not (0 < self.e_shstrndx < len(self.shdrs)):
            return [''] * len(self.shdrs)
        st = self.shdrs[self.e_shstrndx]
        out = []
        for s in self.shdrs:
            p = st['offset'] + s['name']
            out.append(self.data[p:self.data.index(b'\0', p)].decode('utf-8', 'replace'))
        return out

    # --- 写回 ---------------------------------------------------------------
    def set_phdr_offset(self, idx, new_offset, new_align):
        off = self.e_phoff + idx * self.e_phentsize
        if self.is64:
            struct.pack_into(self.e + 'Q', self.data, off + 8, new_offset)
            struct.pack_into(self.e + 'Q', self.data, off + 48, new_align)
        else:
            struct.pack_into(self.e + 'I', self.data, off + 4, new_offset)
            struct.pack_into(self.e + 'I', self.data, off + 28, new_align)

    def set_shdr_offset(self, idx, new_offset):
        off = self.e_shoff + idx * self.e_shentsize
        if self.is64:
            struct.pack_into(self.e + 'Q', self.data, off + 24, new_offset)
        else:
            struct.pack_into(self.e + 'I', self.data, off + 16, new_offset)

    def set_e_shoff(self, v):
        struct.pack_into(self.e + ('Q' if self.is64 else 'I'),
                         self.data, 0x28 if self.is64 else 0x20, v)

    def set_e_phoff(self, v):
        struct.pack_into(self.e + ('Q' if self.is64 else 'I'),
                         self.data, 0x20 if self.is64 else 0x1C, v)


# ===========================================================================
# 校验
# ===========================================================================
def windows(elf, page_size):
    """每个 PT_LOAD 的映射窗口 (win_v, win_f, length)"""
    out = []
    for p in elf.phdrs:
        if p['type'] != PT_LOAD:
            continue
        wv = p['vaddr'] & ~(page_size - 1)
        wf = p['offset'] & ~(page_size - 1)
        out.append((wv, wf, (p['vaddr'] - wv) + p['filesz']))
    return out


def loads_of(elf):
    return [p for p in elf.phdrs if p['type'] == PT_LOAD]


def check_structure(data, page_size, label):
    """结构合法性：对齐、同余、不越界、不重叠"""
    elf = Elf(data)
    ls = loads_of(elf)
    bad = []
    for i, p in enumerate(ls):
        if p['align'] < page_size:
            bad.append(f'LOAD{i} p_align={p["align"]} < {page_size}')
        if (p['offset'] % page_size) != (p['vaddr'] % page_size):
            bad.append(f'LOAD{i} 不同余: off%P={p["offset"] % page_size} '
                       f'!= vaddr%P={p["vaddr"] % page_size}')
        if p['offset'] + p['filesz'] > len(data):
            bad.append(f'LOAD{i} 超出文件末尾')
    ordered = sorted(ls, key=lambda p: p['offset'])
    for a, b in zip(ordered, ordered[1:]):
        if a['offset'] + a['filesz'] > b['offset']:
            bad.append(f'LOAD 文件区间重叠 {a["offset"]}+{a["filesz"]} > {b["offset"]}')
    ordered = sorted(ls, key=lambda p: p['vaddr'])
    for a, b in zip(ordered, ordered[1:]):
        if a['vaddr'] + a['memsz'] > b['vaddr']:
            bad.append(f'LOAD 内存区间重叠 {a["vaddr"]}+{a["memsz"]} > {b["vaddr"]}')
    ok = not bad
    print(f'  [{label}] 结构: {"通过" if ok else "失败"}  '
          f'({len(ls)} 段, p_align={sorted({p["align"] for p in ls})})')
    for x in bad[:6]:
        print('     x', x)
    return ok


def check_readable(data, page_size, label):
    """
    可读性（真正的判据）：每段的有效数据必须能被正确读到。

    段 i 的数据要正确，需要它自己的窗口满足
        win_f(i) - win_v(i) == offset(i) - vaddr(i)
    并且任何**后于 i 且与之重叠**的映射 j 也必须满足同样的 delta，
    否则 j 会把 i 的数据覆盖成错位的字节。
    """
    elf = Elf(data)
    ls = loads_of(elf)
    ws = windows(elf, page_size)
    bad = []

    for i, p in enumerate(ls):
        delta_i = p['offset'] - p['vaddr']
        wv, wf, ln = ws[i]
        if wf - wv != delta_i:
            bad.append(f'LOAD{i} 自身窗口错位: delta_win={wf - wv} != {delta_i}')
        ms, me = p['vaddr'], p['vaddr'] + p['filesz']
        for j in range(i + 1, len(ls)):
            jv, jf, jl = ws[j]
            if min(me, jv + jl) <= max(ms, jv):
                continue
            if jf - jv != delta_i:
                bad.append(f'LOAD{j} 的映射会覆盖 LOAD{i}（delta {jf - jv} != {delta_i}）')

    ok = not bad
    print(f'  [{label}] 段数据可读性: {"通过" if ok else "失败"}')
    for x in bad[:6]:
        print('     x', x)
    return ok


def check_equivalence(orig, patched):
    """
    补丁前后各段有效数据必须逐字节一致。

    例外：ELF 头与程序头表这两块**本来就该变**（我们改了 e_shoff 和
    各段的 p_offset / p_align），且它们只在加载时被链接器从文件读取，
    不会被当成数据使用，所以排除在比对之外。
    """
    eo = Elf(orig)
    allowed = [
        (0, eo.e_ehsize),                                            # ELF 头
        (eo.e_phoff, eo.e_phoff + eo.e_phnum * eo.e_phentsize),      # 程序头表
    ]

    def in_allowed(off):
        return any(a <= off < b for a, b in allowed)

    a, b = loads_of(Elf(orig)), loads_of(Elf(patched))
    if len(a) != len(b):
        print('  [等价性] 失败：段数量不一致')
        return False

    total = 0
    ignored = 0
    for i, (x, y) in enumerate(zip(a, b)):
        if x['vaddr'] != y['vaddr'] or x['filesz'] != y['filesz']:
            print(f'  [等价性] 失败：LOAD{i} 的 vaddr/filesz 变了')
            return False
        da = orig[x['offset']:x['offset'] + x['filesz']]
        db = patched[y['offset']:y['offset'] + y['filesz']]
        if da != db:
            diffs = [t for t in range(len(da)) if da[t] != db[t]]
            bad = [t for t in diffs if not in_allowed(x['offset'] + t)]
            if bad:
                print(f'  [等价性] 失败：LOAD{i} 在文件偏移 '
                      f'{x["offset"] + bad[0]} 处不一致（共 {len(bad)} 处）')
                return False
            ignored += len(diffs)
        total += len(da)

    print(f'  [等价性] 有效数据一致（{total:,} 字节 / {len(a)} 段；'
          f'其中 {ignored} 字节属于 ELF 头/程序头表，本就应变化）')
    return True


# ===========================================================================
# 打补丁
# ===========================================================================
def patch(data, page_size):
    elf = Elf(data)
    load_idx = sorted([i for i, p in enumerate(elf.phdrs) if p['type'] == PT_LOAD],
                      key=lambda i: elf.phdrs[i]['offset'])
    if not load_idx:
        raise ValueError('没有 PT_LOAD 段')

    cuts = {0, len(data)}
    for i in load_idx:
        p = elf.phdrs[i]
        cuts.add(p['offset'])
        cuts.add(min(p['offset'] + p['filesz'], len(data)))
    cuts = sorted(c for c in cuts if 0 <= c <= len(data))

    # 第一遍：算出每个切片的旧起点 -> 新起点
    seg_new = {}
    remap = []
    cur = 0
    for a, b in zip(cuts, cuts[1:]):
        for i in load_idx:
            if elf.phdrs[i]['offset'] == a:
                want = elf.phdrs[i]['vaddr'] % page_size
                pad = (want - cur % page_size) % page_size
                cur += pad
                seg_new[i] = cur
        remap.append((a, b, cur))
        cur += (b - a)

    # 第二遍：真正拼出新文件
    out = bytearray()
    cur = 0
    for a, b, _ in remap:
        for i in load_idx:
            if elf.phdrs[i]['offset'] == a:
                want = elf.phdrs[i]['vaddr'] % page_size
                pad = (want - cur % page_size) % page_size
                out.extend(b'\0' * pad)
                cur += pad
        out.extend(data[a:b])
        cur += (b - a)

    def map_off(old):
        for a, b, new_a in remap:
            if a <= old < b:
                return new_a + (old - a)
        return None

    res = Elf(bytes(out))
    res.data = bytearray(out)

    # 顺序很关键：节头表与程序头表本身也可能移动，
    # 必须先更新头部里的 e_shoff / e_phoff，并同步 Python 侧缓存的偏移，
    # 否则后续写节头/程序头会落到旧位置，把文件写坏。
    new_shoff = map_off(elf.e_shoff)
    if new_shoff is not None:
        res.e_shoff = new_shoff
        res.set_e_shoff(new_shoff)

    new_phoff = map_off(elf.e_phoff)
    if new_phoff is not None:
        res.e_phoff = new_phoff
        res.set_e_phoff(new_phoff)

    for i in load_idx:
        res.set_phdr_offset(i, seg_new[i], page_size)

    for idx, s in enumerate(elf.shdrs):
        if s['type'] == 0 and s['size'] == 0:
            continue
        m = map_off(s['offset'])
        if m is not None:
            res.set_shdr_offset(idx, m)

    return bytes(res.data)


# ===========================================================================
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('lib')
    ap.add_argument('--page-size', type=int, default=16384)
    ap.add_argument('--out')
    ap.add_argument('--in-place', action='store_true')
    ap.add_argument('--quiet', action='store_true')
    a = ap.parse_args()

    orig = open(a.lib, 'rb').read()
    if not a.quiet:
        print(f'文件: {a.lib}  ({len(orig):,} 字节)')

    need = (not check_readable(orig, a.page_size, '原始@目标页')) if not a.quiet else \
        not check_readable(orig, a.page_size, '')
    if not need:
        if not a.quiet:
            print('  已满足目标页大小，无需补丁')
        return 0

    patched = patch(orig, a.page_size)

    if not a.quiet:
        print('\n--- 补丁后 ---')
    ok = True
    for ps, tag in ((a.page_size, f'{a.page_size // 1024}KB'), (4096, '4KB')):
        ok &= check_structure(patched, ps, f'补丁结构@{tag}')
        ok &= check_readable(patched, ps, f'补丁@‌{tag}')
    ok &= check_equivalence(orig, patched)
    # 4KB 兼容性
    ok &= check_readable(patched, 4096, '补丁@4KB')

    if not ok:
        print('\n[失败] 校验未通过，不写出文件')
        return 1

    out = a.out or (a.lib if a.in_place else a.lib + '.16kb')
    with open(out, 'wb') as f:
        f.write(patched)
    if not a.quiet:
        print(f'\n[成功] 已写出: {out}')
        print(f'       大小 {len(orig):,} -> {len(patched):,} '
              f'(+{len(patched) - len(orig):,} 字节填充)')
    return 0


if __name__ == '__main__':
    sys.exit(main())
