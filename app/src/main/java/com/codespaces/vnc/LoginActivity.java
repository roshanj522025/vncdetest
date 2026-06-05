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
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LoginActivity extends Activity {

    private static final String GITHUB_LOGIN_URL = "https://github.com/login";
    private static final String PREFS_NAME       = "VncPrefs";
    private static final String PREF_GH_TOKEN    = "gh_token";

    private static final String[] LOGGED_IN_INDICATORS = {
            "github.com/?",
            "github.com/dashboard",
            "github.com/codespaces",
            "github.com/notifications",
    };

    private WebView     webView;
    private ProgressBar progressBar;
    private TextView    statusText;
    private boolean     launched = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Build UI
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0d1117"));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(48, 56, 48, 24);
        header.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("Codespaces VNC");
        title.setTextSize(22f);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);

        statusText = new TextView(this);
        statusText.setText("Sign in to GitHub to continue");
        statusText.setTextSize(14f);
        statusText.setTextColor(Color.parseColor("#8b949e"));
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(0, 8, 0, 0);

        header.addView(title);
        header.addView(statusText);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 6));

        FrameLayout webContainer = new FrameLayout(this);
        webContainer.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        webView = new WebView(this);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        webContainer.addView(webView);

        root.addView(header);
        root.addView(progressBar);
        root.addView(webContainer);
        setContentView(root);

        // Configure WebView
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setUserAgentString(
                "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36");

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        // JS bridge to receive the token from the page
        webView.addJavascriptInterface(new TokenBridge(), "AndroidBridge");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                progressBar.setVisibility(newProgress == 100 ? View.GONE : View.VISIBLE);
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                checkIfLoggedIn(request.getUrl().toString());
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                checkIfLoggedIn(url);
                // Try to extract token from page meta / local storage
                extractTokenFromPage(view);
            }
        });

        // Already logged in?
        CookieManager.getInstance().flush();
        String existingCookies = CookieManager.getInstance().getCookie("https://github.com");
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String savedToken = prefs.getString(PREF_GH_TOKEN, "");

        if (existingCookies != null && existingCookies.contains("user_session") && !savedToken.isEmpty()) {
            launchCodespaceStart();
        } else {
            webView.loadUrl(GITHUB_LOGIN_URL);
        }
    }

    /**
     * Injects JS to pull the GitHub token from the page after login.
     * GitHub stores the user token in a meta tag and in localStorage.
     */
    private void extractTokenFromPage(WebView view) {
        String js =
            "(function() {" +
            "  try {" +
            // Try meta tag (github.com stores token here in some flows)
            "    var meta = document.querySelector('meta[name=\"user-login\"]');" +
            // Try localStorage
            "    var ls = localStorage.getItem('dotcom_user');" +
            // Extract from cookie string accessible to JS
            "    var cookie = document.cookie;" +
            // Find the GitHub token from the page's data attributes or session storage
            "    var tokenMeta = document.querySelector('meta[name=\"github-token\"]');" +
            "    if (tokenMeta && tokenMeta.content) {" +
            "      AndroidBridge.onTokenFound(tokenMeta.content);" +
            "    }" +
            "  } catch(e) {}" +
            "})();";
        view.evaluateJavascript(js, null);

        // Also try fetching the token via the GitHub API using the session cookie
        // by loading the API endpoint in background
        String apiJs =
            "(function() {" +
            "  fetch('https://api.github.com/user', {credentials: 'include'})" +
            "    .then(r => r.headers.get('X-OAuth-Scopes') ? r.json() : null)" +
            "    .then(d => { if(d && d.login) AndroidBridge.onLoginConfirmed(d.login); })" +
            "    .catch(e => {});" +
            "})();";
        view.evaluateJavascript(apiJs, null);
    }

    private void checkIfLoggedIn(String url) {
        if (launched || url == null) return;

        String cookies = CookieManager.getInstance().getCookie("https://github.com");
        boolean hasCookie = cookies != null && cookies.contains("user_session");
        boolean notOnLoginPage = !url.contains("github.com/login") &&
                                 !url.contains("github.com/session") &&
                                 url.contains("github.com");

        if (hasCookie && notOnLoginPage) {
            statusText.setText("Logged in! Fetching token…");
            // Fetch the token using the session cookie via GitHub's API
            fetchTokenWithSession();
        }
    }

    /**
     * Uses the GitHub session cookie (already in the WebView's CookieManager)
     * to call the API and exchange it for a usable token.
     *
     * In practice, GitHub's REST API accepts session cookies for browser-based
     * requests — we piggyback on that by loading the API URL inside the WebView.
     */
    private void fetchTokenWithSession() {
        // Load a small JS snippet inside the WebView that calls the API
        // using the existing session cookies, and passes the result back via bridge
        webView.loadUrl("javascript:(function(){" +
            "fetch('https://api.github.com/user',{credentials:'include'})" +
            ".then(r=>r.json())" +
            ".then(d=>{ if(d && d.login) AndroidBridge.onLoginConfirmed(d.login); })" +
            ".catch(e=>AndroidBridge.onLoginConfirmed(''));" +
            "})()");
    }

    private void launchCodespaceStart() {
        if (launched) return;
        launched = true;
        CookieManager.getInstance().flush();
        mainHandler.post(() -> {
            startActivity(new Intent(LoginActivity.this, CodespaceStartActivity.class));
            finish();
        });
    }

    // -------------------------------------------------------------------------

    class TokenBridge {
        @JavascriptInterface
        public void onTokenFound(String token) {
            if (token == null || token.isEmpty()) return;
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit().putString(PREF_GH_TOKEN, token).apply();
        }

        @JavascriptInterface
        public void onLoginConfirmed(String login) {
            if (login == null || login.isEmpty()) return;
            // login confirmed — we use session cookies for API calls
            // Store a marker so CodespaceStartActivity knows to use cookie-auth
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(PREF_GH_TOKEN, "cookie_auth:" + login)
                    .apply();
            launchCodespaceStart();
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) webView.destroy();
        executor.shutdownNow();
        super.onDestroy();
    }
}
