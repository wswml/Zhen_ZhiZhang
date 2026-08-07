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
            File logFile = new File(LOG_FILE);
            if (!logFile.exists()) return false;

            long offset = readOffset(logFile.length());

            // 读新增行
            StringBuilder sb = new StringBuilder();
            try (RandomAccessFileCompat raf = new RandomAccessFileCompat(logFile, offset)) {
                String line;
                int count = 0;
                while ((line = raf.readLine()) != null && count < MAX_LINES_PER_BATCH) {
                    sb.append(line).append('\n');
                    count++;
                }
                if (count == 0) return false;
                if (count >= MAX_LINES_PER_BATCH) {
                    // 一批没读完，不推进 offset，下次继续(服务器幂等)
                    Log.i(TAG, "批次满 " + count + " 行，下次继续");
                    return uploadLines(sb.toString(), offset);
                }
                // 全部读完 → 推进 offset
                long newOffset = raf.getFilePointer();
                boolean ok = uploadLines(sb.toString(), offset);
                if (ok) writeOffset(newOffset);
                return ok;
            }
        } catch (Exception e) {
            Log.w(TAG, "上传失败: " + e.getMessage());
            return false;
        }
    }

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

    private static long readOffset(long fileLen) {
        try {
            File f = new File(OFFSET_FILE);
            if (!f.exists()) return 0;
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                String line = br.readLine();
                if (line == null) return 0;
                long off = Long.parseLong(line.trim());
                if (off > fileLen) return 0;  // 日志被截断，重新全量
                return off;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    private static void writeOffset(long offset) {
        try {
            File f = new File(OFFSET_FILE);
            f.getParentFile().mkdirs();
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(String.valueOf(offset).getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }
        } catch (Exception ignored) {}
    }

    /** 轻量 RandomAccessFile 包装: 从 offset 读 UTF-8 行，记录结束位置。 */
    private static class RandomAccessFileCompat implements AutoCloseable {
        private final java.io.RandomAccessFile raf;
        private long pointer;

        RandomAccessFileCompat(File file, long offset) throws Exception {
            raf = new java.io.RandomAccessFile(file, "r");
            raf.seek(offset);
            pointer = offset;
        }

        /**
         * 读一行。raf.readLine() 按 ISO-8859-1 逐字节解码(每个 char=1 字节)，
         * 因此把字符串转回字节再按 UTF-8 解码即得原始文本。不会丢中文。
         */
        String readLine() throws Exception {
            String latin1 = raf.readLine();
            if (latin1 == null) return null;
            pointer = raf.getFilePointer();
            return new String(latin1.getBytes(StandardCharsets.ISO_8859_1),
                    StandardCharsets.UTF_8);
        }

        long getFilePointer() {
            return pointer;
        }

        @Override
        public void close() throws Exception {
            raf.close();
        }
    }
}
