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

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Wakes the GitHub Codespace automatically on app launch.
 *
 * Flow:
 *  1. Read saved Codespace name from SharedPreferences.
 *  2. If no name saved yet, skip straight to UrlEntryActivity (first-time user).
 *  3. Extract GitHub token from WebView cookies (user_session → use cookie-based API call).
 *  4. POST to GitHub Codespaces API to start the Codespace.
 *  5. Poll GET status until state == "Available" (or timeout after 90 s).
 *  6. Launch UrlEntryActivity with the saved VNC URL.
 *
 * The GitHub Codespaces REST API is used with the session cookie as auth.
 * Because GitHub's /api/v2 accepts cookie-based auth from the same WebView session,
 * we make the API calls inside a hidden WebView using fetch() + a JS bridge.
 */
public class CodespacesWakeActivity extends Activity {

    private static final String PREFS_NAME        = "VncPrefs";
    private static final String PREF_VNC_URL      = "last_vnc_url";
    private static final String PREF_CODESPACE    = "codespace_name";

    private static final int POLL_INTERVAL_MS     = 4000;
    private static final int MAX_POLL_ATTEMPTS    = 30;   // 30 × 4 s = 120 s max

    private TextView  statusText;
    private TextView  subText;
    private ProgressBar spinner;
    private WebView   hiddenWebView;

    private final Handler          uiHandler    = new Handler(Looper.getMainLooper());
    private final ExecutorService  executor     = Executors.newSingleThreadExecutor();

    private int     pollAttempts = 0;
    private boolean done         = false;

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ── UI ────────────────────────────────────────────────────────────────
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0d1117"));
        root.setGravity(Gravity.CENTER);
        root.setPadding(64, 0, 64, 0);

        // Logo / emoji
        TextView logo = new TextView(this);
        logo.setText("☁️");
        logo.setTextSize(56f);
        logo.setGravity(Gravity.CENTER);
        logo.setPadding(0, 0, 0, 32);

        // Title
        TextView title = new TextView(this);
        title.setText("Codespaces VNC");
        title.setTextSize(22f);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);

        // Status line (changes as we wake the codespace)
        statusText = new TextView(this);
        statusText.setText("Starting your Codespace…");
        statusText.setTextSize(15f);
        statusText.setTextColor(Color.parseColor("#58a6ff"));
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(0, 24, 0, 8);

        // Sub-status
        subText = new TextView(this);
        subText.setText("This usually takes 10–30 seconds");
        subText.setTextSize(13f);
        subText.setTextColor(Color.parseColor("#8b949e"));
        subText.setGravity(Gravity.CENTER);

        // Spinner
        spinner = new ProgressBar(this);
        LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        spinnerParams.setMargins(0, 40, 0, 0);
        spinner.setLayoutParams(spinnerParams);

        root.addView(logo);
        root.addView(title);
        root.addView(statusText);
        root.addView(subText);
        root.addView(spinner);
        setContentView(root);

        // ── Hidden WebView (carries GitHub cookies for API calls) ─────────────
        hiddenWebView = new WebView(this);
        WebSettings ws = hiddenWebView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setUserAgentString(
                "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36");
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(hiddenWebView, true);

        // Add JS bridge so WebView can call back into Java
        hiddenWebView.addJavascriptInterface(new JsBridge(), "AndroidBridge");

        // ── Start the wake flow ───────────────────────────────────────────────
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String codespaceName = prefs.getString(PREF_CODESPACE, "");
        String vncUrl        = prefs.getString(PREF_VNC_URL, "");

        if (codespaceName.isEmpty()) {
            // First time — derive name from VNC URL if possible, else skip wake
            codespaceName = deriveCodespaceName(vncUrl);
            if (!codespaceName.isEmpty()) {
                prefs.edit().putString(PREF_CODESPACE, codespaceName).apply();
            }
        }

        if (codespaceName.isEmpty()) {
            // No saved codespace — go straight to URL entry
            setStatus("No Codespace saved yet", "Opening setup screen…");
            uiHandler.postDelayed(this::proceedToUrlEntry, 1200);
            return;
        }

        // Check GitHub login first
        String cookies = CookieManager.getInstance().getCookie("https://github.com");
        if (cookies == null || !cookies.contains("user_session")) {
            // Not logged in — go to LoginActivity
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        // Load github.com in hidden WebView so cookies are set in its context,
        // then trigger the wake via JS fetch
        final String finalName = codespaceName;
        hiddenWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (url.contains("github.com")) {
                    triggerCodespaceStart(finalName);
                }
            }
        });
        hiddenWebView.loadUrl("https://github.com/codespaces");
    }

    // ── Wake logic ────────────────────────────────────────────────────────────

    /**
     * Injects a fetch() POST call into the hidden WebView to start the Codespace.
     * The WebView already has the user's GitHub session cookies, so the call is authenticated.
     */
    private void triggerCodespaceStart(String name) {
        setStatus("Waking up " + name + "…", "Sending start signal to GitHub");

        // language=JavaScript
        String js =
            "fetch('https://api.github.com/user/codespaces/" + name + "/start', {" +
            "  method: 'POST'," +
            "  headers: {" +
            "    'Accept': 'application/vnd.github+json'," +
            "    'X-GitHub-Api-Version': '2022-11-28'" +
            "  }," +
            "  credentials: 'include'" +
            "})" +
            ".then(r => r.json())" +
            ".then(d => AndroidBridge.onStartResult(JSON.stringify(d)))" +
            ".catch(e => AndroidBridge.onStartError(e.toString()));";

        hiddenWebView.evaluateJavascript(js, null);
    }

    /** Poll Codespace state until Available */
    private void pollCodespaceState(String name) {
        if (done) return;
        if (pollAttempts >= MAX_POLL_ATTEMPTS) {
            // Timed out — just proceed anyway (instance might be reachable)
            setStatus("Taking longer than usual…", "Proceeding to VNC");
            uiHandler.postDelayed(this::proceedToUrlEntry, 1500);
            return;
        }
        pollAttempts++;
        setStatus("Waiting for Codespace to start…",
                  "Attempt " + pollAttempts + " / " + MAX_POLL_ATTEMPTS);

        // language=JavaScript
        String js =
            "fetch('https://api.github.com/user/codespaces/" + name + "', {" +
            "  headers: {" +
            "    'Accept': 'application/vnd.github+json'," +
            "    'X-GitHub-Api-Version': '2022-11-28'" +
            "  }," +
            "  credentials: 'include'" +
            "})" +
            ".then(r => r.json())" +
            ".then(d => AndroidBridge.onPollResult(JSON.stringify(d)))" +
            ".catch(e => AndroidBridge.onPollError(e.toString()));";

        hiddenWebView.evaluateJavascript(js, null);
    }

    // ── JS Bridge (called from WebView JavaScript) ────────────────────────────

    private class JsBridge {

        @JavascriptInterface
        public void onStartResult(String json) {
            uiHandler.post(() -> {
                try {
                    JSONObject obj = new JSONObject(json);
                    String state = obj.optString("state", "");
                    if ("Available".equalsIgnoreCase(state)) {
                        onCodespaceReady();
                    } else {
                        // Start was accepted; begin polling
                        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                        String name = prefs.getString(PREF_CODESPACE, "");
                        uiHandler.postDelayed(() -> pollCodespaceState(name), POLL_INTERVAL_MS);
                    }
                } catch (Exception e) {
                    // JSON parse failed — just poll
                    SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                    String name = prefs.getString(PREF_CODESPACE, "");
                    uiHandler.postDelayed(() -> pollCodespaceState(name), POLL_INTERVAL_MS);
                }
            });
        }

        @JavascriptInterface
        public void onStartError(String error) {
            uiHandler.post(() -> {
                // Could be 401 (token issue) or network error
                setStatus("Could not start Codespace automatically",
                          "Proceeding — please wake it manually if needed");
                uiHandler.postDelayed(CodespacesWakeActivity.this::proceedToUrlEntry, 2500);
            });
        }

        @JavascriptInterface
        public void onPollResult(String json) {
            uiHandler.post(() -> {
                try {
                    JSONObject obj   = new JSONObject(json);
                    String state     = obj.optString("state", "Unknown");
                    String codespace = obj.optString("name", "");

                    switch (state) {
                        case "Available":
                            onCodespaceReady();
                            break;
                        case "Starting":
                        case "Provisioning":
                        case "Queued":
                            setStatus("Codespace is " + state.toLowerCase() + "…",
                                      "Checking again in 4 seconds");
                            uiHandler.postDelayed(() -> pollCodespaceState(codespace.isEmpty()
                                    ? getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                                              .getString(PREF_CODESPACE, "")
                                    : codespace), POLL_INTERVAL_MS);
                            break;
                        case "Shutdown":
                        case "Stopped":
                            // Re-trigger start
                            setStatus("Codespace stopped — retrying start…", "");
                            triggerCodespaceStart(codespace.isEmpty()
                                    ? getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                                              .getString(PREF_CODESPACE, "")
                                    : codespace);
                            break;
                        default:
                            // Unknown state — keep polling
                            setStatus("Codespace state: " + state, "Waiting…");
                            String name = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                                    .getString(PREF_CODESPACE, "");
                            uiHandler.postDelayed(() -> pollCodespaceState(name), POLL_INTERVAL_MS);
                    }
                } catch (Exception e) {
                    String name = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            .getString(PREF_CODESPACE, "");
                    uiHandler.postDelayed(() -> pollCodespaceState(name), POLL_INTERVAL_MS);
                }
            });
        }

        @JavascriptInterface
        public void onPollError(String error) {
            uiHandler.post(() -> {
                String name = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .getString(PREF_CODESPACE, "");
                uiHandler.postDelayed(() -> pollCodespaceState(name), POLL_INTERVAL_MS);
            });
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void onCodespaceReady() {
        if (done) return;
        done = true;
        setStatus("Codespace is ready! ✓", "Launching VNC…");
        spinner.setVisibility(android.view.View.GONE);
        uiHandler.postDelayed(this::proceedToUrlEntry, 800);
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

    /**
     * Derive the Codespace name from a VNC URL like:
     *   https://myname-codespace-abc123-6080.app.github.dev/vnc.html
     * → name = "myname-codespace-abc123"  (everything before -6080)
     */
    private String deriveCodespaceName(String vncUrl) {
        if (vncUrl == null || vncUrl.isEmpty()) return "";
        try {
            // Strip https:// and take the host part
            String host = vncUrl.replace("https://", "").split("/")[0];
            // host = "myname-codespace-abc123-6080.app.github.dev"
            // Remove the .app.github.dev suffix
            String sub = host.replace(".app.github.dev", "");
            // Remove the trailing -<port>
            int lastDash = sub.lastIndexOf("-");
            if (lastDash > 0) {
                String portPart = sub.substring(lastDash + 1);
                if (portPart.matches("\\d+")) {
                    return sub.substring(0, lastDash);
                }
            }
            return sub;
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    protected void onDestroy() {
        done = true;
        if (hiddenWebView != null) hiddenWebView.destroy();
        executor.shutdownNow();
        super.onDestroy();
    }
}
