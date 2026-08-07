package com.nous.wechatreader;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

/**
 * 极简启动入口：点图标 → 启动支付宝轮询前台服务 → 立即退出。
 * 模块没有桌面 UI，此 Activity 只负责启动常驻服务。
 */
public class LaunchActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            Intent intent = new Intent(this, AlipayPollService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            Toast.makeText(this, "后台记账已启动（每10分钟同步支付宝）", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "启动失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        finish();
    }
}
