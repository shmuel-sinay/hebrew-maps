package il.hebrewmaps.android;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.GeolocationPermissions;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A thin Android shell around the Hebrew Maps web app: the maps server (the Windows program, or any
 * server running hmaps) is entered once and then shown full screen in a WebView.
 */
public class MainActivity extends Activity {
    private static final String PREFS = "hmaps";
    private static final String KEY_URL = "server";
    private static final int REQ_LOCATION = 1;

    private WebView web;
    private View setup;
    private EditText input;
    private TextView error;
    private GeolocationPermissions.Callback pendingGeo;
    private String pendingGeoOrigin;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout root = new FrameLayout(this);
        web = buildWebView();
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView gear = new TextView(this);
        gear.setText(R.string.settings);
        gear.setTextSize(20);
        gear.setTextColor(Color.WHITE);
        gear.setBackgroundColor(0x99000000);
        gear.setPadding(24, 8, 24, 8);
        gear.setOnClickListener(v -> showSetup(null));
        FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.LEFT);
        gp.setMargins(16, 0, 0, 160);
        root.addView(gear, gp);

        setup = buildSetup();
        root.addView(setup, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        String saved = prefs().getString(KEY_URL, "");
        if (saved.isEmpty()) {
            showSetup(null);
        } else {
            setup.setVisibility(View.GONE);
            web.loadUrl(saved);
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private WebView buildWebView() {
        WebView w = new WebView(this);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setGeolocationEnabled(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setBuiltInZoomControls(false);
        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                Uri base = Uri.parse(prefs().getString(KEY_URL, ""));
                if (u.getHost() != null && u.getHost().equals(base.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) { }
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) showSetup(getString(R.string.setup_error));
            }
        });
        w.setWebChromeClient(new WebChromeClient() {
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

    private View buildSetup() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.WHITE);
        box.setGravity(Gravity.CENTER);
        box.setPadding(48, 48, 48, 48);
        box.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        TextView title = new TextView(this);
        title.setText(R.string.setup_title);
        title.setTextSize(24);
        title.setTextColor(0xFF1E3A8A);
        box.addView(title);

        TextView help = new TextView(this);
        help.setText(R.string.setup_help);
        help.setPadding(0, 24, 0, 24);
        box.addView(help);

        input = new EditText(this);
        input.setHint(R.string.setup_hint);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        input.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        input.setOnEditorActionListener((v, action, e) -> { connect(); return true; });
        box.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        error = new TextView(this);
        error.setTextColor(0xFFB91C1C);
        error.setPadding(0, 16, 0, 0);
        box.addView(error);

        Button go = new Button(this);
        go.setText(R.string.setup_connect);
        go.setOnClickListener(v -> connect());
        box.addView(go);
        return box;
    }

    private void showSetup(String message) {
        input.setText(prefs().getString(KEY_URL, ""));
        error.setText(message == null ? "" : message);
        setup.setVisibility(View.VISIBLE);
    }

    private void connect() {
        String url = input.getText().toString().trim();
        if (url.isEmpty()) return;
        if (!url.contains("://")) url = "http://" + url;
        Uri u = Uri.parse(url);
        if (u.getHost() == null || !(u.getScheme().equals("http") || u.getScheme().equals("https"))) {
            error.setText(R.string.setup_bad_url);
            return;
        }
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        prefs().edit().putString(KEY_URL, url).apply();
        setup.setVisibility(View.GONE);
        web.loadUrl(url + "/map.html");
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == REQ_LOCATION && pendingGeo != null) {
            boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            pendingGeo.invoke(pendingGeoOrigin, ok, false);
            pendingGeo = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (setup.getVisibility() == View.VISIBLE && web.getUrl() != null) {
            setup.setVisibility(View.GONE);
        } else if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
