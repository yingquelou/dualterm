package dev.dualterm.term;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import dev.dualterm.MainActivity;
import dev.dualterm.R;

/**
 * 会话保活：前台服务 + 部分唤醒锁。
 *
 * 为什么需要它：Android 12 起 cached app 会被 freezer 直接 SIGSTOP，屏幕一关、
 * 界面一进后台，容器里跑的服务就「还在进程列表里但一个包也不回」；再往后被 LMK 收掉。
 * 这不是网络问题，进程停了什么端口都白搭。把 App 顶成前台服务进程 + 持一把
 * PARTIAL_WAKE_LOCK，才能让「手机上的小服务器」真的能一直应答。
 *
 * 边界：
 * <ul>
 *   <li>只跟着**活着的终端会话**走，会话全结束就 stopSelf，不做常驻守护、不做开机自启</li>
 *   <li>不申请 POST_NOTIFICATIONS：没有通知权限时前台服务照样跑，只是通知不可见；
 *       弹一次系统授权框去换一个「看不见也不影响功能」的权限，不值</li>
 *   <li>START_NOT_STICKY：进程都被杀了，会话早跟着没了，重启服务毫无意义</li>
 * </ul>
 */
public final class SessionService extends Service {

    private static final String CHANNEL_ID = "dualterm-session";
    private static final int NOTIF_ID = 0x4454;
    private static final String EXTRA_COUNT = "count";

    private PowerManager.WakeLock wakeLock;

    /**
     * 上次同步的会话数（-1 = 服务没在跑）。只在主线程访问。
     *
     * 只在数量变化时才启动服务：重启会话会先摘旧 tab 再起新会话，count 会走
     * 1 → 0 → 1 的瞬时序列；逐次照办就是 stopService/startForegroundService
     * 来回抖，通知和唤醒锁跟着成对 REL/ACQ。所以「收摊」延后一拍，
     * 期间只要有新会话就撤销。
     */
    private static int syncedCount = -1;
    private static Context appContext;
    private static boolean pendingStop;
    private static final android.os.Handler MAIN =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private static final Runnable STOP_TASK = new Runnable() {
        @Override
        public void run() {
            if (!pendingStop || appContext == null) {
                return;
            }
            pendingStop = false;
            syncedCount = -1;
            appContext.stopService(new Intent(appContext, SessionService.class));
        }
    };

    /** 会话数变化时调一次：count <= 0 表示收摊。 */
    public static void sync(Context ctx, int count) {
        if (ctx == null) {
            return;
        }
        appContext = ctx.getApplicationContext();
        if (count <= 0) {
            pendingStop = true;
            MAIN.removeCallbacks(STOP_TASK);
            MAIN.postDelayed(STOP_TASK, 800);
            return;
        }
        pendingStop = false;
        MAIN.removeCallbacks(STOP_TASK);
        if (count == syncedCount) {
            return;
        }
        syncedCount = count;
        Intent i = new Intent(appContext, SessionService.class);
        i.putExtra(EXTRA_COUNT, count);
        appContext.startForegroundService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dualterm:session");
            wakeLock.setReferenceCounted(false);
            // 不带超时：会话活着就该醒着，收尾统一在 onDestroy 里 release
            wakeLock.acquire();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int count = intent == null ? 1 : intent.getIntExtra(EXTRA_COUNT, 1);
        if (count <= 0) {
            stopSelf();
            return START_NOT_STICKY;
        }
        Notification n = build(count);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
        syncedCount = -1;
        pendingStop = false;
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "终端会话",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("终端会话在后台运行时保持唤醒");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification build(int count) {
        Intent open = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("终端会话运行中")
                .setContentText(count + " 个会话保持唤醒，切走后不会被冻结")
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(pi);
        return b.build();
    }
}
