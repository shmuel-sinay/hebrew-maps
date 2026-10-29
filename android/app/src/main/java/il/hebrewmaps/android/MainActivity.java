package il.hebrewmaps.android;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.GeolocationPermissions;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Hebrew Maps for Android: the Python server (hmaps) runs inside the app on 127.0.0.1 and the web UI is
 * shown in a WebView. Map data is downloaded from the app itself (menu -> updates) into the app's storage.
 */
public class MainActivity extends Activity {
    private static final int PORT = 8080;
    private static final String BASE = "http://127.0.0.1:" + PORT;
    private static final int REQ_LOCATION = 1;

    private WebView web;
    private TextView status;
    private GeolocationPermissions.Callback pendingGeo;
    private String pendingGeoOrigin;
    private ValueCallback<Uri[]> fileCallback;
    private static final int REQ_FILE = 2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout root = new FrameLayout(this);
        web = buildWebView();
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        status = new TextView(this);
        status.setText("מפות ישראל נטענת…");
        status.setTextSize(20);
        status.setGravity(Gravity.CENTER);
        status.setBackgroundColor(0xFFFFFFFF);
        root.addView(status, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        new Thread(this::boot, "hmaps-boot").start();
    }

    private void boot() {
        try {
            File webDir = new File(getFilesDir(), "web");
            File stamp = new File(getFilesDir(), "web.version");
            String ver = String.valueOf(getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime);
            if (!stamp.exists() || !ver.equals(readText(stamp))) {
                deleteTree(webDir);
                copyAssets("web", getFilesDir());
                writeText(stamp, ver);
            }
            if (!Python.isStarted()) Python.start(new AndroidPlatform(this));
            PyObject m = Python.getInstance().getModule("android_main");
            m.callAttr("start", new File(getExternalFilesDir(null) != null ? getExternalFilesDir(null) : getFilesDir(), "data").getAbsolutePath(),
                    webDir.getAbsolutePath(), PORT);
            for (int i = 0; i < 120; i++) {
                if (healthy()) {
                    runOnUiThread(() -> { status.setVisibility(android.view.View.GONE); web.loadUrl(BASE + "/map.html"); });
                    return;
                }
                Thread.sleep(500);
            }
            runOnUiThread(() -> status.setText("השרת לא עלה. נסו לפתוח את האפליקציה מחדש."));
        } catch (Throwable t) {
            runOnUiThread(() -> status.setText("שגיאה בהפעלה:\n" + t));
        }
    }

    private boolean healthy() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(BASE + "/api/health").openConnection();
            c.setConnectTimeout(500);
            c.setReadTimeout(1500);
            return c.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private void copyAssets(String path, File destRoot) throws Exception {
        String[] list = getAssets().list(path);
        File out = new File(destRoot, path);
        if (list != null && list.length > 0) {
            out.mkdirs();
            for (String n : list) copyAssets(path + "/" + n, destRoot);
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

    private WebView buildWebView() {
        WebView w = new WebView(this);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setGeolocationEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if ("127.0.0.1".equals(u.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) { }
                return true;
            }
        });
        w.setWebChromeClient(new WebChromeClient() {
            // "update from file": the update zip is picked with the system file picker
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    cb.invoke(origin, true, false);
                } else {
                    pendingGeo = cb;
                    pendingGeoOrigin = origin;
                    requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
                }
            }
        });
        return w;
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        if (code == REQ_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data));
            fileCallback = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == REQ_LOCATION && pendingGeo != null) {
            pendingGeo.invoke(pendingGeoOrigin, results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED, false);
            pendingGeo = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
