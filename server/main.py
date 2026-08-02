"""Cashbook Python - 像素级复刻
FastAPI + SQLAlchemy + MySQL + Jinja2
"""
from fastapi import FastAPI, Request
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse
import os
from dotenv import load_dotenv

# load .env for manual runs
load_dotenv(os.path.join(os.path.dirname(__file__), ".env"))


from app.models.database import engine, Base
from app.routers import auth, book, flow, budget, import_data, debug

# 创建数据库表
Base.metadata.create_all(bind=engine)

app = FastAPI(
    title="Cashbook Python",
    description="像素级复刻 Cashbook 记账本",
    version="1.0.0"
)

# CORS
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# 静态文件
app.mount("/static", StaticFiles(directory="app/static"), name="static")

# 模板
templates = Jinja2Templates(directory="app/templates")


# ===== 自动版本号 (asset fingerprint) =====
# 根据静态文件内容 hash 自动生成 ?v= 版本号。
# 改 JS/CSS 后无需手动 bump cache-buster, 浏览器自动拉新文件。
import hashlib

_asset_cache = {}


def asset_ver(path: str) -> str:
    """返回静态文件内容的短 hash, 用作 ?v= 版本号. 文件变了版本号自动变."""
    abs_path = os.path.join(os.path.dirname(__file__), "app/static", path)
    try:
        mtime = os.path.getmtime(abs_path)
        content = open(abs_path, "rb").read()
    except OSError:
        return "0"
    cached = _asset_cache.get(path)
    if cached and cached[0] == mtime:
        return cached[1]
    h = hashlib.md5(content).hexdigest()[:8]
    _asset_cache[path] = (mtime, h)
    return h


templates.env.globals["asset_ver"] = asset_ver

# 路由
app.include_router(auth.router)
app.include_router(book.router)
app.include_router(flow.router)
app.include_router(budget.router)
app.include_router(import_data.router)
app.include_router(debug.router)


@app.get("/", response_class=HTMLResponse)
async def index(request: Request):
    """首页"""
    return templates.TemplateResponse("index.html", {"request": request})


@app.get("/login", response_class=HTMLResponse)
async def login_page(request: Request):
    """登录页"""
    return templates.TemplateResponse("login.html", {"request": request})


@app.get("/register", response_class=HTMLResponse)
async def register_page(request: Request):
    """注册页"""
    return templates.TemplateResponse("register.html", {"request": request})


@app.get("/book/{book_id}", response_class=HTMLResponse)
async def book_page(request: Request, book_id: str):
    """账本详情页 - 校验账本存在"""
    from app.models.database import SessionLocal
    from app.models.models import Book
    db = SessionLocal()
    exists = db.query(Book).filter(Book.book_id == book_id).first()
    db.close()
    if not exists:
        from fastapi.responses import RedirectResponse
        return RedirectResponse(url="/")
    return templates.TemplateResponse("book.html", {
        "request": request,
        "book_id": book_id
    })


# 全局响应处理 - 添加 Token 刷新头
@app.middleware("http")
async def add_refresh_token_header(request: Request, call_next):
    response = await call_next(request)

    # 如果请求中有 Authorization header，检查是否需要刷新
    auth_header = request.headers.get("Authorization", "")
    if auth_header.startswith("Bearer "):
        token = auth_header.split(" ")[1]
        try:
            from jose import jwt
            from datetime import datetime, timedelta
            import os
            from app.utils.auth import create_access_token

            SECRET_KEY = os.getenv("SECRET_KEY", "your-secret-key")
            ALGORITHM = os.getenv("ALGORITHM", "HS256")

            payload = jwt.decode(token, SECRET_KEY, algorithms=[ALGORITHM])
            exp = payload.get("exp")
            user_id = payload.get("sub")

            if exp and user_id:
                exp_date = datetime.fromtimestamp(exp)
                if exp_date - datetime.utcnow() < timedelta(days=1):
                    # Token 即将过期，生成新 token
                    new_token = create_access_token(data={"sub": user_id})
                    response.headers["X-Refresh-Token"] = new_token
        except:
            pass

    return response


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=9090)
