package com.nous.wechatreader;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 增量上传 messages.log 原始行到服务器 /api/entry/import/rawlog。
 *
 * 设计:
 * - offset 文件记录上次上传到的字节位置，每次只读新增行
 * - 服务器端累积全量行后全量重解析(转账双记合并/群收款需要跨批次上下文)，
 *   因此上传失败(网络)时只需保留 offset 不推进，下次重试即可
 * - messages.log 被截断时 offset 会超过文件长度 → 重置为 0 重新全量上传
 *   (服务器按 (day,money,name) 去重，幂等)
 * - 双环境适配:
 *   * 微信进程(有 /sdcard 权限) → 直接文件读写
 *   * 模块服务进程(无存储权限但有 root) → 读/写失败时 fallback su -M
 */
public class RawLogUploader {

    private static final String TAG = "WechatReader-Upload";

    private static final String LOG_FILE =
            "/storage/emulated/0/Download/WechatReader/messages.log";
    private static final String OFFSET_FILE =
            "/storage/emulated/0/Download/WechatReader/.upload_offset";

    // 服务器配置
    private static final String SERVER_URL =
            "http://47.99.240.71:9090/api/entry/import/rawlog";
    private static final String MODULE_TOKEN =
            "wxr_488b8ea5b031f084995fa94a341b6ddc";
    private static final String BOOK_ID = "1-u3wit23z";

    private static final int MAX_LINES_PER_BATCH = 500;
    private static final int CONNECT_TIMEOUT = 10000;
    private static final int READ_TIMEOUT = 20000;

    private static long sLastUploadAttempt = 0;

    /** 上传新行。成功返回 true；无新行或网络失败返回 false。 */
    public static synchronized boolean uploadIncremental() {
        // 限流: 两次上传间隔至少 30 秒(微信支付触发频繁时避免重复请求)
        long now = System.currentTimeMillis();
        if (now - sLastUploadAttempt < 30000) return false;
        sLastUploadAttempt = now;

        try {
            long fileLen = fileLength();
            if (fileLen < 0) return false;

            long offset = readOffset(fileLen);

            // 读新增行（普通读失败时 fallback su -M）
            String newContent = readFromOffset(offset);
            if (newContent == null || newContent.isEmpty()) return false;
            int count = newContent.split("\n", -1).length - 1;
            if (count == 0) return false;

            if (count >= MAX_LINES_PER_BATCH) {
                // 一批没读完，不推进 offset，下次继续(服务器幂等)
                Log.i(TAG, "批次满 " + count + " 行，下次继续");
                return uploadLines(newContent, offset);
            }
            // 全部读完 → 推进 offset
            long newOffset = offset + contentBytes(newContent);
            boolean ok = uploadLines(newContent, offset);
            if (ok) writeOffset(newOffset);
            return ok;
        } catch (Exception e) {
            Log.w(TAG, "上传失败: " + e.getMessage());
            return false;
        }
    }

    // ── 文件读写（普通 → su -M fallback）──

    private static long fileLength() {
        try {
            return new File(LOG_FILE).length();
        } catch (Exception e) {
            return -1;
        }
    }

    /** 从 offset 读增量行。返回 null 表示失败。 */
    private static String readFromOffset(long offset) {
        // 先试普通读
        try {
            StringBuilder sb = new StringBuilder();
            try (java.io.RandomAccessFile raf =
                         new java.io.RandomAccessFile(LOG_FILE, "r")) {
                raf.seek(offset);
                String line;
                int count = 0;
                while ((line = raf.readLine()) != null
                        && count < MAX_LINES_PER_BATCH) {
                    // readLine 是 ISO-8859-1 字节解码，转回 UTF-8
                    sb.append(new String(line.getBytes(StandardCharsets.ISO_8859_1),
                            StandardCharsets.UTF_8)).append('\n');
                    count++;
                }
            }
            return sb.length() > 0 ? sb.toString() : "";
        } catch (Exception e) {
            Log.d(TAG, "普通读失败(" + e.getMessage() + ")，尝试 su -M");
        }
        // fallback: su -M cat 全文件再截取
        try {
            String full = execSu("cat " + LOG_FILE);
            if (full == null) return null;
            if (offset >= full.getBytes(StandardCharsets.UTF_8).length) return "";
            // 按字节截取 offset 之后，再按行切
            byte[] bytes = full.getBytes(StandardCharsets.UTF_8);
            String tail = new String(bytes, (int) offset,
                    bytes.length - (int) offset, StandardCharsets.UTF_8);
            // 截断到 MAX_LINES 行
            String[] lines = tail.split("\n", -1);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines.length && i < MAX_LINES_PER_BATCH; i++) {
                sb.append(lines[i]).append('\n');
            }
            return sb.length() > 0 ? sb.toString() : "";
        } catch (Exception e) {
            Log.w(TAG, "su 读失败: " + e.getMessage());
            return null;
        }
    }

    private static long readOffset(long fileLen) {
        try {
            String s = readSmallFile(OFFSET_FILE);
            if (s == null) return 0;
            long off = Long.parseLong(s.trim());
            if (off > fileLen) return 0;  // 日志被截断，重新全量
            return off;
        } catch (Exception e) {
            return 0;
        }
    }

    private static void writeOffset(long offset) {
        writeSmallFile(OFFSET_FILE, String.valueOf(offset));
    }

    /** 读小文件（offset 文件），普通失败走 su -M */
    private static String readSmallFile(String path) {
        try {
            File f = new File(path);
            if (!f.exists()) return null;
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                return br.readLine();
            }
        } catch (Exception e) {
            Log.d(TAG, "普通读 " + path + " 失败，走 su");
        }
        return execSu("cat " + path);
    }

    /** 写小文件（offset 文件），普通失败走 su -M */
    private static void writeSmallFile(String path, String content) {
        try {
            File f = new File(path);
            f.getParentFile().mkdirs();
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }
            return;
        } catch (Exception e) {
            Log.d(TAG, "普通写 " + path + " 失败，走 su");
        }
        try {
            String b64 = android.util.Base64.encodeToString(
                    content.getBytes(StandardCharsets.UTF_8),
                    android.util.Base64.NO_WRAP);
            execSu("echo " + b64 + " | base64 -d > " + path);
        } catch (Exception ignored) {}
    }

    /** 执行 su -M 命令并返回 stdout（null=失败） */
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
            StringBuilder errSb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) errSb.append(line).append('\n');
            }
            p.waitFor();
            if (p.exitValue() != 0) {
                Log.w(TAG, "su 命令失败 exit=" + p.exitValue()
                        + " err=" + errSb.toString().trim());
                return null;
            }
            return out.toString();
        } catch (Exception e) {
            Log.w(TAG, "su 命令异常: " + e.getMessage());
            return null;
        }
    }

    private static long contentBytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    // ── 上传 ──

    private static boolean uploadLines(String lines, long offset) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(SERVER_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("X-Module-Token", MODULE_TOKEN);
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setDoOutput(true);

            String body = buildJson(lines);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "服务器返回 " + code);
                return false;
            }

            // 读响应(JSON)，成功即可
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String resp = br.readLine();
                Log.i(TAG, "上传成功: " + (resp != null ? resp : ""));
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "请求异常: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String buildJson(String lines) {
        // 手动构建 JSON(不依赖 org.json 在模块中可能缺失的构建配置)
        StringBuilder sb = new StringBuilder();
        sb.append("{\"book_id\":\"").append(BOOK_ID).append("\",\"lines\":[");
        String[] arr = lines.split("\n", -1);
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].isEmpty()) continue;
            if (i > 0) sb.append(',');
            sb.append('"').append(escapeJson(arr[i])).append('"');
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
