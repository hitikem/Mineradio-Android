#!/usr/bin/env python3
"""
用真机同款的 Node 版本（nodejs-mobile 的 Node 18.20.4）在桌面上启动
node-app，验证 server.js 是否真的能监听端口。

为什么必须用 Node 18
--------------------
内嵌运行时是 nodejs-mobile 的 **Node 18.20.4**。Node 18 的 `require()`
不支持加载 ESM 包，而本机/CI 上常见的是 Node 20.19+/22+，它们支持
`require(esm)`。于是会出现：

    本地用 Node 22 测：一切正常
    装到手机上：server.js 在 require 阶段抛 ERR_PACKAGE_PATH_NOT_EXPORTED
                → 进程退出 → 端口从未监听 → 「本地音乐服务启动失败」

所以**只有用 Node 18 测才有意义**。这个脚本就是干这件事的：
它不会修改 node-app 目录（用户数据写到临时目录），启动后在超时内
轮询 `http://127.0.0.1:<port>/`，把结果和日志一起打出来。

用法：
    python scripts/verify-node-app-runtime.py --node <node18 可执行文件>

    # 若本地没有 Node 18，脚本会提示下载地址，例如：
    #   https://nodejs.org/dist/v18.20.4/node-v18.20.4-win-x64.zip
"""

import argparse
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request


def default_project():
    return os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def prepare_user_data(root):
    dirs = ['', 'tmp', 'logs', 'cache']
    for d in dirs:
        os.makedirs(os.path.join(root, d), exist_ok=True)


def check_writable(root):
    """
    自检：目录真的能写文件吗。

    必须做这一步。`NeteaseCloudMusicApi/main.js` 会在 `os.tmpdir()` 下写
    `anonymous_token`，而受限环境（沙箱、受保护的系统临时目录）里新建文件
    会 EPERM。那种失败看起来和应用缺陷一模一样，会把结论带偏 ——
    所以先自己写一个探针文件，写不进去就直接判定为环境问题。
    """
    probe_path = os.path.join(root, 'logs', '.write-probe')
    try:
        with open(probe_path, 'w', encoding='utf-8') as f:
            f.write('ok')
        os.remove(probe_path)
        return True, ''
    except OSError as exc:
        return False, f'{exc.__class__.__name__}: {exc}'


def build_env(project_dir, user_data, port):
    env = dict(os.environ)
    env.update({
        'PORT': str(port),
        'HOST': '127.0.0.1',
        'MINERADIO_ANDROID_USER_DATA': user_data,
        'MINERADIO_PROJECT_DIR': project_dir,
        'MINERADIO_VERSION': 'verify',
        'NODE_ENV': 'production',
        'HOME': user_data,
        'TMPDIR': os.path.join(user_data, 'tmp'),
        'TMP': os.path.join(user_data, 'tmp'),
        'XDG_CACHE_HOME': os.path.join(user_data, 'cache'),
        'NODE_PATH': os.path.join(project_dir, 'node_modules'),
    })
    # 清掉可能从外部继承、会干扰判断的变量
    for key in ('NODE_OPTIONS',):
        env.pop(key, None)
    return env


def probe(port, timeout=3.0):
    url = f'http://127.0.0.1:{port}/'
    try:
        with urllib.request.urlopen(url, timeout=timeout) as resp:
            body = resp.read()
            return resp.status, len(body)
    except Exception:                                              # noqa: BLE001
        return None, 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--node', required=True, help='Node 18 可执行文件路径')
    ap.add_argument('--project', default=default_project(), help='项目根目录')
    ap.add_argument('--port', type=int, default=17911)
    ap.add_argument('--timeout', type=float, default=60.0)
    ap.add_argument('--user-data', help='用户数据目录（默认建在系统临时目录下）')
    ap.add_argument('--keep-user-data', action='store_true')
    a = ap.parse_args()

    sys.stdout.reconfigure(encoding='utf-8', errors='replace')

    project_dir = os.path.abspath(os.path.join(a.project, 'node-app'))
    entry = os.path.join(project_dir, 'main.js')

    if not os.path.isfile(entry):
        print(f'找不到入口脚本：{entry}')
        return 2

    version = subprocess.run([a.node, '-v'], capture_output=True, text=True).stdout.strip()
    print(f'使用运行时：{a.node}  ({version})')

    if not version.startswith('v18.'):
        print(f'[警告] 这不是 Node 18（实际 {version}）。')
        print('       新版本支持 require(esm)，测不出手机上的 ESM 兼容问题，结论不可信。')

    # 用户数据目录：先建好并自检可写，避免把「环境不可写」误判成「应用有缺陷」
    if a.user_data:
        user_data = os.path.abspath(a.user_data)
        os.makedirs(user_data, exist_ok=True)
        owned = False
    else:
        user_data = tempfile.mkdtemp(prefix='mineradio-verify-')
        owned = True

    prepare_user_data(user_data)
    print(f'用户数据目录：{user_data}')

    writable, why = check_writable(user_data)
    if not writable:
        print('')
        print('=' * 70)
        print(f'[环境不可用] 无法在该目录写入文件：{why}')
        print('')
        print('这不是 node-app 的问题：NeteaseCloudMusicApi 需要在临时目录下写')
        print('anonymous_token，而当前目录被拒绝写入（受限沙箱 / 受保护的系统临时目录）。')
        print('请用 --user-data 指定一个可写目录后重试，例如：')
        print(r'  --user-data D:\temp\mineradio-verify')
        if owned:
            import shutil
            shutil.rmtree(user_data, ignore_errors=True)
        return 2

    env = build_env(project_dir, user_data, a.port)
    print(f'启动 main.js（端口 {a.port}）…')

    log_path = os.path.join(user_data, 'stdout.txt')
    with open(log_path, 'w', encoding='utf-8', errors='replace') as out:
        proc = subprocess.Popen(
            [a.node, entry],
            cwd=project_dir, env=env,
            stdout=out, stderr=subprocess.STDOUT,
        )

    status, length = None, 0
    deadline = time.time() + a.timeout
    try:
        while time.time() < deadline:
            time.sleep(1.0)
            if proc.poll() is not None:
                break
            status, length = probe(a.port)
            if status:
                break
    finally:
        if proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                proc.kill()

    exit_code = proc.returncode
    print('')
    print('=' * 70)
    if status:
        print(f'[通过] 首页可访问：HTTP {status}，{length} 字节')
        result = 0
    else:
        print(f'[失败] 端口 {a.port} 在 {a.timeout:.0f}s 内没有可访问的服务'
              f'（进程退出码={exit_code}）')
        result = 1

    for name in ('stdout.txt', os.path.join('logs', 'mineradio-node.log')):
        path = os.path.join(user_data, name)
        if not os.path.isfile(path):
            continue
        print('')
        print(f'--- {name} ---')
        try:
            with open(path, 'r', encoding='utf-8', errors='replace') as f:
                text = f.read()
            print('\n'.join(text.splitlines()[:60]))
        except Exception as exc:                                   # noqa: BLE001
            print(f'（读取失败：{exc}）')

    print('')
    if result == 0:
        print('结论：node-app 可以在 nodejs-mobile 的 Node 18 上正常启动并监听端口。')
    else:
        print('结论：node-app 在 Node 18 上启动失败，装到手机上同样会失败。')
        print('      常见原因是依赖里有纯 ESM 包，先跑：')
        print('      python scripts/scan-node18-incompatible.py')

    if not a.keep_user_data and owned:
        import shutil
        shutil.rmtree(user_data, ignore_errors=True)

    return result


if __name__ == '__main__':
    sys.exit(main())
