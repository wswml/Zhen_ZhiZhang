"""数据导入路由 - CSV导入 + 自动分类"""
from fastapi import APIRouter, Depends, UploadFile, File, Form, Header, HTTPException
from sqlalchemy.orm import Session
import csv
import io
import re
import logging
import os

from app.models.database import get_db
from app.models.models import Flow, TypeRelation
from app.utils.auth import get_current_user_id
from app.utils.common import success, error
from app.utils.classifier import classify_transaction
from app.utils.rawlog_parser import parse_lines, CSV_HEADER

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/entry/import", tags=["导入"])

# 模块端上传用固定 token（与 password.json 的 module_token 一致）
MODULE_TOKEN = os.getenv("MODULE_TOKEN", "")

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/entry/import", tags=["导入"])


def _auto_classify(name: str, money: float, day: str) -> str:
    """自动分类入口：规则引擎 > 缓存 > DeepSeek > 其他

    使用商户名/备注信息进行分类判断。
    """
    result = classify_transaction(
        merchant=name,
        amount=money,
        date=day[:10] if day else "",
        note=name,
    )
    return result["category"]


@router.post("/alipay")
def import_alipay(
    file: UploadFile = File(...),
    book_id: str = Form(...),
    user_id: int = Depends(get_current_user_id),
    db: Session = Depends(get_db)
):
    """导入支付宝 CSV"""
    if not book_id:
        return error("请先选择账本")

    content = file.file.read().decode('utf-8-sig')
    reader = csv.DictReader(io.StringIO(content))

    # 增量导入：先获取已有记录的去重键 (day, money, name)
    existing = set()
    flows = db.query(Flow).filter(
        Flow.book_id == book_id,
        Flow.origin == f"{user_id}-支付宝导入"
    ).all()
    for f in flows:
        existing.add((f.day, f.money, f.name))

    count = 0
    skipped = 0
    for row in reader:
        trade_time = row.get('交易时间', row.get('交易创建时间', ''))
        trade_type = row.get('类型', '')
        trade_name = row.get('交易名称', row.get('商品名称', row.get('交易对方', '')))
        # 花呗还款排除
        if trade_name == "花呗":
            skipped += 1
            continue
        amount_str = row.get('金额', row.get('金额（元）', '0'))

        try:
            amount = float(amount_str.replace(',', ''))
        except:
            amount = 0

        # 类型转换
        flow_type = "支出"
        if '退款' in trade_type:
            flow_type = "收入"
            amount = abs(amount)
            trade_name = "退款-" + trade_name
        elif '收入' in trade_type or amount > 0:
            flow_type = "收入"
            amount = abs(amount)
        elif amount < 0:
            amount = abs(amount)

        day = trade_time[:10] if trade_time else ""
        key = (day, amount, trade_name)
        if key in existing:
            skipped += 1
            continue

        # 查找类型映射
        type_map = db.query(TypeRelation).filter(
            TypeRelation.book_id == book_id,
            TypeRelation.source == trade_name
        ).first()

        if type_map:
            industry_type = type_map.target
        else:
            # 自动分类
            industry_type = _auto_classify(trade_name, amount, trade_time)

        db_flow = Flow(
            user_id=user_id,
            book_id=book_id,
            day=day,
            flow_type=flow_type,
            industry_type=industry_type,
            money=amount,
            name=trade_name,
            origin=f"{user_id}-支付宝导入"
        )
        db.add(db_flow)
        existing.add(key)
        count += 1

    db.commit()
    return success({"count": count, "skipped": skipped})


@router.post("/qianji")
def import_qianji(
    file: UploadFile = File(...),
    book_id: str = Form(...),
    user_id: int = Depends(get_current_user_id),
    db: Session = Depends(get_db)
):
    """导入钱迹 CSV

    格式: 时间,分类,二级分类,类型,金额,账户1,账户2,备注,账单标记,手续费,优惠券,标签,账单图片

    自动分类：
    - 当 CSV 中的分类为"其他"或空时，根据"备注"字段自动匹配分类
    - 三级策略：规则引擎 -> 缓存 -> DeepSeek API -> 兜底"其他"
    """
    if not book_id:
        return error("请先选择账本")

    content = file.file.read().decode("utf-8-sig")
    result = _import_qianji_csv(content, book_id, user_id, db)
    return success(result)


def _import_qianji_csv(content: str, book_id: str, user_id: int, db: Session) -> dict:
    """钱迹 CSV 入库核心（被 /qianji 和 /rawlog 共用）。返回 {count, skipped, classification}。"""
    reader = csv.DictReader(io.StringIO(content))

    # 增量导入：先获取已有记录的去重
    existing = set()  # (day, money, name) 精确去重
    existing_dm = {}  # (day, money) -> name  (检测同名泛称)
    flows = db.query(Flow).filter(
        Flow.book_id == book_id,
        Flow.origin == f"{user_id}-钱迹导入"
    ).all()
    for f in flows:
        existing.add((f.day, f.money, f.name))
        # 记录最近一条 (day, money) 对应的 name
        existing_dm[(f.day, f.money)] = f.name

    GENERIC_NAMES = {"微信支付", "微信转账", "支付宝", "付款", "转账", "收款", "红包"}

    def _is_generic(name: str) -> bool:
        return name in GENERIC_NAMES or len(name) <= 1

    count = 0
    skipped = 0

    # 分类统计
    method_stats: dict[str, int] = {}
    category_stats: dict[str, int] = {}

    for row in reader:
        time_str = row.get("时间", "").strip()
        if not time_str:
            continue

        # 日期转换: 2026/7/19 12:58:48 -> 2026-07-19 12:58:48 (保留完整时间用于秒级去重)
        m = re.match(r"(\d{4})/(\d{1,2})/(\d{1,2})(?: (\d{1,2}:\d{2}(?::\d{2})?))?", time_str)
        if m:
            day = f"{m.group(1)}-{int(m.group(2)):02d}-{int(m.group(3)):02d}"
            if m.group(4):
                day += " " + m.group(4)
        else:
            day = time_str[:10]

        flow_type = row.get("类型", "支出").strip() or "支出"

        # ── 分类处理 ──
        csv_category = row.get("分类", "").strip()
        sub = row.get("二级分类", "").strip()

        # 如果 CSV 自带有效分类，优先使用
        if sub and sub != "其他":
            industry_type = sub
        elif csv_category and csv_category != "其他":
            industry_type = csv_category
        else:
            # 自动分类：用备注字段匹配
            name_field = row.get("备注", "").strip()
            money_str = row.get("金额", "0").strip()
            try:
                money = float(money_str) if money_str else 0.0
            except ValueError:
                money = 0.0

            industry_type = _auto_classify(name_field, money, day)

        money_str = row.get("金额", "0").strip()
        try:
            money = float(money_str) if money_str else 0.0
        except ValueError:
            money = 0.0

        pay_type = row.get("账户1", "").strip()
        name = row.get("备注", "").strip()
        # 防御: 清理 CDATA/XML 残留
        name = name.replace("]]>", "").replace("<![CDATA[", "").strip()
        # 花呗还款排除: 商户名为"花呗"的记录是信用卡还款，重复记账
        if name == "花呗":
            skipped += 1
            continue

        # 去重检查（两级）
        key = (day, money, name)
        if key in existing:
            skipped += 1
            continue

        # 二级去重：同名 (day, money) 已存在，且当前是泛称 → 跳过
        existing_name = existing_dm.get((day, money))
        if existing_name and _is_generic(name) and not _is_generic(existing_name):
            # 已有具体商户名，跳过这次泛称
            skipped += 1
            continue
        if existing_name and _is_generic(existing_name) and not _is_generic(name):
            # 已有泛称，这次是具体商户名 → 替换（删除旧的，用新的）
            db.query(Flow).filter(
                Flow.book_id == book_id,
                Flow.origin == f"{user_id}-钱迹导入",
                Flow.day == day,
                Flow.money == money,
                Flow.name == existing_name,
            ).delete(synchronize_session=False)
            existing.discard((day, money, existing_name))

        # 更新 existing / existing_dm
        existing.add(key)
        existing_dm[(day, money)] = name

        db_flow = Flow(
            user_id=user_id,
            book_id=book_id,
            day=day,
            flow_type=flow_type,
            industry_type=industry_type,
            pay_type=pay_type or "现金",
            money=money,
            name=name,
            origin=f"{user_id}-钱迹导入"
        )
        db.add(db_flow)
        count += 1

        # 统计
        method_stats["auto_classified"] = method_stats.get("auto_classified", 0) + 1
        category_stats[industry_type] = category_stats.get(industry_type, 0) + 1

    db.commit()
    return {
        "count": count,
        "skipped": skipped,
        "classification": category_stats,
    }


@router.post("/rawlog")
def import_rawlog(
    payload: dict,
    x_module_token: str = Header(default=""),
    db: Session = Depends(get_db)
):
    """模块端增量上传原始 messages.log 行，服务器解析后入库。

    payload: {"lines": ["[MM-dd HH:mm:ss] W|...", ...]}
    鉴权: X-Module-Token header（固定 token，与 password.json module_token 一致）

    设计: 行先累积到服务器 rawlog 文件（按 book_id 分文件），每次上传后
    **全量重解析** —— 转账双记合并/群收款关联需要跨批次的上下文
    （同一笔转账的初始+状态更新消息可能分属两个上传批次）。
    """
    if not MODULE_TOKEN or x_module_token != MODULE_TOKEN:
        raise HTTPException(status_code=401, detail="模块 token 无效")

    book_id = payload.get("book_id") or "1-u3wit23z"
    lines = payload.get("lines") or []
    if not lines:
        return success({"count": 0, "skipped": 0, "classification": {}})

    # 1. 累积: 追加到 rawlog 文件
    data_dir = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(
        os.path.abspath(__file__)))), "data")
    os.makedirs(data_dir, exist_ok=True)
    raw_file = os.path.join(data_dir, f"rawlog_{book_id}.log")
    with open(raw_file, "a", encoding="utf-8") as f:
        for line in lines:
            f.write(line if line.endswith("\n") else line + "\n")

    # 2. 全量重解析（含跨批次上下文）
    with open(raw_file, "r", encoding="utf-8", errors="replace") as f:
        all_lines = f.readlines()

    rows = parse_lines(all_lines)
    if not rows:
        return success({"count": 0, "skipped": 0, "classification": {}})

    # 3. 转成钱迹 CSV 文本 → 复用 qianji 入库管道（去重 + 自动分类）
    csv_text = CSV_HEADER + "\n"
    for r in rows:
        csv_text += ",".join(r) + ",,,,,,\n"

    # user_id: 模块 token 对应 admin (1)
    result = _import_qianji_csv(csv_text, book_id, user_id=1, db=db)
    return success(result)


@router.post("/wechat")
def import_wechat(
    file: UploadFile = File(...),
    book_id: str = Form(...),
    user_id: int = Depends(get_current_user_id),
    db: Session = Depends(get_db)
):
    """导入微信 CSV"""
    if not book_id:
        return error("请先选择账本")

    content = file.file.read().decode('utf-8-sig')
    reader = csv.DictReader(io.StringIO(content))

    # 增量导入：先获取已有记录的去重键 (day, money, name)
    existing = set()
    flows = db.query(Flow).filter(
        Flow.book_id == book_id,
        Flow.origin == f"{user_id}-微信导入"
    ).all()
    for f in flows:
        existing.add((f.day, f.money, f.name))

    count = 0
    skipped = 0
    for row in reader:
        trade_time = row.get('交易时间', '')
        trade_type = row.get('交易类型', '')
        trade_name = row.get('商品', row.get('交易对方', ''))
        # 花呗还款排除
        if trade_name == "花呗":
            skipped += 1
            continue
        amount_str = row.get('金额(元)', row.get('金额', '0'))

        try:
            amount = float(amount_str.replace('¥', '').replace(',', ''))
        except:
            amount = 0

        flow_type = "支出" if '支出' in trade_type else "收入"
        
        # 检测退款
        if '退款' in trade_type:
            flow_type = "收入"
            trade_name = "退款-" + trade_name
            amount = abs(amount)

        day = trade_time[:10] if trade_time else ""
        key = (day, amount, trade_name)
        if key in existing:
            skipped += 1
            continue

        type_map = db.query(TypeRelation).filter(
            TypeRelation.book_id == book_id,
            TypeRelation.source == trade_name
        ).first()

        if type_map:
            industry_type = type_map.target
        else:
            industry_type = _auto_classify(trade_name, amount, trade_time)

        db_flow = Flow(
            user_id=user_id,
            book_id=book_id,
            day=day,
            flow_type=flow_type,
            industry_type=industry_type,
            money=amount,
            name=trade_name,
            origin=f"{user_id}-微信导入"
        )
        db.add(db_flow)
        existing.add(key)
        count += 1

    db.commit()
    return success({"count": count, "skipped": skipped})
