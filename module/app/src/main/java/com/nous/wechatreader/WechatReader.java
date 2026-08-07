package com.nous.wechatreader;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import org.json.JSONObject;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 微信+支付宝双平台消息/账单实时读取 Xposed 模块。
 *
 * 微信: Hook SQLiteDatabase.insert/update, 拦截 message 表
 * 支付宝: Hook SQLiteDatabase.insert, 拦截 service_message 表(支付助手)
 *
 * 输出格式:
 *   W|<ts_ms>|<dir>|<type>|<content>
 *   A|<ts_ms>|<dir>|<amount>|<merchant>|<method>
 */
public class WechatReader implements IXposedHookLoadPackage {

    private static final String TAG = "WechatReader";
    private static String sApp = null;
    private static boolean sActive = false;
    private static Context sContext = null;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        String pkg = lpparam.packageName;

        if ("com.tencent.mm".equals(pkg)) {
            if (!"com.tencent.mm".equals(lpparam.processName)) return;
            sApp = "wechat";
        } else if ("com.eg.android.AlipayGphone".equals(pkg)) {
            // 支付宝多进程（push/tools等），全钩
            sApp = "alipay";
        } else {
            return;
        }

        Log.i(TAG, "模块加载: " + sApp + " / " + lpparam.processName);
        hookSQLite(lpparam.classLoader);
        if ("wechat".equals(sApp)) {
            triggerInitialExport(lpparam);
        }
        Log.i(TAG, "SQLite hook 注册完成");
    }

    /** 模块首次加载时触发一次导出，让用户立即看到成果 */
    private void triggerInitialExport(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    "android.app.Application",
                    lpparam.classLoader,
                    "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            android.content.Context ctx = (android.content.Context) param.thisObject;
                            sContext = ctx.getApplicationContext();
                            DailyExportReceiver.triggerExportNow(ctx);
                            Log.i(TAG, "已触发即时导出");
                        }
                    });
        } catch (Throwable e) {
            Log.w(TAG, "触发导出失败: " + e.getMessage());
        }
    }

    // ── 统一的 SQLite hook（Android + WCDB 双路径）───────

    private void hookSQLite(ClassLoader wechatCL) {
        // 1. 安卓原生 SQLiteDatabase（兼容旧版微信）
        Class<?> androidDB = SQLiteDatabase.class;
        hookSQLiteClass(androidDB, "android");

        // 2. WCDB SQLiteDatabase（微信主力路径）
        try {
            Class<?> wcdbDB = XposedHelpers.findClassIfExists(
                    "com.tencent.wcdb.database.SQLiteDatabase",
                    wechatCL);
            if (wcdbDB != null) {
                hookSQLiteClass(wcdbDB, "wcdb");
            } else {
                Log.w(TAG, "未找到 WCDB SQLiteDatabase，仅使用 Android 原生钩子");
            }
        } catch (Throwable e) {
            Log.w(TAG, "未找到 WCDB SQLiteDatabase: " + e.getMessage());
        }
    }

    private void hookSQLiteClass(Class<?> dbClass, String label) {
        try {
            XposedHelpers.findAndHookMethod(dbClass,
                    "insert",
                    String.class, String.class, ContentValues.class,
                    new InsertHook());
            XposedHelpers.findAndHookMethod(dbClass,
                    "insertWithOnConflict",
                    String.class, String.class, ContentValues.class, int.class,
                    new InsertHook());
            XposedHelpers.findAndHookMethod(dbClass,
                    "insertOrThrow",
                    String.class, String.class, ContentValues.class,
                    new InsertHook());
            XposedHelpers.findAndHookMethod(dbClass,
                    "replace",
                    String.class, String.class, ContentValues.class,
                    new InsertHook());
            XposedHelpers.findAndHookMethod(dbClass,
                    "replaceOrThrow",
                    String.class, String.class, ContentValues.class,
                    new InsertHook());

            if ("wechat".equals(sApp)) {
                XposedHelpers.findAndHookMethod(dbClass,
                        "update",
                        String.class, ContentValues.class,
                        String.class, String[].class,
                        new UpdateHook());
                XposedHelpers.findAndHookMethod(dbClass,
                        "updateWithOnConflict",
                        String.class, ContentValues.class,
                        String.class, String[].class, int.class,
                        new UpdateHook());
            }

            XposedHelpers.findAndHookMethod(dbClass,
                    "execSQL", String.class,
                    new ExecSqlHook());
            XposedHelpers.findAndHookMethod(dbClass,
                    "execSQL", String.class, Object[].class,
                    new ExecSqlHook());

            Log.i(TAG, "[SQLite] hooks OK (" + sApp + "/" + label + ")");
        } catch (Throwable e) {
            Log.e(TAG, "[SQLite] hook 失败 (" + label + ")", e);
        }
    }

    // ── Insert ────────────────────────────────────────────

    static class InsertHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                String table = (String) param.args[0];
                ContentValues cv = (ContentValues) param.args[2];
                if (cv == null) return;

                String line = null;

                if ("wechat".equals(sApp) && "message".equals(table)) {
                    line = formatWechat(cv);
                    if (line == null) {
                        Integer type = cv.getAsInteger("type");
                        String content = cv.getAsString("content");
                        byte[] blob = cv.getAsByteArray("content");
                        Log.d(TAG, "跳过 message insert type=" + type
                                + " contentStr=" + (content != null ? content.substring(0, Math.min(50, content.length())) : "null")
                                + " blobLen=" + (blob != null ? blob.length : 0));
                    }
                } else if ("alipay".equals(sApp) && "service_message".equals(table)) {
                    line = formatAlipay(cv);
                    if (line == null) {
                        String title = cv.getAsString("title");
                        String content = cv.getAsString("content");
                        Log.d(TAG, "跳过 service_message title=" + title
                                + " content=" + (content != null ? content.substring(0, Math.min(80, content.length())) : "null"));
                    }
                }

                if (line != null) {
                    markActive();
                    long ts = System.currentTimeMillis();
                    MessageWriter.write(line, ts);
                    // 捕获到微信支付消息时立即触发导出
                    if ("wechat".equals(sApp) && sContext != null
                            && (line.contains("|红包|") || line.contains("|转账|")
                              || line.contains("other:318767153"))) {
                        new Thread(() -> {
                            try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
                            DailyExportReceiver.triggerExportNow(sContext);
                        }).start();
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    // ── Update (仅微信红包/转账) ──────────────────────────

    static class UpdateHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                String table = (String) param.args[0];
                if (!"message".equals(table)) return;

                ContentValues cv = (ContentValues) param.args[1];
                if (cv == null) return;

                Integer type = cv.getAsInteger("type");
                if (type == null) return;
                if (type != 436207665 && type != 419430449 && type != 268435505)
                    return;

                String f = formatWechat(cv);
                if (f != null) {
                    markActive();
                    long ts = System.currentTimeMillis();
                    MessageWriter.write("U|" + f, ts);
                }
            } catch (Throwable ignored) {}
        }
    }

    // ── execSQL (WCDB 底层入口) ────────────────────────────

    static class ExecSqlHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                String sql = (String) param.args[0];
                if (sql == null) return;
                String upper = sql.toUpperCase().trim();

                // 关注 message 表（微信）和 service_message 表（支付宝）
                boolean isMsg = (upper.contains("MESSAGE")
                        || upper.contains("SERVICE_MESSAGE"))
                        && (upper.startsWith("INSERT")
                         || upper.startsWith("UPDATE")
                         || upper.startsWith("REPLACE"));

                if (isMsg) {
                    Object[] bindArgs = (param.args.length > 1)
                            ? (Object[]) param.args[1] : null;
                    int bindCount = (bindArgs != null) ? bindArgs.length : 0;
                    Log.i(TAG, "execSQL → message: " + sql.substring(0, Math.min(200, sql.length()))
                            + (bindCount > 0 ? " [args:" + bindCount + "]" : ""));
                }
            } catch (Throwable ignored) {}
        }
    }

    // ── 微信消息格式化 → W|<dir>|<type>|<content> ─────────

    static String formatWechat(ContentValues cv) {
        Integer type   = cv.getAsInteger("type");
        Integer isSend = cv.getAsInteger("isSend");

        if (type == null) return null;

        String content = cv.getAsString("content");
        // 兜底: 红包等富媒体消息 content 可能是 BLOB
        if (content == null) {
            byte[] blob = cv.getAsByteArray("content");
            if (blob != null) {
                try { content = new String(blob, "UTF-8"); }
                catch (Exception e) { content = null; }
            }
        }

        // content 可能是 XML（红包等），也检查是否包含微信红包关键词
        if (content == null && type == 436207665) {
            content = "微信红包";
        }

        if (content == null) return null;

        String dir = (isSend != null && isSend == 1) ? "发出" : "收到";
        String typeStr = wechatType(type);
        String talker = cv.getAsString("talker");
        if (talker == null) talker = "";
        String body = sanitize(content);

        if (body.isEmpty()) return null;
        return "W|" + dir + "|" + typeStr + "|" + talker + "|" + body;
    }

    // ── 支付宝消息格式化 → A|<dir>|<amount>|<merchant>|<method> ─

    static String formatAlipay(ContentValues cv) {
        String title   = cv.getAsString("title");
        String content = cv.getAsString("content");

        if (!"支付助手".equals(title) || content == null) return null;

        try {
            JSONObject json = new JSONObject(content);

            // 只处理支付消息
            if (!json.optBoolean("isPaymentMsg", false)) return null;

            String topType = json.optString("topSubContent", "");
            String amount  = json.optString("content", "");
            String method  = json.optString("assistMsg1", "");
            String merchant = "";

            JSONObject scene = json.optJSONObject("sceneExt2");
            if (scene != null) {
                merchant = scene.optString("sceneName", "");
            }

            if (amount.isEmpty()) return null;

            // 判断收支方向
            String dir;
            if ("付款成功".equals(topType) || topType.contains("扣款")) {
                dir = "支出";
            } else {
                dir = "收入";
            }

            return "A|" + dir + "|" + amount + "|"
                    + sanitize(merchant) + "|" + sanitize(method);
        } catch (Exception e) {
            return null;
        }
    }

    // ── 工具方法 ──────────────────────────────────────────

    /** 去掉换行和管道符，防止破坏输出格式 */
    static String sanitize(String s) {
        if (s == null) return "";
        return s.replace('\n', ' ').replace('\r', ' ')
                .replace('|', '/').trim();
    }

    static String wechatType(int type) {
        switch (type) {
            case 1:           return "文本";
            case 3:           return "图片";
            case 34:          return "语音";
            case 43:          return "视频";
            case 47:          return "表情";
            case 49:          return "链接";
            case 436207665:   return "红包";
            case 419430449:   return "转账";
            case 268435505:   return "红包记录";
            case 10000:       return "系统";
            case -1879048186: return "群公告";
            default:          return "other:" + type;
        }
    }

    static void markActive() {
        if (!sActive) {
            sActive = true;
            Log.i(TAG, "✓ " + sApp + " 捕获到消息！");
        }
    }
}
