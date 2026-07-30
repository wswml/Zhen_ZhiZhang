"""流水路由 - 像素级复刻 Cashbook 流水 API"""
from fastapi import APIRouter, Depends, UploadFile, File
from sqlalchemy.orm import Session
from sqlalchemy import func
from typing import Optional, List

from app.models.database import get_db
from app.models.models import Flow, BookMember
from app.models.schemas import FlowCreate
from app.utils.auth import get_current_user_id
from app.utils.common import success, error

router = APIRouter(prefix="/api/entry/flow", tags=["流水"])


@router.get("/all")
def get_all_flows(
    bookId: Optional[str] = None,      # camelCase - 前端 JS 发的参数
    book_id: Optional[str] = None,       # snake_case - 兼容
    month: Optional[str] = None,         # 按月份筛选（如 "2026-07"）
    user_id: int = Depends(get_current_user_id),
    db: Session = Depends(get_db)
):
    """获取账本流水记录，支持月份筛选"""
    # 兼容两种参数名
    target_book_id = bookId or book_id

    if not target_book_id:
        return error("请先选择账本")

    query = db.query(Flow).filter(Flow.book_id == target_book_id)

    if month:
        query = query.filter(Flow.day.like(f"{month}%"))

    flows = query.order_by(Flow.day.desc(), Flow.id.desc()).all()
    return success(flows)


@router.post("/add")
def add_flow(flow: FlowCreate, user_id: int = Depends(get_current_user_id), db: Session = Depends(get_db)):
    """添加流水"""
    # 检查权限
    has_access = db.query(BookMember).filter(
        BookMember.book_id == flow.book_id,
        BookMember.user_id == user_id
    ).first()

    if not has_access:
        return error("无权限")

    db_flow = Flow(
        user_id=user_id,
        book_id=flow.book_id,
        day=flow.day,
        flow_type=flow.flow_type or "支出",
        industry_type=flow.industry_type,
        pay_type=flow.pay_type,
        money=flow.money or 0,
        name=flow.name,
        description=flow.description,
        attribution=flow.attribution
    )
    db.add(db_flow)
    db.commit()
    db.refresh(db_flow)

    return success(db_flow)


@router.delete("/{flow_id}")
def delete_flow(flow_id: int, user_id: int = Depends(get_current_user_id), db: Session = Depends(get_db)):
    """删除流水"""
    flow = db.query(Flow).filter(Flow.id == flow_id).first()
    if not flow:
        return error("流水不存在")

    # 检查权限：记账人或账本 owner 可删
    from app.models.models import Book
    book = db.query(Book).filter(Book.book_id == flow.book_id).first()

    if flow.user_id != user_id and book.user_id != user_id:
        return error("无权限删除")

    db.delete(flow)
    db.commit()

    return success()


@router.get("/statistics")
def get_statistics(
    bookId: Optional[str] = None,      # camelCase - 前端 JS 发的参数
    book_id: Optional[str] = None,       # snake_case - 兼容
    month: Optional[str] = None,
    user_id: int = Depends(get_current_user_id),
    db: Session = Depends(get_db)
):
    """获取统计数据"""
    # 兼容两种参数名
    target_book_id = bookId or book_id

    if not target_book_id:
        return error("请先选择账本")

    query = db.query(Flow).filter(Flow.book_id == target_book_id)

    if month:
        query = query.filter(Flow.day.like(f"{month}%"))

    flows = query.all()

    # 统计计算
    total_income = sum(f.money or 0 for f in flows if f.flow_type == "收入")
    total_expense = sum(f.money or 0 for f in flows if f.flow_type == "支出")

    # 按类型统计
    type_stats = {}
    for f in flows:
        key = f.industry_type or "未分类"
        if f.flow_type not in type_stats:
            type_stats[f.flow_type] = {}
        if key not in type_stats[f.flow_type]:
            type_stats[f.flow_type][key] = 0
        type_stats[f.flow_type][key] += f.money or 0

    # 按日统计
    day_stats = {}
    for f in flows:
        if f.day not in day_stats:
            day_stats[f.day] = {"income": 0, "expense": 0}
        if f.flow_type == "收入":
            day_stats[f.day]["income"] += f.money or 0
        elif f.flow_type == "支出":
            day_stats[f.day]["expense"] += f.money or 0

    return success({
        "total_income": total_income,
        "total_expense": total_expense,
        "balance": total_income - total_expense,
        "type_stats": type_stats,
        "day_stats": day_stats,
        "count": len(flows)
    })
