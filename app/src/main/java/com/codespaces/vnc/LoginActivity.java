package com.codespaces.vnc;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

public class LoginActivity extends Activity {

    // GitHub login URL — after login GitHub redirects back to github.com
    private static final String GITHUB_LOGIN_URL = "https://github.com/login";

    // We detect successful login by watching for these GitHub home URLs
    private static final String[] LOGGED_IN_INDICATORS = {
            "github.com/?",
            "github.com/dashboard",
            "github.com/codespaces",
            "github.com/notifications",
    };

    private WebView webView;
    private ProgressBar progressBar;
    private TextView statusText;
    private boolean launched = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Build UI programmatically — no XML layout needed
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0d1117")); // GitHub dark bg

        // Header bar
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

        // Progress bar
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 6));
        progressBar.setVisibility(View.VISIBLE);

        // WebView container
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

        // Persist cookies across sessions
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                if (newProgress == 100) {
                    progressBar.setVisibility(View.GONE);
                } else {
                    progressBar.setVisibility(View.VISIBLE);
                }
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                checkIfLoggedIn(url);
                return false; // let WebView handle it
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                checkIfLoggedIn(url);
            }
        });

        // Check if already logged in from a previous session
        CookieManager.getInstance().flush();
        String existingCookies = CookieManager.getInstance().getCookie("https://github.com");
        if (existingCookies != null && existingCookies.contains("user_session")) {
            // Already have a GitHub session — go straight to VNC
            launchVnc();
        } else {
            webView.loadUrl(GITHUB_LOGIN_URL);
        }
    }

    private void checkIfLoggedIn(String url) {
        if (launched) return;
        if (url == null) return;

        // Check for GitHub session cookie (set after successful login)
        String cookies = CookieManager.getInstance().getCookie("https://github.com");
        boolean hasCookie = cookies != null && cookies.contains("user_session");

        // Also check the URL — after login GitHub redirects away from /login
        boolean onPostLoginPage = false;
        for (String indicator : LOGGED_IN_INDICATORS) {
            if (url.contains(indicator)) {
                onPostLoginPage = true;
                break;
            }
        }

        // Logged in if we have the session cookie AND we're no longer on /login
        boolean notOnLoginPage = !url.contains("github.com/login") &&
                                 !url.contains("github.com/session") &&
                                 url.contains("github.com");

        if (hasCookie && notOnLoginPage) {
            statusText.setText("Logged in! Launching VNC...");
            launchVnc();
        }
    }

    private void launchVnc() {
        if (launched) return;
        launched = true;

        // Flush cookies so MainActivity WebView shares the same session
        CookieManager.getInstance().flush();

        Intent intent = new Intent(LoginActivity.this, UrlEntryActivity.class);
        startActivity(intent);
        finish(); // Remove login screen from back stack
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
