#!/usr/bin/env python3
"""
生成 libnode.so / APK 的构建溯源清单。

为什么需要它
------------
排查「Node 起不来」时最容易浪费时间的情况是：**源码树里的 libnode.so 和
APK 里打进的那个不是同一个文件**（比如某个 ABI 忘了替换、或者构建时被
strip 过）。光看文件大小很容易被误导 —— release 构建会 strip 掉符号，
62MB 的原始库进 APK 后只剩 49MB。

这个脚本把每个 ABI 的 libnode.so 在**进入 APK 前后**的真实身份都记下来：
    * 源码树里的大小 + SHA256
    * 打进 APK 后的大小 + SHA256
    * 每个 PT_LOAD 的 p_align（16KB 页机型能否加载的判据）
    * 是否导出 node::Start（_ZN4node5StartEiPPc）

用法：
    python scripts/gen-build-manifest.py            # 打印到标准输出
    python scripts/gen-build-manifest.py -o 文件     # 写到文件
"""

import argparse
import hashlib
import os
import struct
import sys
import zipfile

PT_LOAD = 1
NODE_START_SYMBOL = b'_ZN4node5StartEiPPc'

ABIS = ('arm64-v8a', 'armeabi-v7a', 'x86_64')


# ---------------------------------------------------------------------------
# 极简 ELF 读取：只需要 p_align 与动态符号表里有没有 node::Start
# ---------------------------------------------------------------------------
def parse_elf(data):
    if data[:4] != b'\x7fELF':
        raise ValueError('不是 ELF 文件')
    is64 = data[4] == 2
    endian = '<' if data[5] == 1 else '>'

    if is64:
        (e_type, e_machine, _ver, _entry, e_phoff, e_shoff, _flags, _ehsize,
         e_phentsize, e_phnum, e_shentsize, e_shnum, e_shstrndx) = struct.unpack_from(
            endian + 'HHIQQQIHHHHHH', data, 0x10)
    else:
        (e_type, e_machine, _ver, _entry, e_phoff, e_shoff, _flags, _ehsize,
         e_phentsize, e_phnum, e_shentsize, e_shnum, e_shstrndx) = struct.unpack_from(
            endian + 'HHIIIIIHHHHHH', data, 0x10)

    machine = {0x28: 'ARM', 0xB7: 'AArch64', 0x3E: 'x86-64', 0x03: 'x86'}.get(
        e_machine, hex(e_machine))

    aligns = []
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        if is64:
            p_type = struct.unpack_from(endian + 'I', data, off)[0]
            p_align = struct.unpack_from(endian + 'Q', data, off + 48)[0]
        else:
            p_type = struct.unpack_from(endian + 'I', data, off)[0]
            p_align = struct.unpack_from(endian + 'I', data, off + 28)[0]
        if p_type == PT_LOAD:
            aligns.append(p_align)

    has_start = NODE_START_SYMBOL in data
    return machine, aligns, has_start


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def fmt_align(aligns):
    return ','.join(f'{a // 1024}KB' if a % 1024 == 0 else str(a) for a in aligns)


def min_align_note(aligns, page_size=16384):
    if not aligns:
        return '（无 PT_LOAD？）'
    smallest = min(aligns)
    if smallest >= page_size:
        return f'[OK] >= {page_size // 1024}KB，16KB 页机型可加载'
    return (f'[!!] 只有 {smallest} —— 16KB 内存页机型上会被动态链接器拒绝加载'
            f'（4KB 页机型不受影响）')


# ---------------------------------------------------------------------------
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('-o', '--out', help='输出文件（默认打印到 stdout）')
    ap.add_argument('--project', default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                    help='项目根目录')
    a = ap.parse_args()

    lines = []
    add = lines.append

    add('Mineradio Android 构建溯源清单（libnode.so / APK）')
    add('=' * 72)

    # ---- 源码树里的 libnode.so -------------------------------------------
    add('')
    add('【1】app/src/main/jniLibs 下的 libnode.so（进 APK 之前）')
    add('-' * 72)
    for abi in ABIS:
        path = os.path.join(a.project, 'app', 'src', 'main', 'jniLibs', abi, 'libnode.so')
        if not os.path.isfile(path):
            add(f'{abi:<14} 缺失：{path}')
            continue
        data = open(path, 'rb').read()
        machine, aligns, has_start = parse_elf(data)
        add(f'{abi:<14} {len(data):>12,} 字节  sha256={sha256(data)[:16]}…')
        add(f'{"":<14} 架构={machine}  LOAD p_align={fmt_align(aligns)}  node::Start={"有" if has_start else "无"}')
        add(f'{"":<14} 16KB 判据：{min_align_note(aligns)}')

    # ---- APK 里的 libnode.so ---------------------------------------------
    add('')
    add('【2】release-assets 下 APK 里的 libnode.so（进 APK 之后）')
    add('    注意：release 构建会 strip 符号，所以这里的大小通常明显小于上面')
    add('-' * 72)

    assets_dir = os.path.join(a.project, 'release-assets')
    apks = sorted(f for f in os.listdir(assets_dir)) if os.path.isdir(assets_dir) else []
    apks = [f for f in apks if f.endswith('.apk')]
    if not apks:
        add('（release-assets 下没有 APK）')
    for name in apks:
        full = os.path.join(assets_dir, name)
        add(f'{name}（{os.path.getsize(full):,} 字节）')
        try:
            with zipfile.ZipFile(full) as z:
                for info in z.infolist():
                    if not (info.filename.startswith('lib/') and info.filename.endswith('.so')):
                        continue
                    data = z.read(info.filename)
                    add(f'    {info.filename:<44} {len(data):>12,} 字节  '
                        f'sha256={sha256(data)[:16]}…')
                    if info.filename.endswith('libnode.so'):
                        machine, aligns, has_start = parse_elf(data)
                        add(f'{"":<8}架构={machine} p_align={fmt_align(aligns)} '
                            f'node::Start={"有" if has_start else "无"}')
        except Exception as exc:                                   # noqa: BLE001
            add(f'    读取失败：{exc}')
        add('')

    text = '\n'.join(lines) + '\n'
    if a.out:
        with open(a.out, 'w', encoding='utf-8') as f:
            f.write(text)
        print(f'已写出：{a.out}')
    else:
        # Windows 控制台默认 GBK，显式用 UTF-8 并容错，避免脚本本身报编码错
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
        sys.stdout.write(text)
    return 0


if __name__ == '__main__':
    sys.exit(main())
