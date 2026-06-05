package com.codespaces.vnc;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Wakes the Codespace on app launch — no URL entry, no manual browser step.
 *
 * Auth strategy:
 *   GitHub's REST API (api.github.com) rejects session cookies.
 *   Instead we load github.com/codespaces in the WebView, extract the
 *   logged-in user's OAuth token that GitHub embeds in every page as
 *   `<meta name="octolytics-app-id">` / window.__github_token, then use
 *   that token as a Bearer header for all API calls.
 *
 *   If token extraction fails we fall back to GitHub's internal
 *   /user/codespaces Ajax endpoint which DOES accept cookie auth when
 *   called from the same origin (github.com loaded in the WebView).
 */
public class CodespacesWakeActivity extends Activity {

    private static final String PREFS_NAME     = "VncPrefs";
    private static final String PREF_VNC_URL   = "last_vnc_url";
    private static final String PREF_CODESPACE = "codespace_name";
    private static final String PREF_TOKEN     = "gh_token";

    private static final int POLL_INTERVAL_MS  = 4000;
    private static final int MAX_POLL_ATTEMPTS = 30;

    private TextView    statusText;
    private TextView    subText;
    private ProgressBar spinner;
    private WebView     webView;   // visible + active — carries GitHub session

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private int     pollAttempts = 0;
    private boolean done         = false;
    private String  ghToken      = "";   // extracted after page load

    // ── onCreate ──────────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        buildUi();

        // Check login cookie first
        String cookies = CookieManager.getInstance().getCookie("https://github.com");
        if (cookies == null || !cookies.contains("user_session")) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        // Check for a saved token from last session
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        ghToken = prefs.getString(PREF_TOKEN, "");

        setupWebView();

        setStatus("Connecting to GitHub…", "");
        // Load github.com/codespaces — same origin as the API, cookies apply.
        // We also extract the token from this page.
        webView.loadUrl("https://github.com/codespaces");
    }

    // ── WebView setup ─────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        webView = new WebView(this);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setUserAgentString(
                "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36");
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        webView.addJavascriptInterface(new JsBridge(), "AndroidBridge");

        webView.setWebViewClient(new WebViewClient() {
            boolean extracted = false;

            @Override
            public void onPageFinished(WebView view, String url) {
                if (extracted) return;
                if (url.contains("github.com/login")) {
                    // Session expired
                    startActivity(new Intent(CodespacesWakeActivity.this, LoginActivity.class));
                    finish();
                    return;
                }
                if (url.contains("github.com")) {
                    extracted = true;
                    extractTokenThenList();
                }
            }
        });

        // Keep WebView hidden — we only need it for JS execution
        webView.setVisibility(android.view.View.GONE);
    }

    // ── Step 1: Extract token from page, then list codespaces ─────────────────

    /**
     * GitHub embeds a scoped token in every page it serves to authenticated
     * users. We read it from:
     *   1. The <meta name="user-scopes"> / data attributes on #js-pjax-container
     *   2. window.__github_token  (older pages)
     *   3. The `authenticity_token` hidden input in forms
     *
     * Once we have it we call the REST API with Bearer auth.
     * If extraction yields nothing we call via same-origin fetch (cookie auth).
     */
    private void extractTokenThenList() {
        setStatus("Fetching your Codespaces…", "");

        // language=JavaScript
        String js =
            "(function() {" +
            // Try 1: GitHub's React/Relay bootstrap data contains a viewer token
            "  try {" +
            "    var scripts = document.querySelectorAll('script[data-target]');" +
            "    for (var s of scripts) {" +
            "      var t = s.getAttribute('data-token');" +
            "      if (t && t.length > 10) { AndroidBridge.onToken(t); return; }" +
            "    }" +
            "  } catch(e) {}" +
            // Try 2: meta[name=user-login] doesn't give token, but look for
            //        the __github_token global some versions expose
            "  try {" +
            "    if (window.__github_token) { AndroidBridge.onToken(window.__github_token); return; }" +
            "  } catch(e) {}" +
            // Try 3: GitHub bakes a token into the page bootstrap JSON
            "  try {" +
            "    var el = document.getElementById('js-pjax-loader-bar');" +
            "    if (el && el.dataset.token) { AndroidBridge.onToken(el.dataset.token); return; }" +
            "  } catch(e) {}" +
            // Try 4: Look for the GitHub token in the inline __NEXT_DATA__ or
            //        bootstrap-data script tags (newer github.com)
            "  try {" +
            "    var allScripts = document.querySelectorAll('script');" +
            "    for (var sc of allScripts) {" +
            "      var txt = sc.innerText || sc.textContent;" +
            "      if (!txt) continue;" +
            "      var m = txt.match(/['\"]token['\"]:['\"](ghu_[A-Za-z0-9]+)['\"]/);" +
            "      if (!m) m = txt.match(/['\"]token['\"]:['\"](ghp_[A-Za-z0-9]+)['\"]/);" +
            "      if (m) { AndroidBridge.onToken(m[1]); return; }" +
            "    }" +
            "  } catch(e) {}" +
            // No token found — proceed with cookie-only auth (same-origin)
            "  AndroidBridge.onToken('');" +
            "})();";

        webView.evaluateJavascript(js, null);
    }

    /**
     * List codespaces.
     * - If we have a token → use Authorization: Bearer (works from any origin)
     * - If no token → use same-origin fetch from the github.com WebView context
     *   (cookie auth works because the WebView IS on github.com)
     */
    private void fetchCodespacesList() {
        // language=JavaScript
        String headers = ghToken.isEmpty()
                ? "'Accept':'application/vnd.github+json','X-GitHub-Api-Version':'2022-11-28'"
                : "'Accept':'application/vnd.github+json','X-GitHub-Api-Version':'2022-11-28','Authorization':'Bearer " + ghToken + "'";

        String js =
            "fetch('https://api.github.com/user/codespaces?per_page=20', {" +
            "  headers: {" + headers + "}," +
            "  credentials: 'include'" +
            "})" +
            ".then(r => r.json())" +
            ".then(d => AndroidBridge.onCodespacesList(JSON.stringify(d)))" +
            ".catch(e => AndroidBridge.onError('list', e.toString()));";

        webView.evaluateJavascript(js, null);
    }

    // ── Step 2: Start codespace ───────────────────────────────────────────────

    private void startCodespace(String name) {
        setStatus("Waking up Codespace…", name);

        String headers = ghToken.isEmpty()
                ? "'Accept':'application/vnd.github+json','X-GitHub-Api-Version':'2022-11-28'"
                : "'Accept':'application/vnd.github+json','X-GitHub-Api-Version':'2022-11-28','Authorization':'Bearer " + ghToken + "'";

        String js =
            "fetch('https://api.github.com/user/codespaces/" + name + "/start', {" +
            "  method: 'POST'," +
            "  headers: {" + headers + "}," +
            "  credentials: 'include'" +
            "})" +
            ".then(r => r.json())" +
            ".then(d => AndroidBridge.onStartResult(JSON.stringify(d)))" +
            ".catch(e => AndroidBridge.onError('start', e.toString()));";

        webView.evaluateJavascript(js, null);
    }

    // ── Step 3: Poll ──────────────────────────────────────────────────────────

    private void pollState(String name) {
        if (done) return;
        if (pollAttempts >= MAX_POLL_ATTEMPTS) {
            String url = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getString(PREF_VNC_URL, "");
            if (!url.isEmpty()) launchVnc(url);
            else proceedToUrlEntry();
            return;
        }
        pollAttempts++;
        setStatus("Starting Codespace…", "Check " + pollAttempts + " of " + MAX_POLL_ATTEMPTS);

        String headers = ghToken.isEmpty()
                ? "'Accept':'application/vnd.github+json','X-GitHub-Api-Version':'2022-11-28'"
                : "'Accept':'application/vnd.github+json','X-GitHub-Api-Version':'2022-11-28','Authorization':'Bearer " + ghToken + "'";

        String js =
            "fetch('https://api.github.com/user/codespaces/" + name + "', {" +
            "  headers: {" + headers + "}," +
            "  credentials: 'include'" +
            "})" +
            ".then(r => r.json())" +
            ".then(d => AndroidBridge.onPollResult(JSON.stringify(d)))" +
            ".catch(e => AndroidBridge.onError('poll', e.toString()));";

        webView.evaluateJavascript(js, null);
    }

    // ── JS Bridge ─────────────────────────────────────────────────────────────

    private class JsBridge {

        @JavascriptInterface
        public void onToken(String token) {
            uiHandler.post(() -> {
                if (token != null && token.length() > 10) {
                    ghToken = token;
                    // Persist for next session
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                            .putString(PREF_TOKEN, token).apply();
                }
                // Whether we got a token or not, proceed to list
                fetchCodespacesList();
            });
        }

        @JavascriptInterface
        public void onCodespacesList(String json) {
            uiHandler.post(() -> {
                try {
                    JSONObject root = new JSONObject(json);

                    // Check for 401 — token stale or cookie-only auth failed
                    if (root.has("message")) {
                        String msg = root.optString("message", "");
                        if (msg.contains("Bad credentials") || msg.contains("401")
                                || msg.contains("Requires authentication")) {
                            // Clear stale token and retry via GitHub's internal endpoint
                            ghToken = "";
                            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                                    .remove(PREF_TOKEN).apply();
                            retryViaInternalApi();
                            return;
                        }
                    }

                    JSONArray list = root.optJSONArray("codespaces");
                    if (list == null || list.length() == 0) {
                        setStatus("No Codespaces found",
                                  "Create one at github.com/codespaces");
                        return;
                    }

                    if (list.length() == 1) {
                        pickCodespace(list.getJSONObject(0));
                    } else {
                        SharedPreferences prefs =
                                getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                        String saved = prefs.getString(PREF_CODESPACE, "");
                        if (!saved.isEmpty()) {
                            for (int i = 0; i < list.length(); i++) {
                                JSONObject cs = list.getJSONObject(i);
                                if (saved.equals(cs.optString("name"))) {
                                    pickCodespace(cs);
                                    return;
                                }
                            }
                        }
                        showCodespacePicker(list);
                    }
                } catch (Exception e) {
                    setStatus("Error reading Codespaces", e.getMessage());
                }
            });
        }

        @JavascriptInterface
        public void onStartResult(String json) {
            uiHandler.post(() -> {
                try {
                    JSONObject obj = new JSONObject(json);
                    if (obj.has("message")) {
                        // API error — may need re-auth
                        String msg = obj.optString("message");
                        setStatus("Start failed: " + msg, "Retrying in 3s…");
                        String name = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                                .getString(PREF_CODESPACE, "");
                        uiHandler.postDelayed(() -> pollState(name), 3000);
                        return;
                    }
                    String state = obj.optString("state", "");
                    String name  = obj.optString("name", "");
                    if ("Available".equalsIgnoreCase(state)) {
                        readyToLaunch(obj);
                    } else {
                        uiHandler.postDelayed(() -> pollState(name), POLL_INTERVAL_MS);
                    }
                } catch (Exception e) {
                    String name = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            .getString(PREF_CODESPACE, "");
                    uiHandler.postDelayed(() -> pollState(name), POLL_INTERVAL_MS);
                }
            });
        }

        @JavascriptInterface
        public void onPollResult(String json) {
            uiHandler.post(() -> {
                try {
                    JSONObject obj = new JSONObject(json);
                    String state = obj.optString("state", "Unknown");
                    String name  = obj.optString("name",
                            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                                    .getString(PREF_CODESPACE, ""));

                    switch (state) {
                        case "Available":
                            readyToLaunch(obj);
                            break;
                        case "Starting":
                        case "Provisioning":
                        case "Queued":
                            setStatus("Codespace is " + state.toLowerCase() + "…",
                                      "Check " + pollAttempts + " of " + MAX_POLL_ATTEMPTS);
                            uiHandler.postDelayed(() -> pollState(name), POLL_INTERVAL_MS);
                            break;
                        case "Shutdown":
                        case "Stopped":
                            startCodespace(name);
                            break;
                        default:
                            uiHandler.postDelayed(() -> pollState(name), POLL_INTERVAL_MS);
                    }
                } catch (Exception e) {
                    String name = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            .getString(PREF_CODESPACE, "");
                    uiHandler.postDelayed(() -> pollState(name), POLL_INTERVAL_MS);
                }
            });
        }

        @JavascriptInterface
        public void onInternalApiResult(String json) {
            uiHandler.post(() -> {
                try {
                    // GitHub's internal /codespaces JSON returns { codespaces: [...] }
                    JSONObject root = new JSONObject(json);
                    JSONArray list = root.optJSONArray("codespaces");
                    if (list == null) list = root.optJSONArray("items");
                    if (list == null || list.length() == 0) {
                        setStatus("No Codespaces found", "");
                        return;
                    }
                    if (list.length() == 1) {
                        pickCodespace(list.getJSONObject(0));
                    } else {
                        showCodespacePicker(list);
                    }
                } catch (Exception e) {
                    setStatus("Could not load Codespaces", e.getMessage());
                }
            });
        }

        @JavascriptInterface
        public void onError(String step, String error) {
            uiHandler.post(() -> {
                if ("list".equals(step) && !ghToken.isEmpty()) {
                    // Token may be stale — retry without it
                    ghToken = "";
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                            .remove(PREF_TOKEN).apply();
                    retryViaInternalApi();
                    return;
                }
                setStatus("Error (" + step + ")", "Proceeding…");
                uiHandler.postDelayed(() -> {
                    String url = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            .getString(PREF_VNC_URL, "");
                    if (!url.isEmpty()) launchVnc(url);
                    else proceedToUrlEntry();
                }, 2000);
            });
        }
    }

    /**
     * Fallback: use GitHub's internal AJAX endpoint for the codespaces page.
     * This endpoint is same-origin to github.com and accepts the session cookie.
     * It's the same call the browser page makes to populate the codespaces list.
     */
    private void retryViaInternalApi() {
        setStatus("Fetching Codespaces…", "(using session auth)");
        // language=JavaScript
        String js =
            "fetch('https://github.com/codespaces/search?type=all&sort=created_at&per_page=20', {" +
            "  headers: {" +
            "    'Accept': 'application/json'," +
            "    'X-Requested-With': 'XMLHttpRequest'" +
            "  }," +
            "  credentials: 'include'" +
            "})" +
            ".then(r => r.json())" +
            ".then(d => AndroidBridge.onInternalApiResult(JSON.stringify(d)))" +
            ".catch(e => {" +
            // Last resort: re-parse the already-loaded page for JSON embedded in a script tag
            "  try {" +
            "    var scripts = document.querySelectorAll('script[type=\"application/json\"]');" +
            "    for (var s of scripts) {" +
            "      var t = s.textContent;" +
            "      if (t && t.includes('codespaces')) {" +
            "        AndroidBridge.onInternalApiResult(t); return;" +
            "      }" +
            "    }" +
            "  } catch(e2) {}" +
            "  AndroidBridge.onError('internal', e.toString());" +
            "});";

        webView.evaluateJavascript(js, null);
    }

    // ── Picker UI ─────────────────────────────────────────────────────────────

    private void showCodespacePicker(JSONArray list) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0d1117"));
        root.setPadding(48, 80, 48, 48);

        TextView title = new TextView(this);
        title.setText("Choose a Codespace");
        title.setTextSize(20f);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 40);
        root.addView(title);

        try {
            for (int i = 0; i < list.length(); i++) {
                JSONObject cs    = list.getJSONObject(i);
                String     name  = cs.optString("name", "");
                JSONObject repo  = cs.optJSONObject("repository");
                String     label = repo != null
                        ? repo.optString("full_name", name) : name;
                String     state = cs.optString("state", "");

                android.widget.Button btn = new android.widget.Button(this);
                btn.setText(label + "\n" + state);
                btn.setTextColor(Color.WHITE);
                btn.setBackgroundColor(Color.parseColor("#161b22"));
                btn.setPadding(32, 24, 32, 24);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMargins(0, 12, 0, 0);
                btn.setLayoutParams(lp);
                final JSONObject chosen = cs;
                btn.setOnClickListener(v -> pickCodespace(chosen));
                root.addView(btn);
            }
        } catch (Exception ignored) {}

        setContentView(root);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void pickCodespace(JSONObject cs) {
        try {
            String name  = cs.optString("name", "");
            String state = cs.optString("state", "");
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putString(PREF_CODESPACE, name).apply();
            if ("Available".equalsIgnoreCase(state)) {
                readyToLaunch(cs);
            } else {
                startCodespace(name);
            }
        } catch (Exception e) {
            proceedToUrlEntry();
        }
    }

    private void readyToLaunch(JSONObject cs) {
        if (done) return;
        done = true;
        try {
            String name   = cs.optString("name", "");
            String vncUrl = "https://" + name + "-6080.app.github.dev/vnc.html";
            setStatus("Codespace ready! ✓", "Launching VNC…");
            spinner.setVisibility(android.view.View.GONE);
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putString(PREF_VNC_URL, vncUrl)
                    .putString(PREF_CODESPACE, name)
                    .apply();
            uiHandler.postDelayed(() -> launchVnc(vncUrl), 600);
        } catch (Exception e) {
            proceedToUrlEntry();
        }
    }

    private void launchVnc(String url) {
        if (isDestroyed()) return;
        Intent i = new Intent(this, MainActivity.class);
        i.putExtra(MainActivity.EXTRA_VNC_URL, url);
        startActivity(i);
        finish();
    }

    private void proceedToUrlEntry() {
        if (isDestroyed()) return;
        startActivity(new Intent(this, UrlEntryActivity.class));
        finish();
    }

    private void setStatus(String main, String sub) {
        uiHandler.post(() -> {
            statusText.setText(main);
            subText.setText(sub);
        });
    }

    // ── UI builder ────────────────────────────────────────────────────────────

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0d1117"));
        root.setGravity(Gravity.CENTER);
        root.setPadding(64, 0, 64, 0);

        TextView logo = new TextView(this);
        logo.setText("☁️");
        logo.setTextSize(56f);
        logo.setGravity(Gravity.CENTER);
        logo.setPadding(0, 0, 0, 32);

        TextView title = new TextView(this);
        title.setText("Codespaces VNC");
        title.setTextSize(22f);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);

        statusText = new TextView(this);
        statusText.setText("Connecting…");
        statusText.setTextSize(15f);
        statusText.setTextColor(Color.parseColor("#58a6ff"));
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(0, 24, 0, 8);

        subText = new TextView(this);
        subText.setText("");
        subText.setTextSize(13f);
        subText.setTextColor(Color.parseColor("#8b949e"));
        subText.setGravity(Gravity.CENTER);

        spinner = new ProgressBar(this);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.setMargins(0, 40, 0, 0);
        spinner.setLayoutParams(sp);

        root.addView(logo);
        root.addView(title);
        root.addView(statusText);
        root.addView(subText);
        root.addView(spinner);
        setContentView(root);
    }

    @Override
    protected void onDestroy() {
        done = true;
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
