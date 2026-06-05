package com.codespaces.vnc;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * Auto-starts the user's Codespace via the GitHub API, then launches the VNC view.
 *
 * Auth strategy:
 *   GitHub's REST API accepts session cookies for first-party web requests.
 *   We use a hidden WebView to make credentialed fetch() calls, which automatically
 *   attach the github.com session cookies captured during login.
 *
 * Flow:
 *   1. GET /user/codespaces  → pick the right codespace
 *   2. POST /user/codespaces/{name}/start  (if not already Available)
 *   3. Poll GET /user/codespaces/{name} every 3s until state == "Available"
 *   4. Build VNC URL → launch MainActivity
 */
public class CodespaceStartActivity extends Activity {

    private static final String PREFS_NAME    = "VncPrefs";
    private static final String PREF_VNC_URL  = "last_vnc_url";
    private static final String PREF_CS_NAME  = "last_codespace_name";
    private static final String PREF_GH_TOKEN = "gh_token";

    private static final int POLL_INTERVAL_MS = 3000;
    private static final int MAX_POLLS        = 60;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private TextView    statusText;
    private TextView    subText;
    private WebView     apiWebView;   // hidden; used for credentialed fetch()
    private boolean     launched = false;
    private int         pollCount = 0;
    private String      codespaceName;
    private SharedPreferences prefs;

    // -------------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        buildUi();
        setupApiWebView();
        beginFlow();
    }

    // -------------------------------------------------------------------------
    // UI
    // -------------------------------------------------------------------------

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0d1117"));
        root.setGravity(Gravity.CENTER);
        root.setPadding(64, 0, 64, 0);

        TextView appTitle = new TextView(this);
        appTitle.setText("Codespaces VNC");
        appTitle.setTextSize(22f);
        appTitle.setTextColor(Color.WHITE);
        appTitle.setTypeface(null, Typeface.BOLD);
        appTitle.setGravity(Gravity.CENTER);

        statusText = new TextView(this);
        statusText.setText("Connecting to GitHub…");
        statusText.setTextSize(16f);
        statusText.setTextColor(Color.parseColor("#58a6ff"));
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(0, 32, 0, 8);

        subText = new TextView(this);
        subText.setText("Waking your Codespace — this may take up to a minute.");
        subText.setTextSize(13f);
        subText.setTextColor(Color.parseColor("#8b949e"));
        subText.setGravity(Gravity.CENTER);
        subText.setPadding(0, 0, 0, 32);

        ProgressBar spinner = new ProgressBar(this, null, android.R.attr.progressBarStyleLarge);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, 16, 0, 0);
        spinner.setLayoutParams(p);

        root.addView(appTitle);
        root.addView(statusText);
        root.addView(subText);
        root.addView(spinner);
        setContentView(root);
    }

    private void setStatus(String status, String sub) {
        mainHandler.post(() -> {
            statusText.setText(status);
            if (sub != null) subText.setText(sub);
        });
    }

    // -------------------------------------------------------------------------
    // Hidden API WebView — makes credentialed fetch() calls using session cookies
    // -------------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private void setupApiWebView() {
        apiWebView = new WebView(this);
        WebSettings s = apiWebView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUserAgentString(
                "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36");

        // Share cookies with the login WebView
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(apiWebView, true);

        apiWebView.addJavascriptInterface(new ApiBridge(), "API");
        // Load github.com so fetch() requests share its origin cookies
        apiWebView.loadUrl("https://github.com/codespaces");
    }

    // -------------------------------------------------------------------------
    // Flow
    // -------------------------------------------------------------------------

    private void beginFlow() {
        // Give the hidden WebView a moment to load cookies, then start
        mainHandler.postDelayed(this::listCodespaces, 2500);
    }

    /** Step 1: list codespaces */
    private void listCodespaces() {
        setStatus("Fetching your Codespaces…", null);
        runFetch("GET", "https://api.github.com/user/codespaces", "list");
    }

    /** Step 2: start the codespace */
    private void startCodespace(String name) {
        codespaceName = name;
        prefs.edit().putString(PREF_CS_NAME, name).apply();
        setStatus("Waking up: " + name, "Sending start request…");
        runFetch("POST", "https://api.github.com/user/codespaces/" + name + "/start", "start");
    }

    /** Step 3: poll state */
    private void pollState() {
        runFetch("GET", "https://api.github.com/user/codespaces/" + codespaceName, "poll");
    }

    // -------------------------------------------------------------------------
    // JS bridge — receives API responses from the hidden WebView
    // -------------------------------------------------------------------------

    class ApiBridge {

        @JavascriptInterface
        public void onResponse(String tag, int status, String body) {
            mainHandler.post(() -> handleResponse(tag, status, body));
        }

        @JavascriptInterface
        public void onError(String tag, String error) {
            mainHandler.post(() -> {
                if ("list".equals(tag) || "start".equals(tag)) {
                    // API call failed — fall back to saved URL
                    fallbackToSavedUrl();
                } else {
                    setStatus("Network error: " + error, "Retrying…");
                    mainHandler.postDelayed(CodespaceStartActivity.this::pollState, POLL_INTERVAL_MS);
                }
            });
        }
    }

    private void handleResponse(String tag, int status, String body) {
        if (status == 401 || status == 403) {
            goToLogin();
            return;
        }

        try {
            switch (tag) {
                case "list":
                    handleList(body);
                    break;
                case "start":
                    // Start returns 200 (already running) or 202 (starting)
                    // Either way, begin polling
                    mainHandler.postDelayed(this::pollState, POLL_INTERVAL_MS);
                    break;
                case "poll":
                    handlePoll(body);
                    break;
            }
        } catch (Exception e) {
            setStatus("Parse error: " + e.getMessage(), null);
        }
    }

    private void handleList(String body) throws Exception {
        org.json.JSONObject root = new org.json.JSONObject(body);
        org.json.JSONArray list = root.optJSONArray("codespaces");

        if (list == null || list.length() == 0) {
            setStatus("No Codespaces found.", "Create one at github.com/codespaces");
            return;
        }

        // Prefer previously used codespace
        String savedName = prefs.getString(PREF_CS_NAME, "");
        org.json.JSONObject cs = null;

        if (!savedName.isEmpty()) {
            for (int i = 0; i < list.length(); i++) {
                org.json.JSONObject c = list.getJSONObject(i);
                if (savedName.equals(c.optString("name"))) { cs = c; break; }
            }
        }
        if (cs == null) cs = list.getJSONObject(0);

        String name  = cs.getString("name");
        String state = cs.optString("state", "Unknown");
        String webUrl = cs.optString("web_url", "");

        codespaceName = name;
        prefs.edit().putString(PREF_CS_NAME, name).apply();

        if ("Available".equalsIgnoreCase(state)) {
            String vncUrl = buildVncUrl(name, webUrl);
            prefs.edit().putString(PREF_VNC_URL, vncUrl).apply();
            launchVnc(vncUrl);
        } else {
            startCodespace(name);
        }
    }

    private void handlePoll(String body) throws Exception {
        org.json.JSONObject cs = new org.json.JSONObject(body);
        String state  = cs.optString("state", "Unknown");
        String webUrl = cs.optString("web_url", "");
        pollCount++;

        int elapsed = pollCount * POLL_INTERVAL_MS / 1000;
        setStatus("Starting Codespace…", "State: " + state + "  (" + elapsed + "s)");

        if ("Available".equalsIgnoreCase(state)) {
            String vncUrl = buildVncUrl(codespaceName, webUrl);
            prefs.edit().putString(PREF_VNC_URL, vncUrl).apply();
            launchVnc(vncUrl);
            return;
        }

        if ("Failed".equalsIgnoreCase(state) || "Deleted".equalsIgnoreCase(state)) {
            setStatus("Codespace " + state + ".", "Please check github.com/codespaces");
            return;
        }

        if (pollCount >= MAX_POLLS) {
            setStatus("Timed out waiting for Codespace.", "Try again or open in browser first.");
            return;
        }

        mainHandler.postDelayed(this::pollState, POLL_INTERVAL_MS);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Executes a fetch() call inside the hidden WebView, using its session cookies.
     * The result is passed back via ApiBridge.onResponse / onError.
     */
    private void runFetch(String method, String url, String tag) {
        String js =
            "(function(){" +
            "  var opts = {method:'" + method + "',credentials:'include'," +
            "    headers:{'Accept':'application/vnd.github+json'," +
            "             'X-GitHub-Api-Version':'2022-11-28'}};" +
            (method.equals("POST") ? "  opts.body = '';" : "") +
            "  fetch('" + url + "', opts)" +
            "    .then(function(r){ var s=r.status; return r.text().then(function(b){" +
            "       API.onResponse('" + tag + "', s, b); }); })" +
            "    .catch(function(e){ API.onError('" + tag + "', e.toString()); });" +
            "})();";
        mainHandler.post(() -> apiWebView.evaluateJavascript(js, null));
    }

    private String buildVncUrl(String csName, String webUrl) {
        if (webUrl != null && !webUrl.isEmpty()) {
            try {
                String host = new java.net.URL(webUrl).getHost();
                String base = host.replace(".github.dev", "");
                return "https://" + base + "-6080.app.github.dev/vnc.html";
            } catch (Exception ignored) {}
        }
        return "https://" + csName + "-6080.app.github.dev/vnc.html";
    }

    private void fallbackToSavedUrl() {
        String savedUrl = prefs.getString(PREF_VNC_URL, "");
        if (!savedUrl.isEmpty()) {
            launchVnc(savedUrl);
        } else {
            mainHandler.post(() -> {
                startActivity(new Intent(this, UrlEntryActivity.class));
                finish();
            });
        }
    }

    private void launchVnc(String vncUrl) {
        if (launched) return;
        launched = true;
        setStatus("Launching VNC…", vncUrl);
        mainHandler.postDelayed(() -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra(MainActivity.EXTRA_VNC_URL, vncUrl);
            startActivity(intent);
            finish();
        }, 500);
    }

    private void goToLogin() {
        mainHandler.post(() -> {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
        });
    }

    @Override
    protected void onDestroy() {
        if (apiWebView != null) apiWebView.destroy();
        super.onDestroy();
    }
}
