package com.nous.wechatreader;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONObject;

/**
 * 每日账单导出 + 通知。
 * 触发: BOOT_COMPLETED / AlarmManager 闹钟
 */
public class DailyExportReceiver extends BroadcastReceiver {

    private static final String TAG = "WechatReader-Export";
    private static final String CHANNEL_ID = "wechatreader_export";
    private static final String ALARM_ACTION = "com.nous.wechatreader.EXPORT_ALARM";

    // 日志和输出路径
    private static final String LOG_FILE =
            "/storage/emulated/0/Download/WechatReader/messages.log";
    private static final String OUT_FILE =
            "/storage/emulated/0/Download/qianji_import.csv";

    // 支付宝轮询
    private static final String ALIPAY_DB =
            "/data/data/com.eg.android.AlipayGphone/databases/messagebox.db";
    private static final String ALIPAY_TMP =
            "/data/local/tmp/alipay_export.db";
    private static final String ALIPAY_LAST_ID_FILE =
            "/storage/emulated/0/Download/WechatReader/.alipay_last_id";

    // 日志行正则: [MM-dd HH:mm:ss] <data>
    private static final Pattern LINE_RE =
            Pattern.compile("\\[(\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})\\] (.+)");

    // Alipay:  A|支出|20.00|商户|方法
    // WeChat:  W|收到|类型|talker|body
    // Update:  U|W|方向|类型|talker|body（红包开奖后金额更新）
    private static final Pattern ALIPAY_RE =
            Pattern.compile("A\\|(支出|收入)\\|([\\d.]+)\\|([^|]*)\\|([^|]*)");
    private static final Pattern WECHAT_RE =
            Pattern.compile("W\\|(收到|发出)\\|([^|]+)\\|(?:[^|]*\\|)?(.*)");
    private static final Pattern UPDATE_RE =
            Pattern.compile("U\\|W\\|(收到|发出)\\|([^|]+)\\|[^|]*\\|(.*)");
    private static final Pattern AMOUNT_RE =
            Pattern.compile("[¥￥]\\s*([\\d,]+\\.?\\d*)");

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.i(TAG, "receiver: " + action);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "com.nous.wechatreader.SCHEDULE_ALARM".equals(action)) {
            scheduleDailyAlarm(context);
            return;
        }

        if (ALARM_ACTION.equals(action)) {
            createChannel(context);
            final PendingResult pending = goAsync();
            new Thread(() -> {
                try {
                    doExport(context);
                } finally {
                    // 导出完成后重新调度闹钟（使用模块自身 Context，不依赖微信进程）
                    scheduleDailyAlarm(context);
                    pending.finish();
                }
            }).start();
        }
    }

    // ── 闹钟调度 ──────────────────────────────────────

    public static void scheduleDailyAlarm(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Intent intent = new Intent(context, DailyExportReceiver.class);
        intent.setAction(ALARM_ACTION);
        PendingIntent pi = PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // 每小时触发
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.add(Calendar.HOUR_OF_DAY, 1);
        if (cal.before(Calendar.getInstance())) {
            cal.add(Calendar.HOUR_OF_DAY, 1);
        }

        am.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                cal.getTimeInMillis(),
                AlarmManager.INTERVAL_HOUR,
                pi);

        Log.i(TAG, "闹钟已设置: 每整点");
    }

    /** 由 WechatReader 模块加载时调用，直接执行导出（不走广播） */
    public static void triggerExportNow(Context context) {
        new Thread(() -> {
            try {
                DailyExportReceiver receiver = new DailyExportReceiver();
                receiver.createChannel(context);
                receiver.doExport(context);
            } catch (Exception e) {
                Log.e(TAG, "即时导出失败: " + e.getMessage());
            }
        }).start();
    }

    // ── 支付宝轮询（通过 su 复制 DB → Java SQLite 读取）────

    private void pollAlipay() {
        try {
            // 1. su 复制数据库到可读位置（通过 shell 查找 su）
            ProcessBuilder pb = new ProcessBuilder(
                    "/system/bin/sh", "-c",
                    "su -c 'cp " + ALIPAY_DB + " " + ALIPAY_TMP
                    + " && chmod 644 " + ALIPAY_TMP + "'"
            );
            Process p = pb.start();
            p.waitFor();
            if (p.exitValue() != 0) {
                Log.w(TAG, "支付宝 DB 复制失败");
                return;
            }

            // 2. 读取上次轮询 id
            String lastId = "";
            File idFile = new File(ALIPAY_LAST_ID_FILE);
            if (idFile.exists()) {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(new FileInputStream(idFile)))) {
                    lastId = br.readLine();
                }
            }

            // 3. 查询新记录
            SQLiteDatabase db = SQLiteDatabase.openDatabase(
                    ALIPAY_TMP, null, SQLiteDatabase.OPEN_READONLY);
            Cursor c;
            if (lastId == null || lastId.isEmpty()) {
                // 首次运行：拿最后一条的 id 做标记，不追加
                c = db.rawQuery(
                        "SELECT id FROM service_message WHERE title='支付助手' ORDER BY id DESC LIMIT 1",
                        null);
                if (c.moveToFirst()) {
                    lastId = c.getString(0);
                    writeLastId(lastId);
                }
                c.close();
                db.close();
                Log.i(TAG, "支付宝轮询初始化，lastId=" + lastId);
                return;
            } else {
                c = db.rawQuery(
                        "SELECT id, gmtCreate, content FROM service_message"
                        + " WHERE title='支付助手' AND id > ? ORDER BY id ASC",
                        new String[]{lastId});
            }

            // 4. 解析 + 追加到 messages.log
            StringBuilder sb = new StringBuilder();
            SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault());

            while (c.moveToNext()) {
                String msgId = c.getString(0);
                long gmtCreate = c.getLong(1);
                String content = c.getString(2);

                try {
                    JSONObject json = new JSONObject(content);
                    if (!json.optBoolean("isPaymentMsg", false)) continue;

                    String topType = json.optString("topSubContent", "");
                    String amount  = json.optString("content", "");
                    String method  = json.optString("assistMsg1", "");
                    String merchant = "";

                    JSONObject scene = json.optJSONObject("sceneExt2");
                    if (scene != null) {
                        merchant = scene.optString("sceneName", "");
                    }

                    if (amount.isEmpty()) continue;

                    String dir;
                    if ("付款成功".equals(topType) || topType.contains("扣款")) {
                        dir = "支出";
                    } else {
                        dir = "收入";
                    }

                    String ts = sdf.format(new Date(gmtCreate));
                    sb.append("[").append(ts).append("] A|")
                      .append(dir).append("|").append(amount).append("|")
                      .append(merchant).append("|").append(method).append("\n");

                    lastId = msgId;
                } catch (Exception e) {
                    Log.w(TAG, "解析支付宝记录失败: " + e.getMessage());
                }
            }
            c.close();
            db.close();

            // 5. 写入
            if (sb.length() > 0) {
                File logFile = new File(LOG_FILE);
                try (FileOutputStream fos = new FileOutputStream(logFile, true);
                     OutputStreamWriter w = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                    w.write(sb.toString());
                    w.flush();
                }
                writeLastId(lastId);
                Log.i(TAG, "支付宝轮询: 追加 " + sb.toString().split("\n").length + " 条");
            }

        } catch (Exception e) {
            Log.e(TAG, "支付宝轮询异常: " + e.getMessage());
        }
    }

    private void writeLastId(String id) {
        try {
            File idFile = new File(ALIPAY_LAST_ID_FILE);
            idFile.getParentFile().mkdirs();
            try (FileOutputStream fos = new FileOutputStream(idFile);
                 OutputStreamWriter w = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                w.write(id);
                w.flush();
            }
        } catch (IOException ignored) {}
    }

    // ── 导出 ──────────────────────────────────────────

    private void doExport(Context context) {
        // 先轮询支付宝支付记录（通过 su 读数据库）
        pollAlipay();

        File logFile = new File(LOG_FILE);
        if (!logFile.exists()) {
            Log.d(TAG, "日志不存在, 跳过");
            return;
        }

        // 只处理最近24小时的记录
        long cutoff = System.currentTimeMillis() - 24 * 3600 * 1000L;
        SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault());

        int count = 0;
        double total = 0;
        StringBuilder csv = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(logFile), StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                Matcher m = LINE_RE.matcher(line);
                if (!m.find()) continue;

                String tsStr = m.group(1);
                String data = m.group(2);

                // 解析时间
                long ts;
                try {
                    Date d = sdf.parse(tsStr);
                    ts = (d != null) ? d.getTime() : 0;
                    // 补齐年份（日志无年份，用当前年）
                    if (ts > System.currentTimeMillis()) {
                        ts -= 365L * 24 * 3600 * 1000; // 可能是跨年
                    }
                } catch (Exception e) {
                    continue;
                }

                if (ts < cutoff) continue;

                // ── 支付宝 ──
                Matcher am = ALIPAY_RE.matcher(data);
                if (am.matches()) {
                    String dir = am.group(1);
                    double amount;
                    try { amount = Double.parseDouble(am.group(2)); }
                    catch (NumberFormatException e) { continue; }
                    String merchant = am.group(3);
                    String method = am.group(4);

                    String cat = guessCategory(merchant, method);
                    String acct = extractAccount(method);
                    String note = merchant.isEmpty() ? method : merchant;

                    SimpleDateFormat df = new SimpleDateFormat(
                            "yyyy/M/d HH:mm", Locale.getDefault());
                    String timeFmt = df.format(new Date(ts));

                    csv.append(timeFmt).append(",")
                       .append(cat).append(",,")
                       .append(dir).append(",")
                       .append(amount).append(",")
                       .append(acct).append(",,")
                       .append(note).append(",,,,,\n");

                    count++;
                    total += amount;
                    continue;
                }

                // ── 微信转账/红包/支付 ──
                Matcher wm = WECHAT_RE.matcher(data);
                if (wm.matches()) {
                    String dirRaw = wm.group(1);
                    String type = wm.group(2);
                    String content = wm.group(3);

                    double amount = 0;
                    Matcher amtM = AMOUNT_RE.matcher(content);
                    if (amtM.find()) {
                        try {
                            amount = Double.parseDouble(
                                    amtM.group(1).replace(",", ""));
                        } catch (NumberFormatException e) { continue; }
                    } else {
                        continue; // 没金额，跳过
                    }

                    String dir = "收到".equals(dirRaw) ? "收入" : "支出";
                    String cat;
                    String note;

                    if (type.startsWith("other:")) {
                        // 仅处理微信支付凭证 (318767153)，过滤公众号文章等含¥的非支付消息
                        if (!type.contains("318767153")) continue;
                        // 支付凭证：收到凭证 = 钱花出去了
                        dir = "支出";
                        String merchant = extractXmlTag(content, "收款方");
                        if (merchant.isEmpty()) {
                            merchant = extractXmlCdata(content, "des");
                            if (merchant.length() > 30) {
                                merchant = merchant.substring(0, 30);
                            }
                        }
                        cat = guessCategoryFromText(content);
                        note = merchant.isEmpty() ? "微信支付" : ("微信支付-" + merchant);
                    } else if ("红包".equals(type)) {
                        cat = "红包";
                        note = "微信红包";
                    } else if ("转账".equals(type)) {
                        cat = "转账";
                        note = "微信转账";
                    } else {
                        cat = type;
                        note = "微信" + type;
                    }

                    SimpleDateFormat df = new SimpleDateFormat(
                            "yyyy/M/d HH:mm", Locale.getDefault());
                    String timeFmt = df.format(new Date(ts));

                    csv.append(timeFmt).append(",")
                       .append(cat).append(",,")
                       .append(dir).append(",")
                       .append(amount).append(",")
                       .append(",,").append(note).append(",,,,,\n");

                    count++;
                    total += amount;
                }

                // ── 更新状态（红包开奖等带金额的 U| 行）──
                Matcher um = UPDATE_RE.matcher(data);
                if (um.matches()) {
                    String dirRaw = um.group(1);
                    String type = um.group(2);
                    String content = um.group(3);

                    double amount = 0;
                    Matcher amtM = AMOUNT_RE.matcher(content);
                    if (amtM.find()) {
                        try {
                            amount = Double.parseDouble(
                                    amtM.group(1).replace(",", ""));
                        } catch (NumberFormatException e) { continue; }
                    } else {
                        continue;
                    }

                    String dir = "收到".equals(dirRaw) ? "收入" : "支出";
                    String cat = "红包".equals(type) ? "红包" : "转账";
                    String note = "红包".equals(type) ? "微信红包" : "微信转账";

                    SimpleDateFormat df = new SimpleDateFormat(
                            "yyyy/M/d HH:mm", Locale.getDefault());
                    String timeFmt = df.format(new Date(ts));

                    csv.append(timeFmt).append(",")
                       .append(cat).append(",,")
                       .append(dir).append(",")
                       .append(amount).append(",")
                       .append(",,").append(note).append(",,,,,\n");

                    count++;
                    total += amount;
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "读取日志失败", e);
            notifyError(context, "读取日志失败: " + e.getMessage());
            return;
        }

        if (count == 0) {
            Log.d(TAG, "没有新账单");
            return;
        }

        // 写入 CSV
        try {
            File outFile = new File(OUT_FILE);
            outFile.getParentFile().mkdirs();

            try (OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(outFile), StandardCharsets.UTF_8)) {
                w.write('\uFEFF'); // BOM
                w.write("时间,分类,二级分类,类型,金额,账户1,账户2,备注,账单标记,手续费,优惠券,标签,账单图片\n");
                w.write(csv.toString());
                w.flush();
            }
            outFile.setReadable(true, false);
        } catch (IOException e) {
            Log.e(TAG, "写入CSV失败", e);
            notifyError(context, "写入失败: " + e.getMessage());
            return;
        }

        // 通知
        notifySuccess(context, count, total);

        // 增量上传原始日志到服务器(方案A: 服务器端解析入库)
        try {
            RawLogUploader.uploadIncremental();
        } catch (Exception e) {
            Log.w(TAG, "上传触发失败: " + e.getMessage());
        }
    }

    // ── XML / 文本解析辅助 ──────────────────────────────

    /** 从文本中提取 XML 标签内的 CDATA 内容 */
    private String extractXmlCdata(String xml, String tag) {
        Pattern p = Pattern.compile(
                "<" + tag + "[^>]*><!\\[CDATA\\[([^\\]]*)\\]\\]></" + tag + ">",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(xml);
        return m.find() ? m.group(1).trim() : "";
    }

    /** 从文本中提取 "key" 后面的值（如 "收款方老婆" → "老婆"） */
    private String extractXmlTag(String text, String key) {
        Pattern p = Pattern.compile(key + "\\s*([\\u4e00-\\u9fa5a-zA-Z0-9\\-]+)");
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1).trim() : "";
    }

    /** 基于文本内容推断消费分类 */
    private String guessCategoryFromText(String text) {
        String t = text.toLowerCase();
        if (containsAny(t, "公交","地铁","打车","滴滴","高德","火车","高铁",
                "加油","充电","停车","铁路","机场","九号","单车","骑行"))
            return "交通";
        if (containsAny(t, "餐厅","饭店","小吃","面馆","米粉","烧烤","火锅",
                "奶茶","咖啡","麦当劳","肯德基","便利店","外卖","美团","饿了么",
                "星巴克","瑞幸","包子","饼","卤","牛肉","快餐","便当",
                "饺子","馄饨","麻辣","烤鸭","汉堡","披萨","寿司",
                "生鲜","买菜","食堂","家常","米线","麻辣烫",
                "零食","饮料","水果","食品","超市"))
            return "三餐";
        if (containsAny(t, "淘宝","京东","拼多多","闲鱼","商城","百货","购物","烟酒","服装"))
            return "购物";
        if (containsAny(t, "房租","水电","燃气","物业","宽带","话费","电网","供暖"))
            return "居家";
        if (containsAny(t, "电影","KTV","乐园","游戏","公园","演出","展览"))
            return "娱乐";
        if (containsAny(t, "医院","药店","诊所","医"))
            return "医疗";
        if (containsAny(t, "学费","培训","书店","文具","图书","校园"))
            return "教育";
        if (containsAny(t, "科技","软件","订阅","会员","苹果","华为","小米","AI"))
            return "数码";
        if (containsAny(t, "转账","汇款","红包"))
            return "转账";
        return "其他";
    }

    // ── 分类 ──────────────────────────────────────────

    private String guessCategory(String merchant, String method) {
        String t = (merchant + " " + method).toLowerCase();
        if (containsAny(t, "餐厅","饭店","小吃","面馆","米粉","烧烤","火锅",
                "奶茶","咖啡","麦当劳","肯德基","便利店","外卖","美团","饿了么",
                "星巴克","瑞幸","包子","饼","卤","牛肉","快餐","便当",
                "饺子","馄饨","麻辣","烤鸭","汉堡","披萨","寿司",
                "生鲜","买菜","食堂","家常","米线","麻辣烫",
                "零食","饮料","水果","食品")) return "三餐";
        if (containsAny(t, "公交","地铁","打车","滴滴","高德","火车","高铁",
                "加油","充电","停车","铁路","机场","九号")) return "交通";
        if (containsAny(t, "淘宝","京东","拼多多","闲鱼","商城","百货",
                "购物","超市","烟酒","服装")) return "购物";
        if (containsAny(t, "房租","水电","燃气","物业","宽带",
                "话费","电网","供暖")) return "居家";
        if (containsAny(t, "电信","移动","联通")) return "通讯";
        if (containsAny(t, "电影","KTV","乐园","游戏","公园")) return "娱乐";
        if (containsAny(t, "医院","药店","诊所","医")) return "医疗";
        if (containsAny(t, "学费","培训","书店","文具","图书","校园")) return "教育";
        if (containsAny(t, "科技","软件","订阅","会员","苹果","华为",
                "小米","AI")) return "数码";
        if (containsAny(t, "转账","汇款")) return "转账";
        return "其他";
    }

    private boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    private String extractAccount(String method) {
        if (method == null || method.isEmpty()) return "支付宝";
        if (method.contains("花呗")) return "花呗";
        if (method.contains("余额")) return "支付宝";
        if (method.contains("银行")) {
            int idx = method.indexOf("银行");
            if (idx > 0) {
                // 往前取到行首或空格 "工商银行"
                int start = idx;
                while (start > 0 && method.charAt(start - 1) > ' ')
                    start--;
                return method.substring(start, idx + 2);
            }
        }
        return "支付宝";
    }

    // ── 通知 ──────────────────────────────────────────

    private void createChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "账单导出",
                    NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("每日账单导出通知");
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private void notifySuccess(Context context, int count, double total) {
        try {
            NotificationCompat.Builder nb = new NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("账单已更新")
                    .setContentText(String.format(Locale.getDefault(),
                            "%d 笔交易，合计 ¥%.2f", count, total))
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true);

            NotificationManagerCompat.from(context).notify(1, nb.build());
        } catch (Exception e) {
            Log.w(TAG, "通知失败", e);
        }
    }

    private void notifyError(Context context, String msg) {
        try {
            NotificationCompat.Builder nb = new NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("账单导出失败")
                    .setContentText(msg)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true);

            NotificationManagerCompat.from(context).notify(2, nb.build());
        } catch (Exception ignored) {}
    }
}
