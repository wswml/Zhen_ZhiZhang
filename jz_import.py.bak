#!/usr/bin/env python3
"""📲 jz - 一键导出微信/支付宝账单 → 导入服务器 Cashbook
=============================================================
用法:  cd ~/cashbook && python3 jz_import.py           # 默认账本
       cd ~/cashbook && python3 jz_import.py -b ID     # 指定账本
       cd ~/cashbook && python3 jz_import.py --list    # 列出账本

流程:  module/jz.py 生成CSV → scp 到服务器 → 增量导入 Cashbook
"""

import subprocess, os, sys, json, argparse
from datetime import datetime

# 确保能找到项目根（无论从哪个目录运行）
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# ── 统一配置 ──
from cashbook_config import (
    SERVER_HOST as HOST, SERVER_PORT as PORT,
    ADMIN_USER as USER, ADMIN_PASS as PASS,
    DEFAULT_BOOK_ID, JZ_PARSER, CSV_OUTPUT as CSV_LOCAL,
    SSH_USER,
)

SSH_HOST = HOST   # 同机部署


def run(cmd, timeout=120):
    p = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)
    return p.stdout.strip(), p.stderr.strip(), p.returncode


def ssh(cmd, timeout=120):
    return run(f'ssh -o ConnectTimeout=10 {SSH_USER}@{SSH_HOST} {sh_quote(cmd)}', timeout=timeout)


def sh_quote(s):
    """Simple single-quote shell escaping."""
    return "'" + s.replace("'", "'\\''") + "'"


def list_books(token: str) -> list[dict]:
    """列出服务器上所有账本"""
    out, err, code = run(
        f'curl -s http://{HOST}:{PORT}/api/entry/book/all'
        f' -H "Authorization: Bearer {token}"', timeout=10)
    if code != 0:
        print(f"  FAIL: {err}")
        sys.exit(1)
    try:
        d = json.loads(out)
        if d.get("code") != 200 or not d.get("data"):
            print(f"  FAIL: {d.get('message', '无数据')}")
            sys.exit(1)
        return d["data"]
    except Exception as e:
        print(f"  FAIL: {e}\n{out[:200]}")
        sys.exit(1)


def main():
    parser = argparse.ArgumentParser(
        description="微信/支付宝账单导出 + 导入 Cashbook",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""示例:
  python3 ~/jz_import.py                     # 默认账本
  python3 ~/jz_import.py -b 1-xxxxxxx        # 指定账本ID
  python3 ~/jz_import.py --list-books         # 列出所有账本
        """)
    parser.add_argument("-b", "--book", help="账本ID（默认: %s）" % DEFAULT_BOOK_ID)
    parser.add_argument("--list-books", action="store_true", help="列出所有账本")
    args = parser.parse_args()

    TOTAL = 4
    ts = datetime.now().strftime("%Y-%m-%d %H:%M")
    print("=" * 52)
    print("  jz - 微信/支付宝 账单导出 + 导入 Cashbook")
    print(f"  {ts}")
    print("=" * 52)

    # ── 登录（list-books 也要登录） ──
    print(f"\n[1/{TOTAL}] 登录 Cashbook...")
    login_payload = json.dumps({"username": USER, "password": PASS})
    out, err, code = run(
        f'curl -s http://{HOST}:{PORT}/api/auth/login'
        f' -H "Content-Type: application/json"'
        f" -d {sh_quote(login_payload)}", timeout=15)
    if code != 0:
        print(f"  FAIL: curl:\n{err}")
        sys.exit(1)
    try:
        d = json.loads(out)
        if d.get("code") != 200:
            print(f"  FAIL: {d.get('message')}")
            sys.exit(1)
        token = d["data"]["access_token"]
        print("  OK")
    except Exception as e:
        print(f"  FAIL: parse response: {e}\n{out[:200]}")
        sys.exit(1)

    # ── --list-books ──
    if args.list_books:
        print()
        books = list_books(token)
        if not books:
            print("  没有账本")
        else:
            print("  账本列表:")
            for b in books:
                print(f"    {b['book_id']:20s}  {b['book_name']}  (预算: ¥{b.get('budget',0):.0f})")
        return

    # ── 确定账本 ──
    book_id = args.book or DEFAULT_BOOK_ID

    # ── 1. 生成 CSV ──
    print(f"\n[2/{TOTAL}] 运行 module/jz.py 生成账单 CSV...")
    out, err, code = run(f"cd {os.path.dirname(os.path.abspath(__file__))} && python3 {JZ_PARSER}")
    if code != 0:
        print(f"  FAIL: jz.py:\n{err}")
        sys.exit(1)
    print("  OK")
    for line in out.split("\n"):
        if "新增" in line or "累计" in line or "文件" in line:
            print(f"     {line.strip()}")

    if not os.path.exists(CSV_LOCAL):
        print(f"  FAIL: CSV not found at {CSV_LOCAL}")
        sys.exit(1)
    print(f"  {CSV_LOCAL} ({os.path.getsize(CSV_LOCAL)//1024} KB)")

    # ── 2. scp 到服务器 ──
    print(f"\n[3/{TOTAL}] 上传 CSV 到服务器...")
    remote = f"/tmp/qj_{os.getpid()}.csv"
    out, err, code = run(f"scp {CSV_LOCAL} {SSH_USER}@{SSH_HOST}:{remote}", timeout=30)
    if code != 0:
        print(f"  FAIL: scp:\n{err}")
        sys.exit(1)
    print(f"  OK -> {remote}")

    # ── 3/4. 增量导入（已登录有 token，直接导入）──
    print(f"\n[4/{TOTAL}] 增量导入 Cashbook (账本: {book_id})...")
    import_cmd = (
        f'curl -s -X POST http://localhost:{PORT}/api/entry/import/qianji'
        f' -H "Authorization: Bearer {token}"'
        f' -F "file=@{remote}"'
        f' -F "book_id={book_id}"'
    )
    out, err, code = ssh(import_cmd)
    if code != 0:
        print(f"  FAIL: import:\n{err}\n{out[:300]}")
        sys.exit(1)
    try:
        d = json.loads(out)
        if d.get("code") == 200:
            data = d["data"]
            detail = []
            if "count" in data:
                detail.append(f"新增 {data['count']} 条")
            if "skipped" in data:
                detail.append(f"跳过 {data['skipped']} 条")
            if "classification" in data:
                cls = data["classification"]
                detail.append("分类: " + ", ".join(f"{k}={v}" for k, v in cls.items()))
            print(f"  OK: {'; '.join(detail)}")
        else:
            print(f"  WARN: {d.get('message')}")
    except Exception as e:
        print(f"  FAIL: parse: {e}\n{out[:300]}")
        sys.exit(1)

    # Cleanup
    ssh(f"rm -f {remote}")

    print(f"\n{'=' * 52}")
    print("  OK!")
    print(f"  http://{HOST}:{PORT}")
    print(f"{'=' * 52}")


if __name__ == "__main__":
    main()
