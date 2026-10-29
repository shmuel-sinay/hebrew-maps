package il.hebrewmaps.android;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
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
    private static final int REQ_NOTIFY = 3;

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
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
        }
        startForegroundService(new Intent(this, ServerService.class));
        new Thread(this::boot, "hmaps-boot").start();
    }

    private void boot() {
        for (int i = 0; i < 240; i++) {
            if (ServerService.error != null) {
                runOnUiThread(() -> status.setText("שגיאה בהפעלה:\n" + ServerService.error));
                return;
            }
            if (healthy()) {
                runOnUiThread(() -> { status.setVisibility(android.view.View.GONE); web.loadUrl(BASE + "/map.html"); });
                return;
            }
            try { Thread.sleep(500); } catch (InterruptedException e) { return; }
        }
        runOnUiThread(() -> status.setText("השרת לא עלה. נסו לפתוח את האפליקציה מחדש."));
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
        if (code == REQ_NOTIFY) return;
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
