"""原始日志解析核心 — 从 module/jz.py 提取，供 /api/entry/import/rawlog 使用。

设计: 模块端只上传 messages.log 原始行(增量)，服务器端累积全量历史行后
全量解析(转账合并/群收款关联需要跨批次上下文)，再走 qianji 入库管道。
逻辑与 module/jz.py 保持一致 —— 修 bug 时两端同步。
"""
import re
from datetime import datetime

# ── 正则 (与 jz.py 一致) ──
LINE_R = re.compile(r'\[(\d{2}-\d{2} \d{2}:\d{2}:\d{2})\] (.+)')
AMT_R  = re.compile(r'[¥￥]\s*([\d,]++\.?\d*)')
WX_R   = re.compile(r'W\|(收到|发出)\|([^|]+)\|(?:[^|]*\|)?(.*)')
ALI_R  = re.compile(r'A\|(支出|收入)\|([\d.]+)\|([^|]*)\|([^|]*)')

WX_TRANSFER_ID_R = re.compile(r'<transferid><!\[CDATA\[([^\]]+)\]\]></transferid>')
WX_TRANSID_R     = re.compile(r'<transcationid><!\[CDATA\[([^\]]+)\]\]></transcationid>')
WX_PAYSUB_R      = re.compile(r'<paysubtype>\s*(\d+)')
WX_RECV_R        = re.compile(r'<receiver_username><!\[CDATA\[([^\]]+)\]\]></receiver_username>')
WX_PAYER_R       = re.compile(r'<payer_username><!\[CDATA\[([^\]]+)\]\]></payer_username>')
WX_BILLNO_R      = re.compile(r'<billno>([0-9a-fA-F]+)</billno>|billno=([0-9a-fA-F]+)')
WX_PAYLIST_R     = re.compile(r'<newaa>.*?<payerlist>(.*?)</payerlist>', re.S)
WX_RTITLE_R      = re.compile(r'<receivertitle><!\[CDATA\[([^\]]+)\]\]></receivertitle>')
WX_DISPLAY_NAME_R = re.compile(r'<display_name>(?:<!\[CDATA\[)?([^<]+?)(?:\]\]>)?</display_name>')

CSV_HEADER = '时间,分类,二级分类,类型,金额,账户1,账户2,备注,账单标记,手续费,优惠券,标签,账单图片'


def _extract_wx_merchant(content: str) -> str:
    m = WX_DISPLAY_NAME_R.search(content)
    if m:
        name = m.group(1).strip()
        name = name.replace(']]>', '').replace('<![CDATA[', '').strip()
        if name:
            return name
    m_shanghu = re.search(r'商户名称\s*([^\s<\]>]+)', content)
    if m_shanghu:
        return m_shanghu.group(1).strip()
    if '<appname>' in content:
        m2 = re.search(r'<appname>(?:<!\[CDATA\[)?([^<]+?)(?:\]\]>)?</appname>', content)
        if m2:
            return m2.group(1).strip()
    return "微信支付"


def _detect_direction(content: str) -> str:
    title_m = re.search(r'<title><!\[CDATA\[(.+?)\]\]></title>', content)
    if not title_m:
        title_m = re.search(r'<title>([^<]+)</title>', content)
    if title_m:
        title = title_m.group(1)
        if any(kw in title for kw in ('收款', '退款', '已退')):
            return "收入"
    return "支出"


def _transfer_id(content: str) -> str:
    for pat in (WX_TRANSFER_ID_R, WX_TRANSID_R):
        m = pat.search(content)
        if m and m.group(1):
            return m.group(1).strip()
    return ""


def _pay_subtype(content: str) -> str:
    m = WX_PAYSUB_R.search(content)
    return m.group(1) if m else ""


def _count_wxid(content: str, counter: dict):
    for pat in (WX_RECV_R, WX_PAYER_R):
        m = pat.search(content)
        if m and m.group(1) and '@chatroom' not in m.group(1):
            counter[m.group(1)] = counter.get(m.group(1), 0) + 1


def _build_group_pay_rows(me_pays: list, grp_cards: dict, self_wxid: str) -> list:
    rows = []
    for tf, billno in me_pays:
        card = grp_cards.get(billno)
        if not card or not self_wxid:
            continue
        pl, title = card
        for item in pl.split('/'):
            parts = item.strip().split(',')
            if len(parts) >= 2 and parts[0] == self_wxid:
                try:
                    fen = float(parts[1])
                except ValueError:
                    continue
                rows.append((tf, '转账', '', '支出', f'{fen / 100:.2f}', '', '',
                             title or '群收款'))
                break
    return rows


def _append_no_dup(rows: list, cand: tuple):
    def _secs(r) -> int | None:
        try:
            h, m, s = map(int, r[0].split(' ')[1].split(':'))
            return h * 3600 + m * 60 + s
        except Exception:
            return None
    for r in rows:
        if (r[0].split(' ')[0] == cand[0].split(' ')[0]
                and r[4] == cand[4] and r[3] == cand[3] and r[7] == cand[7]):
            t1, t2 = _secs(r), _secs(cand)
            if t1 is not None and t2 is not None and abs(t1 - t2) < 120:
                return
    rows.append(cand)


def parse_lines(lines: list) -> list:
    """解析原始日志行列表(带 [MM-dd HH:mm:ss] 前缀)，返回钱迹 CSV 行列表。

    与 jz.py parse_rows 逻辑一致，仅输入从文件改为行列表。
    """
    rows = []
    now = datetime.now()
    transfer_map = {}
    wxid_counter = {}
    grp_cards = {}
    me_pays = []

    for line in lines:
        m = LINE_R.match(line.rstrip('\n'))
        if not m:
            continue
        ts_str, data = m.group(1), m.group(2)
        try:
            ts = datetime.strptime(f'{now.year}-{ts_str}', '%Y-%m-%d %H:%M:%S')
            if ts > now:
                ts = ts.replace(year=ts.year - 1)
        except Exception:
            continue
        tf = ts.strftime('%Y/%-m/%-d %H:%M:%S')

        # 支付宝
        am = ALI_R.match(data)
        if am:
            merchant = (am.group(3) or '').strip()
            if merchant == '花呗':
                continue
            rows.append((tf, '其他', '', am.group(1), am.group(2), '', '',
                         merchant or am.group(4) or ''))
            continue

        # 微信
        wm = WX_R.match(data)
        if wm:
            content = wm.group(3)
            if '<newaa>' in content:
                billno_m = WX_BILLNO_R.search(content)
                if billno_m:
                    pl_m = WX_PAYLIST_R.search(content)
                    title_m = WX_RTITLE_R.search(content)
                    grp_cards[billno_m.group(1) or billno_m.group(2)] = (
                        pl_m.group(1) if pl_m else '',
                        title_m.group(1) if title_m else '')
                continue
            if '你支付了' in content and '群收款' in content:
                billno_m = WX_BILLNO_R.search(content)
                if billno_m:
                    me_pays.append((tf, billno_m.group(1) or billno_m.group(2)))
                continue
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
                tid = _transfer_id(content)
                if tid:
                    sub = _pay_subtype(content)
                    prev = transfer_map.get(tid)
                    if prev is None or (sub == '1' and prev[3] != '1'):
                        transfer_map[tid] = (tf, direc, amount, sub)
                    _count_wxid(content, wxid_counter)
                    continue
                rows.append((tf, '转账', '', direc, amount, '', '', '微信转账'))
            elif '318767153' in mtype:
                merchant = _extract_wx_merchant(content)
                direc = _detect_direction(content)
                rows.append((tf, '其他', '', direc, amount, '', '', merchant))
            continue

        # U| 更新行
        if data.startswith('U|'):
            inner = data[2:]
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
                        tid = _transfer_id(content)
                        if tid:
                            sub = _pay_subtype(content)
                            prev = transfer_map.get(tid)
                            if prev is None or (sub == '1' and prev[3] != '1'):
                                transfer_map[tid] = (tf, direc, amount, sub)
                            _count_wxid(content, wxid_counter)
                            continue
                        _append_no_dup(rows, (tf, '转账', '', direc, amount, '', '', '微信转账'))
                    elif '318767153' in mtype:
                        merchant = _extract_wx_merchant(content)
                        direc = _detect_direction(content)
                        _append_no_dup(rows, (tf, '其他', '', direc, amount, '', '', merchant))

    for tf, direc, amount, _sub in sorted(transfer_map.values(), key=lambda x: x[0]):
        rows.append((tf, '转账', '', direc, amount, '', '', '微信转账'))
    if me_pays:
        self_wxid = max(wxid_counter, key=wxid_counter.get) if wxid_counter else ''
        rows.extend(_build_group_pay_rows(me_pays, grp_cards, self_wxid))
    return rows


def lines_to_csv(lines: list) -> str:
    """解析原始行并生成钱迹 CSV 文本(含表头)，供 /rawlog 接口复用 qianji 入库管道。"""
    rows = parse_lines(lines)
    if not rows:
        return ""
    out = [CSV_HEADER]
    for r in rows:
        out.append(','.join(r) + ',,,,,,')
    return '\n'.join(out) + '\n'
