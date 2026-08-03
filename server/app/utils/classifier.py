"""分类调度器 - 统一分类入口
===============================
整合规则引擎、缓存、DeepSeek API，提供三级分类策略：

1. 规则引擎匹配 -> 零成本，立即返回
2. 本地缓存查询 -> 零成本，立即返回
3. DeepSeek API 调用 -> 有成本，结果入缓存

用法:
    from app.utils.classifier import classify_transaction
    result = classify_transaction("瑞幸咖啡", 15.0, "2026-07-21", "生椰拿铁")
    # -> {"category": "餐饮", "method": "rule", "cost": 0}
"""
import logging
from app.utils.rule_engine import rule_classify
from app.utils.cache_manager import ClassificationCache
from app.utils.deepseek_classifier import deepseek_classify, is_available as deepseek_available

logger = logging.getLogger(__name__)

# 全局缓存实例（延迟初始化）
_cache: ClassificationCache | None = None


def _get_cache() -> ClassificationCache:
    global _cache
    if _cache is None:
        import os
        cache_file = os.getenv("CLASSIFICATION_CACHE_FILE", "classification_cache.json")
        _cache = ClassificationCache(cache_file=cache_file)
    return _cache


def classify_transaction(
    merchant: str,
    amount: float = 0.0,
    date: str = "",
    note: str = "",
) -> dict:
    """统一分类入口

    优先级: 规则引擎 > 本地缓存 > DeepSeek API > 其他(兜底)

    Args:
        merchant: 商户名/交易对方（必填）
        amount: 交易金额（辅助判断，用于 DeepSeek）
        date: 交易日期 YYYY-MM-DD（辅助判断，用于 DeepSeek）
        note: 备注/商品名（可选）

    Returns:
        {
            "category": str,   # 分类名称，如 "餐饮"
            "method": str,     # 分类方式: "rule" / "cache" / "deepseek" / "fallback"
            "cost": int        # 本次是否消耗了 API 调用 (0/1)
        }
    """
    merchant = (merchant or "").strip()
    note = (note or "").strip()

    # 空商户名直接兜底，不调 DeepSeek（避免无效 API 调用）
    if not merchant:
        return {"category": "其他", "method": "fallback", "cost": 0}

    # 1. 规则引擎
    cat = rule_classify(merchant, note)
    if cat:
        return {"category": cat, "method": "rule", "cost": 0}

    # 2. 本地缓存
    cache = _get_cache()
    cat = cache.get(merchant, note)
    if cat:
        return {"category": cat, "method": "cache", "cost": 0}

    # 3. DeepSeek API（兜底）
    if deepseek_available():
        cat = deepseek_classify(merchant, amount, date, note)
        if cat and cat != "其他":
            cache.set(merchant, cat, note)
            return {"category": cat, "method": "deepseek", "cost": 1}

        if cat == "其他":
            # 也缓存"其他"结果，避免重复调用
            cache.set(merchant, "其他", note)
            return {"category": "其他", "method": "deepseek", "cost": 1}

    # 4. 最终兜底（API 不可用或未配置）
    return {"category": "其他", "method": "fallback", "cost": 0}
