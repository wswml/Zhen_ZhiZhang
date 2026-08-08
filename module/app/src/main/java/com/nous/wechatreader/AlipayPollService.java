package com.nous.wechatreader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 支付宝账单轮询前台服务（替代失效的 AlarmManager 闹钟）。
 *
 * 设计（2026-08-07）:
 * - 模块闹钟从未运行过（dumpsys alarm 无记录），支付宝轮询（需 su root）依赖闹钟 → 断
 * - 本服务跑在模块自身进程（com.nous.wechatreader），KernelSU 给模块授权 root 后 su 可用
 * - 前台服务 + START_STICKY 常驻，每 10 分钟轮询支付宝 DB → 写 A| 行 → 上传服务器
 * - 通知 channel IMPORTANCE_NONE：不显示通知、无状态栏图标（合法隐藏）
 * - 与 Termux 兜底脚本共用 .alipay_last_id（gmtCreate 数值）和 .upload_offset，幂等
 */
public class AlipayPollService extends Service {

    private static final String TAG = "WechatReader-Poll";
    private static final String CHANNEL_ID = "alipay_poll_channel";
    private static final int NOTIF_ID = 1001;

    private static final long INTERVAL_MS = 10 * 60 * 1000L;  // 10 分钟

    private static final String ALIPAY_DB =
            "/data/data/com.eg.android.AlipayGphone/databases/messagebox.db";
    private static final String ALIPAY_TMP =
            "/data/local/tmp/alipay_service.db";
    private static final String ALIPAY_LAST_ID_FILE =
            "/storage/emulated/0/Download/WechatReader/.alipay_last_id";
    private static final String LOG_FILE =
            "/storage/emulated/0/Download/WechatReader/messages.log";

    private Handler handler;
    private Runnable pollTask;

    @Override
    public void onCreate() {
        super.onCreate();
        startAsForeground();
        handler = new Handler(Looper.getMainLooper());
        pollTask = new Runnable() {
            @Override
            public void run() {
                try {
                    new Thread(() -> {
                        try {
                            pollAndUpload();
                        } catch (Exception e) {
                            Log.w(TAG, "轮询异常: " + e.getMessage());
                        }
                    }).start();
                } catch (Exception e) {
                    Log.w(TAG, "轮询线程异常: " + e.getMessage());
                }
                handler.postDelayed(this, INTERVAL_MS);
            }
        };
        // 启动后立即轮询一次，之后每 10 分钟
        handler.post(pollTask);
        Log.i(TAG, "支付宝轮询服务已启动 (10分钟间隔)");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;  // 被系统杀死后自动重启
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (handler != null && pollTask != null) {
            handler.removeCallbacks(pollTask);
        }
        super.onDestroy();
    }

    // ── 前台服务 + 隐藏通知 ──

    private void startAsForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "后台记账",
                    NotificationManager.IMPORTANCE_NONE);  // 不显示通知
            channel.setShowBadge(false);
            channel.enableVibration(false);
            channel.setSound(null, null);
            if (nm != null) nm.createNotificationChannel(channel);
        }
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_upload)
                .setContentTitle("后台记账运行中")
                .setContentText("每10分钟同步支付宝账单")
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= 34) {
            // FOREGROUND_SERVICE_TYPE_SPECIAL_USE = 1073741824 (API 34)
            // 旧 android.jar stub 无三参重载，用反射调用（运行时 API 29+ 才有）
            try {
                java.lang.reflect.Method m = Service.class.getMethod(
                        "startForeground", int.class, Notification.class, int.class);
                m.invoke(this, NOTIF_ID, notification, 1073741824);
            } catch (Throwable t) {
                startForeground(NOTIF_ID, notification);
            }
        } else {
            startForeground(NOTIF_ID, notification);
        }
    }

    // ── 轮询 + 上传 ──

    private void pollAndUpload() {
        pollAlipay();
        RawLogUploader.uploadIncremental();
    }

    /** 支付宝轮询：su 复制 DB → 读 gmtCreate 增量 → 追加 A| 行到 messages.log */
    private void pollAlipay() {
        try {
            // ⚠️ 必须 su -M (--mount-master)：app 进程的 mount namespace 被隔离
            // (zygote 派生进程只能看到自身+gms等白名单包)，普通 su 继承隔离视图，
            // cp 会报 No such file。su -M 强制切到全局 mount namespace。
            ProcessBuilder pb = new ProcessBuilder(
                    "/system/bin/sh", "-c",
                    "su -M -c 'cp " + ALIPAY_DB + " " + ALIPAY_TMP
                    + " && chmod 644 " + ALIPAY_TMP + "'"
            );
            Process p = pb.start();
            // 读取 stderr 便于诊断（su 失败原因：未授权 / 找不到 su / SELinux）
            StringBuilder errSb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) errSb.append(line).append('\n');
            }
            p.waitFor();
            if (p.exitValue() != 0) {
                Log.w(TAG, "支付宝 DB 复制失败 exit=" + p.exitValue()
                        + " err=" + errSb.toString().trim());
                return;
            }

            long lastGmt = 0;
            File idFile = new File(ALIPAY_LAST_ID_FILE);
            if (idFile.exists()) {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(new FileInputStream(idFile)))) {
                    String line = br.readLine();
                    if (line != null) lastGmt = Long.parseLong(line.trim());
                } catch (Exception ignored) {
                    // ⚠️ 模块进程无 /sdcard 存储权限 → FileInputStream 读 EACCES
                    // → lastGmt=0 → 每次轮询全量追加所有支付宝记录(messages.log 爆炸)
                    // fallback: su -M cat 读取(与 RawLogUploader 同款降级)
                    try {
                        String out = execSu("cat " + ALIPAY_LAST_ID_FILE);
                        if (out != null) {
                            String line = out.trim();
                            if (!line.isEmpty()) lastGmt = Long.parseLong(line);
                        }
                    } catch (Exception ignored2) {}
                }
            }

            SQLiteDatabase db = SQLiteDatabase.openDatabase(
                    ALIPAY_TMP, null, SQLiteDatabase.OPEN_READONLY);
            Cursor c = db.rawQuery(
                    "SELECT gmtCreate, content FROM service_message"
                    + " WHERE title='支付助手' AND gmtCreate > ? ORDER BY gmtCreate",
                    new String[]{String.valueOf(lastGmt)});

            StringBuilder sb = new StringBuilder();
            long maxGmt = lastGmt;
            SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault());

            while (c.moveToNext()) {
                long gmtCreate = c.getLong(0);
                String content = c.getString(1);
                maxGmt = Math.max(maxGmt, gmtCreate);
                try {
                    JSONObject json = new JSONObject(content);
                    if (!json.optBoolean("isPaymentMsg", false)) continue;
                    String amount = json.optString("content", "");
                    if (amount.isEmpty()) continue;
                    String top = json.optString("topSubContent", "");
                    String merchant = "";
                    JSONObject scene = json.optJSONObject("sceneExt2");
                    if (scene != null) merchant = scene.optString("sceneName", "");
                    String method = json.optString("assistMsg1", "");
                    // 花呗还款跳过（消费已在花呗消费时记录）
                    if ("花呗".equals(merchant)) continue;

                    String direc = ("付款成功".equals(top) || top.contains("扣款"))
                            ? "支出" : "收入";
                    String ts = sdf.format(new Date(gmtCreate));
                    sb.append("[").append(ts).append("] A|")
                      .append(direc).append("|").append(amount).append("|")
                      .append(merchant).append("|").append(method).append("\n");
                } catch (Exception ignored) {}
            }
            c.close();
            db.close();

            if (sb.length() > 0) {
                // 用 su -M 追加（模块进程无 /sdcard 存储权限，EACCES；root 写入绕开）
                if (appendViaSu(LOG_FILE, sb.toString())) {
                    writeLastGmt(maxGmt);
                    Log.i(TAG, "支付宝轮询: 新增 " + sb.toString().split("\n").length + " 条");
                } else {
                    Log.w(TAG, "支付宝轮询: 写入 messages.log 失败 (su append)");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "支付宝轮询异常: " + e.getMessage());
        }
    }

    /** 执行 su -M 命令并返回 stdout（null=失败）— 与 RawLogUploader 同款 */
    private static String execSu(String cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "/system/bin/sh", "-c",
                    "su -M -c '" + cmd.replace("'", "'\\''") + "'"
            );
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) out.append(line).append('\n');
            }
            p.waitFor();
            if (p.exitValue() != 0) {
                Log.w(TAG, "su 命令失败 exit=" + p.exitValue());
                return null;
            }
            return out.toString();
        } catch (Exception e) {
            Log.w(TAG, "su 命令异常: " + e.getMessage());
            return null;
        }
    }

    /** 通过 su -M 写文件（root 写入，绕开 app 存储权限）。append=false 覆盖。 */
    private boolean suWrite(String path, String content, boolean append) {
        try {
            // 内容经 base64 传递，避免 shell 转义问题
            String b64 = android.util.Base64.encodeToString(
                    content.getBytes(StandardCharsets.UTF_8),
                    android.util.Base64.NO_WRAP);
            ProcessBuilder pb = new ProcessBuilder(
                    "/system/bin/sh", "-c",
                    "su -M -c 'mkdir -p $(dirname " + path + ") && "
                    + "echo " + b64 + " | base64 -d " + (append ? ">>" : ">") + " " + path + "'"
            );
            Process p = pb.start();
            StringBuilder errSb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) errSb.append(line).append('\n');
            }
            p.waitFor();
            if (p.exitValue() != 0) {
                Log.w(TAG, "su 写失败 exit=" + p.exitValue() + " err="
                        + errSb.toString().trim());
                return false;
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "su 写异常: " + e.getMessage());
            return false;
        }
    }

    /** 追加写（messages.log 日志） */
    private boolean appendViaSu(String path, String content) {
        return suWrite(path, content, true);
    }

    private void writeLastGmt(long gmt) {
        // 模块进程无 /sdcard 写权限，用 su -M 覆盖写（状态文件必须覆盖，不能追加）
        try {
            suWrite(ALIPAY_LAST_ID_FILE, String.valueOf(gmt), false);
        } catch (Exception ignored) {}
    }
}
