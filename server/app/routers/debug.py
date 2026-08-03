"""调试页面 - 直接显示流水数据，不依赖前端 JS（需登录，支持 ?token= 或 Authorization header）"""
from fastapi import APIRouter, Request, Depends, HTTPException
from fastapi.responses import HTMLResponse, RedirectResponse
from jose import JWTError, jwt
import os

from app.utils.auth import SECRET_KEY, ALGORITHM

router = APIRouter(tags=["调试"])


def _resolve_user_id(request: Request) -> int | None:
    """从 Authorization header 或 ?token= query 参数解析用户ID，失败返回 None"""
    token = None
    auth = request.headers.get("Authorization", "")
    if auth.startswith("Bearer "):
        token = auth.split(" ")[1]
    if not token:
        token = request.query_params.get("token")
    if not token:
        return None
    try:
        payload = jwt.decode(token, SECRET_KEY, algorithms=[ALGORITHM])
        return int(payload.get("sub"))
    except (JWTError, TypeError, ValueError):
        return None


@router.get("/debug/{book_id}", response_class=HTMLResponse)
def debug_book(request: Request, book_id: str):
    """直接显示流水数据 - 仅登录用户可访问"""
    if _resolve_user_id(request) is None:
        return RedirectResponse(url="/login")

    from app.models.database import SessionLocal
    from app.models.models import Flow, Book, BookMember
    db = SessionLocal()
    try:
        book = db.query(Book).filter(Book.book_id == book_id).first()
        if not book:
            return "<h2>账本不存在</h2>"
        flows = db.query(Flow).filter(Flow.book_id == book_id).order_by(Flow.day.desc()).limit(50).all()
    finally:
        db.close()

    rows = ""
    for f in flows:
        rows += f"<tr><td>{f.day}</td><td>{f.flow_type}</td><td>{f.industry_type}</td><td>¥{f.money:.2f}</td><td>{f.name or ''}</td></tr>"

    total = sum(f.money or 0 for f in flows)

    html = f"""<html><head><meta charset="utf-8">
    <title>Debug - {book.book_name}</title>
    <style>
    body {{ font-family: sans-serif; padding: 20px; background: #0f172a; color: #f1f5f9; }}
    table {{ border-collapse: collapse; width: 100%; }}
    th, td {{ padding: 8px 12px; text-align: left; border-bottom: 1px solid #334155; }}
    .total {{ font-size: 24px; margin: 20px 0; color: #34d399; }}
    </style></head><body>
    <h1>{book.book_name}</h1>
    <div class="total">共 {len(flows)} 条流水，合计 ¥{total:.2f}</div>
    <table><tr><th>日期</th><th>类型</th><th>分类</th><th>金额</th><th>名称</th></tr>
    {rows}</table></body></html>"""
    return html
