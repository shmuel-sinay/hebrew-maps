package il.hebrewmaps.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Runs the Python server in a foreground service so Android keeps the process alive while the screen is off
 * or the user is in another app - long data downloads and an open map keep working.
 */
public class ServerService extends Service {
    static final int PORT = 8080;
    static final String ACTION_STOP = "il.hebrewmaps.android.STOP";
    private static final String CHANNEL = "server";
    /** Set when startup failed; shown by the activity. */
    static volatile String error;
    /** Current startup step, shown on the loading screen. */
    static volatile String stage = "מתחיל…";
    static volatile PyObject module;
    private static boolean started;
    private PowerManager.WakeLock lock;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopForeground(true);
            stopSelf();
            android.os.Process.killProcess(android.os.Process.myPid());
            return START_NOT_STICKY;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "שרת המפות", NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, ServerService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        Notification n = b.setSmallIcon(android.R.drawable.ic_dialog_map)
                .setContentTitle("מפות ישראל פעילה")
                .setContentText("השרת רץ ברקע (עדכון נתונים ומפה). לחצו לפתיחה.")
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "עצירה", stop).build())
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(1, n);

        if (lock == null) {
            lock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hebrewmaps:server");
            lock.acquire();
        }
        synchronized (ServerService.class) {
            if (!started) {
                started = true;
                new Thread(this::boot, "hmaps-boot").start();
            }
        }
        return START_STICKY;
    }

    private void boot() {
        try {
            stage = "מכין קבצים (פעם ראשונה לוקח זמן)…";
            File webDir = new File(getFilesDir(), "web");
            File stamp = new File(getFilesDir(), "web.version");
            String ver = String.valueOf(getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime);
            if (!stamp.exists() || !ver.equals(readText(stamp))) {
                deleteTree(webDir);
                copyAssets("web");
                writeText(stamp, ver);
            }
            stage = "מפעיל את הפייתון…";
            if (!Python.isStarted()) Python.start(new AndroidPlatform(this));
            PyObject m = Python.getInstance().getModule("android_main");
            module = m;
            stage = "טוען את השרת…";
            File ext = getExternalFilesDir(null);
            m.callAttr("start", new File(ext != null ? ext : getFilesDir(), "data").getAbsolutePath(), webDir.getAbsolutePath(), PORT);
            stage = "ממתין לשרת…";
        } catch (Throwable t) {
            error = android.util.Log.getStackTraceString(t);
            android.util.Log.e("HebrewMaps", "startup failed", t);
        }
    }

    private void copyAssets(String path) throws Exception {
        String[] list = getAssets().list(path);
        File out = new File(getFilesDir(), path);
        if (list != null && list.length > 0) {
            out.mkdirs();
            for (String n : list) copyAssets(path + "/" + n);
        } else {
            out.getParentFile().mkdirs();
            try (InputStream in = getAssets().open(path); OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
        }
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    private static String readText(File f) throws Exception {
        return new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
    }

    private static void writeText(File f, String s) throws Exception {
        java.nio.file.Files.write(f.toPath(), s.getBytes("UTF-8"));
    }

    @Override
    public void onDestroy() {
        if (lock != null && lock.isHeld()) lock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
