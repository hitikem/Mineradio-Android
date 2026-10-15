#!/usr/bin/env python3
"""
校验 APK 里所有原生库是否满足 16KB 内存页要求。

这是流水线的最后一道关卡：编译完之后直接从 APK 里把 .so 抽出来验证，
不满足就让构建失败，避免「本地看着好好的、发出去在新机型上打不开」。

用法：
    python scripts/verify-apk-16kb.py <apk> [<apk> ...]
"""

import struct
import sys
import zipfile


def load_phdrs(data):
    if data[:4] != b'\x7fELF':
        return None
    is64 = data[4] == 2
    e = '<' if data[5] == 1 else '>'
    if is64:
        e_phoff, = struct.unpack_from(e + 'Q', data, 0x20)
        e_phentsize, e_phnum = struct.unpack_from(e + 'HH', data, 0x36)
    else:
        # 32 位 ELF：e_phoff 在 0x1C，e_phentsize 在 0x2A，e_phnum 在 0x2C
        e_phoff, = struct.unpack_from(e + 'I', data, 0x1C)
        e_phentsize, e_phnum = struct.unpack_from(e + 'HH', data, 0x2A)
    out = []
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        if is64:
            v = struct.unpack_from(e + 'IIQQQQQQ', data, off)
            if v[0] == 1:
                out.append(dict(offset=v[2], vaddr=v[3], filesz=v[5], align=v[7]))
        else:
            v = struct.unpack_from(e + 'IIIIIIII', data, off)
            if v[0] == 1:
                out.append(dict(offset=v[1], vaddr=v[2], filesz=v[4], align=v[7]))
    return out


def check_apk(path, page_size=16384):
    """
    只对 64 位 ABI 强制 16KB 对齐。

    原因：16KB 内存页是 64 位内核的特性（Pixel 8/9 等新机型，且这些机型基本
    已不再支持 32 位）。Google 的 16KB 适配要求也只覆盖 arm64-v8a / x86_64。
    NDK r27 自带的 armeabi-v7a libc++_shared.so 至今仍是 4KB 对齐，即为此意。
    """
    print(f'== {path}')
    problems = []
    checked = 0
    with zipfile.ZipFile(path) as z:
        for name in z.namelist():
            if not name.startswith('lib/') or not name.endswith('.so'):
                continue
            abi = name.split('/')[1]
            required = abi in ('arm64-v8a', 'x86_64')
            data = z.read(name)
            phdrs = load_phdrs(data)
            if phdrs is None:
                problems.append(f'{name}: 不是有效 ELF')
                continue
            checked += 1
            bad = []
            if required:
                for i, p in enumerate(phdrs):
                    if p['align'] < page_size:
                        bad.append(f'LOAD{i} p_align={p["align"]}')
                    elif (p['offset'] % page_size) != (p['vaddr'] % page_size):
                        bad.append(f'LOAD{i} 不同余 '
                                   f'({p["offset"] % page_size} != {p["vaddr"] % page_size})')
            tag = '' if required else ' (32 位，不强制)'
            status = 'OK ' if not bad else 'FAIL'
            print(f'   [{status}] {abi:14s} {name.split("/")[-1]:24s} '
                  f'{len(phdrs)} 段  p_align={sorted({p["align"] for p in phdrs})}{tag}')
            if bad:
                for b in bad:
                    print(f'          x {b}')
                problems.append(f'{name}: ' + '; '.join(bad))

    if not checked:
        problems.append('APK 里没有找到任何 .so')
    return problems


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    all_problems = []
    for apk in sys.argv[1:]:
        all_problems += check_apk(apk)

    print()
    if all_problems:
        print(f'[失败] {len(all_problems)} 处问题：')
        for p in all_problems:
            print('   -', p)
        print('\n这些库在 16KB 内存页机型上会加载失败（表现为「能装上，点开闪退」）。')
        return 1
    print('[通过] 所有原生库都满足 16KB 内存页要求')
    return 0


if __name__ == '__main__':
    sys.exit(main())
