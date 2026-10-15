#!/usr/bin/env python3
"""
扫描 node-app/node_modules，找出**在 Node 18 下无法被 require() 加载**的包。

背景
----
nodejs-mobile 的 libnode.so 是 **Node 18.20.4**，而 Node 18 的 `require()`
不支持加载 ESM。如果某个依赖是 `"type": "module"`，并且 package.json 的
exports 里没有 `require` 条件，那么 `require()` 会抛：

    Error [ERR_PACKAGE_PATH_NOT_EXPORTED]: No "exports" main defined in ...

上游 Mineradio 桌面版跑在 Electron（Node 22，支持 require(esm)）上，
所以同一个 node_modules 在桌面端正常、在安卓端一 require 就崩 —— 这类
问题只会在真机上暴露，本地用新 Node 测试永远测不出来。

用法：
    python scripts/scan-node18-incompatible.py [node-app 目录]
"""

import json
import os
import sys


def load_json(path):
    try:
        with open(path, 'r', encoding='utf-8') as f:
            return json.load(f)
    except Exception:                                              # noqa: BLE001
        return None


def has_require_condition(exports):
    """exports 字段里是否存在 require 条件（含嵌套）"""
    if exports is None:
        return False
    if isinstance(exports, str):
        return False
    if isinstance(exports, list):
        return any(has_require_condition(x) for x in exports)
    if isinstance(exports, dict):
        for key, value in exports.items():
            if key == 'require':
                return True
            if has_require_condition(value):
                return True
    return False


def classify(pkg_dir):
    """返回 (问题描述 或 None)"""
    pkg_json = os.path.join(pkg_dir, 'package.json')
    data = load_json(pkg_json)
    if data is None:
        return 'package.json 读取失败'

    if data.get('type') != 'module':
        return None

    exports = data.get('exports')
    main = data.get('main')

    # type=module 但没有 require 条件 → Node 18 的 require() 会失败
    if has_require_condition(exports):
        return None
    if exports is None and main and str(main).endswith('.cjs'):
        return None

    engines = (data.get('engines') or {}).get('node', '')
    return f'type=module，exports 无 require 条件（engines={engines or "未声明"}）'


def iter_packages(root):
    """遍历 node_modules，含 @scope/name 与嵌套 node_modules"""
    if not os.path.isdir(root):
        return
    for entry in sorted(os.listdir(root)):
        if entry.startswith('.'):
            continue
        full = os.path.join(root, entry)
        if not os.path.isdir(full):
            continue
        if entry.startswith('@'):
            for sub in sorted(os.listdir(full)):
                pkg = os.path.join(full, sub)
                if os.path.isdir(pkg):
                    yield entry + '/' + sub, pkg
        else:
            yield entry, full


def main():
    project = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    root = sys.argv[1] if len(sys.argv) > 1 else os.path.join(project, 'node-app')
    modules = os.path.join(root, 'node_modules')

    sys.stdout.reconfigure(encoding='utf-8', errors='replace')

    if not os.path.isdir(modules):
        print(f'找不到 node_modules：{modules}')
        return 1

    broken = []
    total = 0
    for name, path in iter_packages(modules):
        total += 1
        reason = classify(path)
        if reason:
            version = (load_json(os.path.join(path, 'package.json')) or {}).get('version', '?')
            broken.append((name, version, reason))
        # 递归进嵌套 node_modules
        for sub_name, sub_path in iter_packages(os.path.join(path, 'node_modules')):
            total += 1
            sub_reason = classify(sub_path)
            if sub_reason:
                sub_version = (load_json(os.path.join(sub_path, 'package.json')) or {}).get('version', '?')
                broken.append((f'{name}/node_modules/{sub_name}', sub_version, sub_reason))

    print(f'扫描 {modules}')
    print(f'共 {total} 个包，其中 {len(broken)} 个在 Node 18 下无法 require：')
    print('-' * 78)
    for name, version, reason in broken:
        print(f'  {name}@{version}')
        print(f'      {reason}')

    if not broken:
        print('  （没有发现问题包）')
        print('')
        print('结论：node-app 可以在 nodejs-mobile 的 Node 18 上正常 require。')
        return 0

    print('')
    print('说明：以上包在 Node 20.19+/22+（支持 require(esm)）上可以正常 require，')
    print('      所以桌面 Electron 版不会暴露这个问题，只有 nodejs-mobile 的 Node 18 会。')
    print('')
    print('处理办法：把对应依赖固定到仍然提供 CommonJS 的版本，例如')
    print('      music-metadata 用 dist-tags.cjs 指向的 7.14.0。')
    return 1


if __name__ == '__main__':
    sys.exit(main())
