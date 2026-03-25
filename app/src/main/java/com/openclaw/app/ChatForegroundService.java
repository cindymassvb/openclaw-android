package com.openclaw.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

/**
 * 前台 Service：在 AI 回复流式传输期间运行，防止系统因 App 进后台而杀死进程和 WebSocket。
 *
 * 用法：
 *   ChatForegroundService.start(context);   // 开始流式前调用
 *   ChatForegroundService.stop(context);    // 流式完成/出错后调用
 */
public class ChatForegroundService extends Service {

    private static final String CHANNEL_ID   = "oc_streaming";
    private static final int    NOTIF_ID     = 1001;
    private static final String ACTION_START = "oc.START";
    private static final String ACTION_STOP  = "oc.STOP";

    private PowerManager.WakeLock wakeLock;

    // ── 静态便捷方法 ──────────────────────────────────────────────────────────

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, ChatForegroundService.class);
        i.setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    public static void stop(Context ctx) {
        Intent i = new Intent(ctx, ChatForegroundService.class);
        i.setAction(ACTION_STOP);
        ctx.startService(i);
    }

    // ── 生命周期 ──────────────────────────────────────────────────────────────

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }

        String action = intent.getAction();
        if (ACTION_START.equals(action)) {
            ensureChannel();
            startForeground(NOTIF_ID, buildNotification("小飞正在回复中…"));
            acquireWakeLock();
        } else if (ACTION_STOP.equals(action)) {
            releaseWakeLock();
            stopForeground(true);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        releaseWakeLock();
        super.onDestroy();
    }

    // ── 通知 ──────────────────────────────────────────────────────────────────

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "AI 回复",
                NotificationManager.IMPORTANCE_LOW   // 低重要性：无声音，不打扰
            );
            ch.setDescription("小飞正在生成回复时显示");
            ch.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        // 点击通知跳回 App
        Intent back = new Intent(this, MainActivity.class);
        back.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(
            this, 0, back,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenClaw")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true)        // 不可滑动关闭
            .setSilent(true)         // 无声
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build();
    }

    // ── 唤醒锁：防止 CPU 休眠导致 WebSocket 断连 ─────────────────────────────

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null) return;
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,   // PARTIAL：只锁 CPU，不锁屏幕
            "openclaw:streaming"
        );
        wakeLock.acquire(5 * 60 * 1000L);    // 最多持锁 5 分钟（防泄漏）
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
    }
}
