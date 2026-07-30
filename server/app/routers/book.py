"""账本路由 - 像素级复刻 Cashbook 账本 API"""
from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session
from typing import Optional
from datetime import datetime, timedelta

from app.models.database import get_db
from app.models.models import Book, BookMember, TypeRelation, Flow, Budget
from app.models.schemas import BookCreate, ShareKeyCreate, ShareKeyJoin, MemberRemove
from app.utils.auth import get_current_user, get_current_user_id
from app.utils.common import success, error, generate_book_id, generate_share_key

router = APIRouter(prefix="/api/entry/book", tags=["账本"])


@router.get("/all")
def get_all_books(user_id: int = Depends(get_current_user_id), db: Session = Depends(get_db)):
    """获取用户所有账本"""
    books = db.query(Book).filter(Book.user_id == user_id).all()
    return success(books)


@router.post("/add")
def add_book(book: BookCreate, user_id: int = Depends(get_current_user_id), db: Session = Depends(get_db)):
    """添加账本"""
    book_id = generate_book_id(user_id)

    db_book = Book(
        book_id=book_id,
        user_id=user_id,
        book_name=book.book_name,
        budget=book.budget or 0
    )
    db.add(db_book)
    db.commit()
    db.refresh(db_book)

    # owner 自动加入成员表
    db_member = BookMember(
        book_id=book_id,
        user_id=user_id,
        role='owner'
    )
    db.add(db_member)

    # 初始化默认类型映射
    default_types = db.query(TypeRelation).filter(
        TypeRelation.book_id == "0",
        TypeRelation.user_id == 0
    ).all()

    for t in default_types:
        db_type = TypeRelation(
            book_id=book_id,
            user_id=user_id,
            source=t.source,
            target=t.target
        )
        db.add(db_type)

    db.commit()

    return success({
        "id": db_book.id,
        "book_id": db_book.book_id,
        "book_name": db_book.book_name,
        "share_key": db_book.share_key,
        "user_id": db_book.user_id,
        "budget": db_book.budget,
        "create_date": db_book.create_date.isoformat() if db_book.create_date else None
    })


@router.post("/share")
def create_share_key(data: ShareKeyCreate, user_id: int = Depends(get_current_user_id), db: Session = Depends(get_db)):
    """生成账本分享密钥（7天有效）"""
    book = db.query(Book).filter(Book.id == data.id, Book.user_id == user_id).first()
    if not book:
        return error("Not Find ID")

    share_key = generate_share_key(user_id, book.id)
    book.share_key = share_key
    book.share_key_expires = datetime.utcnow() + timedelta(days=7)  # 7天过期
    db.commit()
    db.refresh(book)

    return success(book)


@router.post("/inshare")
def join_by_share_key(data: ShareKeyJoin, user_id: int = Depends(get_current_user_id), db: Session = Depends(get_db)):
    """通过分享密钥加入账本"""
    if not data.key:
        return error("Not Find key")

    books = db.query(Book).filter(Book.share_key == data.key).all()

    if len(books) > 0:
        book = books[0]

        # 检查 shareKey 是否过期
        if book.share_key_expires and book.share_key_expires < datetime.utcnow():
            return error("邀请链接已过期")

        # 检查是否已加入
        existing = [b for b in books if b.user_id == user_id]
        if existing:
            return error("账本已存在")

        # 复制账本记录
        new_book = Book(
            user_id=user_id,
            book_id=book.book_id,
            book_name=book.book_name,
            create_date=book.create_date,
            share_key=book.share_key
        )
        db.add(new_book)
        db.commit()
        db.refresh(new_book)

        # 写入成员表
        db_member = BookMember(
            book_id=book.book_id,
            user_id=user_id,
            role='member'
        )
        db.add(db_member)
        db.commit()

        return success()
    else:
        return error("无效Key！")


@router.post("/members")
def get_members(data: dict, user_id: int = Depends(get_current_user_id), db: Session = Depends(get_db)):
    """获取账本成员列表 - 读 POST body"""
    book_id = data.get("book_id") or data.get("bookId")

    if not book_id:
        return error("缺少账本ID")

    # 检查权限
    my_member = db.query(BookMember).filter(
        BookMember.book_id == book_id,
        BookMember.user_id == user_id
    ).first()

    if not my_member:
        return error("无权限查看")

    members = db.query(BookMember).filter(BookMember.book_id == book_id).all()

    from app.models.models import User
    result = []
    for m in members:
        user = db.query(User).filter(User.id == m.user_id).first()
        result.append({
            "user_id": m.user_id,
            "role": m.role,
            "joined_at": m.joined_at,
            "name": user.name or user.username if user else "未知用户"
        })

    return success(result)


@router.post("/member-remove")
def remove_member(data: MemberRemove, user_id: int = Depends(get_current_user_id), db: Session = Depends(get_db)):
    """移除账本成员（owner 可调用）"""
    # 检查当前用户是否是 owner
    my_member = db.query(BookMember).filter(
        BookMember.book_id == data.book_id,
        BookMember.user_id == user_id
    ).first()

    if not my_member or my_member.role != 'owner':
        return error("只有账本所有者可以移除成员")

    if data.target_user_id == user_id:
        return error("不能移除自己")

    # 从成员表移除
    db.query(BookMember).filter(
        BookMember.book_id == data.book_id,
        BookMember.user_id == data.target_user_id
    ).delete()

    # 删除该用户的 Book 记录
    db.query(Book).filter(
        Book.book_id == data.book_id,
        Book.user_id == data.target_user_id
    ).delete()

    db.commit()

    return success()


@router.post("/delete")
def delete_books(data: dict, user_id: int = Depends(get_current_user_id), db: Session = Depends(get_db)):
    """批量删除账本（仅 owner）"""
    book_ids = data.get("book_ids", []) or data.get("bookIds", [])
    if not book_ids:
        return error("请选择要删除的账本")
    deleted = 0
    for bid in book_ids:
        book = db.query(Book).filter(Book.book_id == bid, Book.user_id == user_id).first()
        if not book:
            continue
        member = db.query(BookMember).filter(
            BookMember.book_id == bid, BookMember.user_id == user_id
        ).first()
        if not member or member.role != 'owner':
            continue
        # 删除关联流水
        db.query(Flow).filter(Flow.book_id == bid).delete(synchronize_session=False)
        # 删除预算
        db.query(Budget).filter(Budget.book_id == bid).delete(synchronize_session=False)
        # 删除成员
        db.query(BookMember).filter(BookMember.book_id == bid).delete(synchronize_session=False)
        # 删除账本
        db.delete(book)
        deleted += 1
    db.commit()
    return success({"deleted": deleted})


@router.post("/leave")
def leave_book(data: dict, user_id: int = Depends(get_current_user_id), db: Session = Depends(get_db)):
    """成员主动退出账本"""
    book_id = data.get("book_id") or data.get("bookId")

    if not book_id:
        return error("缺少账本ID")

    # 检查是否是成员
    member = db.query(BookMember).filter(
        BookMember.book_id == book_id,
        BookMember.user_id == user_id
    ).first()

    if not member:
        return error("不在该账本中")

    # owner 不能退出
    if member.role == 'owner':
        return error("所有者不能退出，请删除账本")

    # 删除成员记录
    db.delete(member)

    # 删除 Book 记录
    db.query(Book).filter(
        Book.book_id == book_id,
        Book.user_id == user_id
    ).delete()

    db.commit()

    return success()
