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
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;


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
    private static final int REQ_NOTIFY = 3;
    private static final int REQ_PICK = 4;
    private LinearLayout choice;
    private volatile Uri pendingZip;
    private volatile boolean serverUp;

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
        choice = new LinearLayout(this);
        choice.setOrientation(LinearLayout.VERTICAL);
        choice.setGravity(Gravity.CENTER);
        choice.setBackgroundColor(0xFFFFFFFF);
        choice.setPadding(48, 48, 48, 48);
        choice.setVisibility(android.view.View.GONE);
        TextView ct = new TextView(this);
        ct.setText("עדיין אין נתוני מפה.\nאפשר להתקין אותם מקובץ עדכון (HebrewMaps-Update.zip שהורדתם מראש) או להוריד מהאינטרנט.");
        ct.setTextSize(18);
        ct.setGravity(Gravity.CENTER);
        choice.addView(ct);
        Button fromFile = new Button(this);
        fromFile.setText("התקנה מקובץ עדכון");
        fromFile.setOnClickListener(v -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip");
            startActivityForResult(pick, REQ_PICK);
        });
        choice.addView(fromFile);
        Button online = new Button(this);
        online.setText("הורדה מהאינטרנט (תפריט ← עדכונים)");
        online.setOnClickListener(v -> openMap());
        choice.addView(online);
        root.addView(choice, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        pendingZip = zipFrom(getIntent());
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
        }
        startForegroundService(new Intent(this, ServerService.class));
        new Thread(this::boot, "hmaps-boot").start();
    }

    private void boot() {
        for (int i = 0; i < 600; i++) {
            String err = ServerService.error;
            if (err == null && ServerService.module != null) {
                try { String e = ServerService.module.callAttr("last_error").toString(); if (!e.isEmpty()) err = e; } catch (Throwable ignored) { }
            }
            if (err != null) {
                final String shown = err.length() > 1500 ? err.substring(err.length() - 1500) : err;
                runOnUiThread(() -> { status.setTextSize(12); status.setText("שגיאה בהפעלה (שלחו צילום מסך):\n\n" + shown); });
                return;
            }
            String body = healthBody();
            if (body != null) {
                serverUp = true;
                boolean hasData = body.replaceAll("\\s", "").contains("\"dataset\":true");
                Uri zip = pendingZip;
                if (zip != null) importZip(zip);
                else if (!hasData) runOnUiThread(this::showChoice);
                else runOnUiThread(this::openMap);
                return;
            }
            final int sec = i / 2;
            runOnUiThread(() -> status.setText("מפות ישראל נטענת…\n" + ServerService.stage + "\n" + sec + " שניות"));
            try { Thread.sleep(500); } catch (InterruptedException e) { return; }
        }
        runOnUiThread(() -> status.setText("השרת לא עלה אחרי חמש דקות.\nשלב אחרון: " + ServerService.stage));
    }

    /** Body of /api/health (also for 503 "degraded" = server up, no map data yet), null while the server is not up. */
    private String healthBody() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(BASE + "/api/health").openConnection();
            c.setConnectTimeout(500);
            c.setReadTimeout(1500);
            int code = c.getResponseCode();
            java.io.InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            return in == null ? "" : readAll(in);
        } catch (Exception e) {
            return null;
        }
    }

    /** InputStream.readAllBytes needs Android 13; this works from API 24. */
    private static String readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        return bo.toString("UTF-8");
    }

    private void openMap() {
        choice.setVisibility(android.view.View.GONE);
        status.setVisibility(android.view.View.GONE);
        web.loadUrl(BASE + "/map.html");
    }

    private void showChoice() {
        status.setVisibility(android.view.View.GONE);
        choice.setVisibility(android.view.View.VISIBLE);
    }

    private static Uri zipFrom(Intent i) {
        if (i == null) return null;
        if (Intent.ACTION_VIEW.equals(i.getAction())) return i.getData();
        if (Intent.ACTION_SEND.equals(i.getAction())) return i.getParcelableExtra(Intent.EXTRA_STREAM);
        return null;
    }

    private void setStatus(String s) {
        runOnUiThread(() -> { choice.setVisibility(android.view.View.GONE); status.setVisibility(android.view.View.VISIBLE); status.setTextSize(20); status.setText(s); });
    }

    /** Streams the chosen update zip into the local server (same endpoint as "update from file" in the web UI). */
    private void importZip(Uri uri) {
        pendingZip = null;
        new Thread(() -> {
            try {
                long size = -1;
                try (android.database.Cursor c = getContentResolver().query(uri, new String[]{android.provider.OpenableColumns.SIZE}, null, null, null)) {
                    if (c != null && c.moveToFirst() && !c.isNull(0)) size = c.getLong(0);
                }
                HttpURLConnection c = (HttpURLConnection) new URL(BASE + "/api/updates/offline").openConnection();
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("X-HMaps-Update", "1");
                c.setRequestProperty("Content-Type", "application/zip");
                if (size > 0) c.setFixedLengthStreamingMode(size); else c.setChunkedStreamingMode(262144);
                c.setReadTimeout(0);
                int last = -1;
                try (java.io.InputStream in = getContentResolver().openInputStream(uri); OutputStream out = c.getOutputStream()) {
                    byte[] buf = new byte[262144];
                    long done = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done += n;
                        int pct = size > 0 ? (int) (done * 100 / size) : (int) (done >> 20);
                        if (pct != last) { last = pct; setStatus(size > 0 ? "מעתיק את קובץ העדכון… " + pct + "%" : "מעתיק את קובץ העדכון… " + pct + "MB"); }
                    }
                }
                int code = c.getResponseCode();
                java.io.InputStream rs = code >= 400 ? c.getErrorStream() : c.getInputStream();
                String body = rs == null ? "" : readAll(rs);
                if (code == 200 || code == 202) {
                    setStatus("הקובץ נקלט. ההתקנה ממשיכה ברקע (התקדמות: תפריט ← עדכונים).");
                    Thread.sleep(2500);
                    runOnUiThread(this::openMap);
                } else {
                    String msg = body.replaceAll(".*\"message\"\\s*:\\s*\"([^\"]*)\".*", "$1");
                    setStatus("ההתקנה מהקובץ נכשלה:\n" + (msg.equals(body) ? "קוד " + code : msg));
                    Thread.sleep(4000);
                    runOnUiThread(this::showChoice);
                }
            } catch (Throwable e) {
                setStatus("ההתקנה מהקובץ נכשלה:\n" + e);
                try { Thread.sleep(4000); } catch (InterruptedException ignored) { }
                runOnUiThread(this::showChoice);
            }
        }, "hmaps-import").start();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Uri z = zipFrom(intent);
        if (z == null) return;
        if (serverUp) importZip(z); else pendingZip = z;
    }

    private WebView buildWebView() {
        WebView w = new WebView(this);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setGeolocationEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        // lets the web UI share text (GPX tracks, locations) through the Android share sheet
        w.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void shareText(String title, String text) {
                Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(send, title));
            }
        }, "HmapsAndroid");
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
        if (code == REQ_PICK) {
            if (result == RESULT_OK && data != null && data.getData() != null) importZip(data.getData());
            return;
        }
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
