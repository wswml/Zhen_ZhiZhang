#!/usr/bin/env python3
"""微信+支付宝 账单导出 —— 增量追加模式
=========================================
用法: cd ~/cashbook && python3 module/jz.py
输出: /sdcard/Download/qianji.csv (单一文件，每次只追加新增数据)

原理:
  1. 支付宝轮询新记录 → 追加到 messages.log
  2. 扫 messages.log 全部记录，生成行数据
  3. 读已有 CSV，逐一对比：新行不存在则追加

去重: (时间, 类型, 金额, 备注) 四元组
"""

import subprocess, sqlite3, os, re, json, sys
from datetime import datetime, timedelta

# ── 统一配置 ──
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.realpath(__file__))))
from cashbook_config import WECHAT_LOG, ALIPAY_DB, ALIPAY_TMP, ALIPAY_LAST, CSV_OUTPUT

# ── 路径 ──
LOG_FILE   = WECHAT_LOG
CSV_FILE   = CSV_OUTPUT              # ★ 单文件
LAST_ID    = ALIPAY_LAST
ALIPAY_TMP = ALIPAY_TMP   # 只读副本（支付宝 DB 需要 su 复制）

# ── 正则 ──
LINE_R = re.compile(r'\[(\d{2}-\d{2} \d{2}:\d{2}:\d{2})\] (.+)')
AMT_R  = re.compile(r'[¥￥]\s*([\d,]++\.?\d*)')
WX_R    = re.compile(r'W\|(收到|发出)\|([^|]+)\|(?:[^|]*\|)?(.*)')
ALI_R   = re.compile(r'A\|(支出|收入)\|([\d.]+)\|([^|]*)\|([^|]*)')

# 微信支付扣费凭证 提取商户名
WX_DISPLAY_NAME_R = re.compile(r'<display_name>(?:<!\[CDATA\[)?([^<]+?)(?:\]\]>)?</display_name>')

CSV_HEADER = '时间,分类,二级分类,类型,金额,账户1,账户2,备注,账单标记,手续费,优惠券,标签,账单图片'


def _extract_wx_merchant(content: str) -> str:
    """从微信支付扣费凭证 XML 中提取商户名"""
    m = WX_DISPLAY_NAME_R.search(content)
    if m:
        name = m.group(1).strip()
        # 清理 CDATA 残留 (如 ]]> 被误捕获)
        name = name.replace(']]>', '').replace('<![CDATA[', '').strip()
        if name:
            return name
    # 兜底1: 从退款/扣费描述中提取 "商户名称xxx"
    m_shanghu = re.search(r'商户名称\s*([^\s<\]>]+)', content)
    if m_shanghu:
        return m_shanghu.group(1).strip()
    # 兜底2: 取微信支付来源名
    if '<appname>' in content:
        m2 = re.search(r'<appname>(?:<!\[CDATA\[)?([^<]+?)(?:\]\]>)?</appname>', content)
        if m2:
            return m2.group(1).strip()
    return "微信支付"


def _detect_direction(content: str) -> str:
    """从微信扣费凭证 XML 判断资金方向: 退款→收入, 已支付/已扣费→支出"""
    # 先试 CDATA 格式: <title><![CDATA[xxx]]></title>
    title_m = re.search(r'<title><!\[CDATA\[(.+?)\]\]></title>', content)
    # 再试纯文本格式: <title>xxx</title>
    if not title_m:
        title_m = re.search(r'<title>([^<]+)</title>', content)
    if title_m:
        title = title_m.group(1)
        if any(kw in title for kw in ('收款', '退款', '已退')):
            return "收入"
    return "支出"


# ── 支付宝轮询 ──

def poll_alipay() -> int:
    """支付宝轮询，新记录追加到 messages.log"""
    try:
        subprocess.run(["su", "-c", f"cp {ALIPAY_DB} {ALIPAY_TMP} && chmod 644 {ALIPAY_TMP}"],
                       capture_output=True, timeout=10, check=True)
    except Exception as e:
        print(f"  支付宝 DB 复制失败: {e}")
        return 0

    last_gmt = 0
    if os.path.exists(LAST_ID):
        try:
            last_gmt = int(open(LAST_ID).read().strip())
        except:
            last_gmt = 0

    db = sqlite3.connect(ALIPAY_TMP)
    new_count = 0
    try:
        c = db.execute(
            "SELECT gmtCreate, content FROM service_message "
            "WHERE title='支付助手' AND gmtCreate > ? ORDER BY gmtCreate",
            (last_gmt,))
        lines = []
        max_gmt = last_gmt
        for row in c.fetchall():
            try:
                data = json.loads(row[1])
                if not data.get("isPaymentMsg"):
                    continue
                amt = data.get("content", "")
                if not amt:
                    continue
                top = data.get("topSubContent", "")
                merchant = data.get("sceneExt2", {}).get("sceneName", "")
                method = data.get("assistMsg1", "")
                ts = datetime.fromtimestamp(row[0] / 1000).strftime("%m-%d %H:%M:%S")
                direc = "支出" if "扣款" in top or "付款" in top else "收入"
                lines.append(f"[{ts}] A|{direc}|{amt}|{merchant}|{method}\n")
                max_gmt = max(max_gmt, row[0])
            except:
                pass
        if lines:
            os.makedirs(os.path.dirname(LOG_FILE), exist_ok=True)
            with open(LOG_FILE, 'a', encoding='utf-8') as f:
                f.writelines(lines)
            with open(LAST_ID, 'w') as f:
                f.write(str(max_gmt))
            new_count = len(lines)
            print(f"  支付宝新增: {new_count} 条")
    finally:
        db.close()
    return new_count


# ── 从 messages.log 生成行数据 ──

def parse_rows() -> list:
    """解析 messages.log 所有记录，返回钱迹 CSV 行列表"""
    rows = []
    if not os.path.exists(LOG_FILE):
        return rows

    now = datetime.now()
    with open(LOG_FILE, 'r', encoding='utf-8', errors='replace') as f:
        for line in f:
            m = LINE_R.match(line)
            if not m:
                continue
            ts_str, data = m.group(1), m.group(2)
            try:
                ts = datetime.strptime(f'{now.year}-{ts_str}', '%Y-%m-%d %H:%M:%S')
                if ts > now:
                    ts = ts.replace(year=ts.year - 1)
            except:
                continue
            tf = ts.strftime('%Y/%-m/%-d %H:%M:%S')

            # 支付宝
            am = ALI_R.match(data)
            if am:
                merchant = (am.group(3) or '').strip()
                # 花呗还款: 商户是"花呗" → 跳过（实际消费已在花呗消费时记录）
                if merchant == '花呗':
                    continue
                rows.append((tf, '其他', '', am.group(1), am.group(2), '', '',
                            merchant or am.group(4) or ''))
                continue

            # 微信支付
            wm = WX_R.match(data)
            if wm:
                content = wm.group(3)
                amt_m = AMT_R.search(content)
                if not amt_m:
                    continue
                amount = amt_m.group(1).replace(',', '')
                mtype = wm.group(2)
                if mtype.startswith('other:') and '318767153' not in mtype:
                    continue
                if mtype == '红包':
                    direc = '支出' if wm.group(1) == '发出' else '收入'
                    rows.append((tf, '红包', '', direc, amount, '', '', '微信红包'))
                elif mtype == '转账':
                    direc = '支出' if wm.group(1) == '发出' else '收入'
                    rows.append((tf, '转账', '', direc, amount, '', '', '微信转账'))
                elif '318767153' in mtype:
                    merchant = _extract_wx_merchant(content)
                    direc = _detect_direction(content)
                    rows.append((tf, '其他', '', direc, amount, '', '', merchant))
                continue

            # U|更新行：剥离 U| 前缀，作为 W| 重新解析
            if data.startswith('U|'):
                inner = data[2:]  # 去掉 "U|"
                wm2 = WX_R.match(inner)
                if wm2:
                    content = wm2.group(3)
                    amt_m = AMT_R.search(content)
                    if amt_m:
                        amount = amt_m.group(1).replace(',', '')
                        mtype = wm2.group(2)
                        if mtype.startswith('other:') and '318767153' not in mtype:
                            continue
                        elif mtype == '红包' or mtype == '红包记录':
                            direc = '支出' if wm2.group(1) == '发出' else '收入'
                            _append_no_dup(rows, (tf, '红包', '', direc, amount, '', '', '微信红包'))
                        elif mtype == '转账':
                            direc = '支出' if wm2.group(1) == '发出' else '收入'
                            _append_no_dup(rows, (tf, '转账', '', direc, amount, '', '', '微信转账'))
                        elif '318767153' in mtype:
                            merchant = _extract_wx_merchant(content)
                            direc = _detect_direction(content)
                            _append_no_dup(rows, (tf, '其他', '', direc, amount, '', '', merchant))

    return rows


# ── 去重追加 ──

def _append_no_dup(rows: list, cand: tuple):
    """追加候选行。若 rows 已有同日+同金额+同方向+同商户的行（同一笔交易的
    W| + U|W| 双记录，如转账），跳过 U|W| 以免重复。仅 U| 更新行调用。
    """
    for r in rows:
        if (r[0].split(' ')[0] == cand[0].split(' ')[0]
                and r[4] == cand[4] and r[3] == cand[3] and r[7] == cand[7]):
            return
    rows.append(cand)


def _fingerprint(r: tuple) -> str:
    """交易指纹: 完整时间(秒) + 金额 + 方向 + 商户名（去除尾逗号空格）
    秒级时间避免同日同金额同商户的真实多笔交易被误去重
    （2026-08-02 修复: 原日期级指纹误杀 17:00:19/17:00:48 两笔 7.00 琪成超市）。
    """
    note = r[7].strip().rstrip(',')            # 备注清理
    return f'{r[0]}|{r[4]}|{r[3]}|{note}'


def dedup_append(new_rows: list):
    """读已有 CSV，按 (日期, 金额, 方向, 备注) 指纹去重后追加"""
    existing_fps = set()
    if os.path.exists(CSV_FILE):
        with open(CSV_FILE, 'r', encoding='utf-8-sig') as f:
            first = f.readline()  # 表头
            for line in f:
                line = line.strip()
                if not line:
                    continue
                # 从 CSV 行重建 r（仅取前 8 字段）
                parts = line.split(',')
                r = tuple(parts[:8])
                existing_fps.add(_fingerprint(r))

    appended = 0
    written_header = False
    mode = 'a' if os.path.exists(CSV_FILE) else 'w'

    with open(CSV_FILE, mode, encoding='utf-8', newline='') as f:
        if mode == 'w':
            f.write(CSV_HEADER + '\n')
            written_header = True

        seen_this_run = set()
        for r in new_rows:
            fp = _fingerprint(r)
            if fp in existing_fps or fp in seen_this_run:
                continue
            csv_line = ','.join(r) + ',,,,,,'
            if not written_header:
                written_header = True
            f.write(csv_line + '\n')
            appended += 1
            seen_this_run.add(fp)

    return appended


# ── 主流程 ──

def _minute_key(r: tuple) -> str:
    """分钟级匹配键: 日期+分钟 + 金额 + 方向 + 商户（用于新旧 CSV 行对齐）"""
    t = r[0]
    if ' ' in t:
        d, tm = t.split(' ', 1)
        t = f'{d} {tm[:5]}'  # "2026/8/1 17:00:48" → "2026/8/1 17:00"
    note = r[7].strip().rstrip(',')
    return f'{t}|{r[4]}|{r[3]}|{note}'


def rebuild_csv():
    """全量重建 CSV（--rebuild，迁移用，2026-08-02）：
    1. 新解析 rows（秒级时间，_append_no_dup 合并 W|/U|W| 双记录）
    2. 旧 CSV 行：分钟级键在新行中不存在 → 保留（log 截断丢失的历史）
    3. 新行全部写入（秒级指纹去重，恢复被误杀的同日同金额同商户多笔）
    """
    import shutil
    bak = CSV_FILE + '.bak'
    if os.path.exists(CSV_FILE):
        shutil.copy2(CSV_FILE, bak)
        print(f"  旧 CSV 已备份: {bak}")

    rows = parse_rows()
    # 秒级指纹去重（同一笔的双记录已在 parse_rows 内合并）
    seen, new_rows = set(), []
    for r in rows:
        fp = _fingerprint(r)
        if fp in seen:
            continue
        seen.add(fp)
        new_rows.append(r)

    # 旧 CSV 行中，分钟级键不在新行集合里的才保留（防 log 截断丢历史）
    kept_old = []
    if os.path.exists(bak):
        new_min_keys = {_minute_key(r) for r in new_rows}
        with open(bak, 'r', encoding='utf-8-sig') as f:
            f.readline()  # 表头
            for line in f:
                line = line.strip()
                if not line:
                    continue
                parts = line.split(',')
                r = tuple(parts[:8])
                # 花呗还款: 商户是"花呗" → 跳过（实际消费已在花呗消费时记录）
                if r[7].strip() == '花呗':
                    continue
                if _minute_key(r) not in new_min_keys:
                    kept_old.append(r)

    with open(CSV_FILE, 'w', encoding='utf-8-sig', newline='') as f:
        f.write(CSV_HEADER + '\n')
        for r in sorted(kept_old + new_rows, key=lambda x: x[0]):
            f.write(','.join(r) + ',,,,,,\n')
    print(f"  重建完成: 新 {len(new_rows)} 条 + 保留旧 {len(kept_old)} 条 = {len(kept_old) + len(new_rows)} 条")


def main():
    print("=" * 42)
    print("  微信+支付宝 账单导出 (增量模式)")
    print("  " + datetime.now().strftime("%Y-%m-%d %H:%M"))
    print("=" * 42)

    # Step 1: 支付宝轮询
    print("\n[1/3] 支付宝轮询...")
    ali_new = poll_alipay()

    # Step 2: 扫 log 生成行
    print("\n[2/3] 扫描 messages.log...")
    rows = parse_rows()
    print(f"  日志解析: {len(rows)} 条")

    # Step 3: 去重追加
    print(f"\n[3/3] 写入 {CSV_FILE} ...")
    n = dedup_append(rows)

    # 统计
    wechat = sum(1 for r in rows if r[7] in ('微信支付', '微信红包', '微信转账'))
    alipay = len(rows) - wechat
    total = sum(float(r[4]) for r in rows if r[4])

    print(f"\n  微信: {wechat} 条  支付宝: {alipay} 条")
    print(f"  新增: {n} 条  (去重跳过 {len(rows) - n} 条)")
    print(f"  累计: ¥{total:.2f}")
    print(f"  文件: {CSV_FILE}")


if __name__ == '__main__':
    if '--rebuild' in sys.argv:
        print("=" * 42)
        print("  全量重建 CSV (秒级指纹迁移)")
        print("=" * 42)
        rebuild_csv()
    else:
        main()
