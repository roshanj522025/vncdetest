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
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Starts the user's Codespace using a GitHub PAT, then launches the VNC WebView.
 *
 * Auth: Authorization: Bearer <PAT>   (PAT with 'codespace' scope)
 *
 * Flow:
 *   1. GET  /user/codespaces          → pick codespace
 *   2. POST /user/codespaces/{name}/start  (if not Available)
 *   3. Poll GET /user/codespaces/{name}  every 3 s until Available
 *   4. Build VNC URL → launch MainActivity
 */
public class CodespaceStartActivity extends Activity {

    private static final String PREFS_NAME       = "VncPrefs";
    private static final String PREF_VNC_URL     = "last_vnc_url";
    private static final String PREF_CS_NAME     = "last_codespace_name";
    private static final String PREF_PAT         = "github_pat";

    private static final int POLL_INTERVAL_MS = 4000;
    private static final int MAX_POLLS        = 60;

    private final Handler          mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService  executor    = Executors.newSingleThreadExecutor();

    private TextView    statusText;
    private TextView    subText;
    private ProgressBar spinner;

    private boolean launched   = false;
    private int     pollCount  = 0;
    private String  codespaceName;
    private String  pat;
    private SharedPreferences prefs;

    // ──────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        buildUi();

        pat = prefs.getString(PREF_PAT, "");
        if (pat.isEmpty()) {
            goToPATEntry();
            return;
        }

        setStatus("Connecting to GitHub…", "Using Personal Access Token");
        executor.submit(this::listCodespaces);
    }

    // ── UI ────────────────────────────────────────────────────────────────────

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0d1117"));
        root.setGravity(Gravity.CENTER);
        root.setPadding(64, 0, 64, 0);

        TextView logo = new TextView(this);
        logo.setText("☁");
        logo.setTextSize(48f);
        logo.setTextColor(Color.parseColor("#58a6ff"));
        logo.setGravity(Gravity.CENTER);
        logo.setPadding(0, 0, 0, 24);

        TextView appTitle = new TextView(this);
        appTitle.setText("Codespaces VNC");
        appTitle.setTextSize(22f);
        appTitle.setTextColor(Color.WHITE);
        appTitle.setTypeface(null, Typeface.BOLD);
        appTitle.setGravity(Gravity.CENTER);

        statusText = new TextView(this);
        statusText.setText("Starting…");
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

        spinner = new ProgressBar(this, null, android.R.attr.progressBarStyleLarge);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.setMargins(0, 16, 0, 32);
        spinner.setLayoutParams(sp);

        // Change-token button (small, at the bottom)
        Button changePat = new Button(this);
        changePat.setText("Change Token");
        changePat.setTextColor(Color.parseColor("#8b949e"));
        changePat.setBackgroundColor(Color.parseColor("#161b22"));
        changePat.setTextSize(12f);
        changePat.setPadding(24, 12, 24, 12);
        changePat.setOnClickListener(v -> goToPATEntry());

        root.addView(logo);
        root.addView(appTitle);
        root.addView(statusText);
        root.addView(subText);
        root.addView(spinner);
        root.addView(changePat);
        setContentView(root);
    }

    private void setStatus(String status, String sub) {
        mainHandler.post(() -> {
            if (statusText != null) statusText.setText(status);
            if (sub != null && subText != null) subText.setText(sub);
        });
    }

    // ── API helpers ───────────────────────────────────────────────────────────

    /**
     * Makes a GitHub API call and returns the response body as a String.
     * Throws on non-2xx (so callers can catch and handle).
     */
    private ApiResult apiCall(String method, String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setRequestProperty("Authorization", "Bearer " + pat);
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(20_000);

        if ("POST".equals(method)) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Length", "0");
            try (OutputStream os = conn.getOutputStream()) { os.flush(); }
        }

        int code = conn.getResponseCode();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                code >= 400 ? conn.getErrorStream() : conn.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        conn.disconnect();
        return new ApiResult(code, sb.toString());
    }

    static class ApiResult {
        final int    status;
        final String body;
        ApiResult(int s, String b) { status = s; body = b; }
    }

    // ── Flow ──────────────────────────────────────────────────────────────────

    /** Step 1: list codespaces */
    private void listCodespaces() {
        setStatus("Fetching your Codespaces…", "");
        try {
            ApiResult r = apiCall("GET", "https://api.github.com/user/codespaces");

            if (r.status == 401 || r.status == 403) {
                mainHandler.post(this::handleBadToken);
                return;
            }
            if (r.status < 200 || r.status >= 300) {
                setStatus("API error " + r.status, r.body);
                return;
            }

            JSONObject root  = new JSONObject(r.body);
            JSONArray  list  = root.optJSONArray("codespaces");

            if (list == null || list.length() == 0) {
                setStatus("No Codespaces found.", "Create one at github.com/codespaces");
                return;
            }

            // Prefer previously used codespace
            String savedName = prefs.getString(PREF_CS_NAME, "");
            JSONObject cs = null;
            if (!savedName.isEmpty()) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject c = list.getJSONObject(i);
                    if (savedName.equals(c.optString("name"))) { cs = c; break; }
                }
            }
            if (cs == null) cs = list.getJSONObject(0);

            // If multiple codespaces and none saved, show picker
            if (cs == null && list.length() > 1) {
                showPicker(list);
                return;
            }

            pickCodespace(cs);

        } catch (Exception e) {
            setStatus("Network error", e.getMessage());
        }
    }

    private void pickCodespace(JSONObject cs) {
        try {
            codespaceName = cs.getString("name");
            prefs.edit().putString(PREF_CS_NAME, codespaceName).apply();
            String state  = cs.optString("state", "Unknown");

            if ("Available".equalsIgnoreCase(state)) {
                String vncUrl = buildVncUrl(cs);
                prefs.edit().putString(PREF_VNC_URL, vncUrl).apply();
                launchVnc(vncUrl);
            } else {
                startCodespace(codespaceName);
            }
        } catch (Exception e) {
            setStatus("Error", e.getMessage());
        }
    }

    /** Step 2: start codespace */
    private void startCodespace(String name) {
        setStatus("Starting: " + name, "Sending wake request…");
        executor.submit(() -> {
            try {
                ApiResult r = apiCall("POST",
                        "https://api.github.com/user/codespaces/" + name + "/start");

                if (r.status == 401 || r.status == 403) {
                    mainHandler.post(this::handleBadToken);
                    return;
                }
                // 200 = already available, 202 = starting — both fine
                mainHandler.postDelayed(this::doPoll, POLL_INTERVAL_MS);

            } catch (Exception e) {
                setStatus("Start error", e.getMessage());
            }
        });
    }

    /** Step 3: poll until Available */
    private void doPoll() {
        if (launched) return;
        if (pollCount >= MAX_POLLS) {
            setStatus("Timed out.", "Try again or open in browser first.");
            return;
        }
        pollCount++;
        executor.submit(() -> {
            try {
                ApiResult r = apiCall("GET",
                        "https://api.github.com/user/codespaces/" + codespaceName);

                if (r.status == 401 || r.status == 403) {
                    mainHandler.post(this::handleBadToken);
                    return;
                }

                JSONObject cs    = new JSONObject(r.body);
                String     state = cs.optString("state", "Unknown");
                int        secs  = pollCount * POLL_INTERVAL_MS / 1000;

                mainHandler.post(() ->
                    setStatus("Starting Codespace…", "State: " + state + "  (" + secs + "s)"));

                if ("Available".equalsIgnoreCase(state)) {
                    String vncUrl = buildVncUrl(cs);
                    prefs.edit().putString(PREF_VNC_URL, vncUrl).apply();
                    launchVnc(vncUrl);
                } else if ("Failed".equalsIgnoreCase(state) || "Deleted".equalsIgnoreCase(state)) {
                    setStatus("Codespace " + state + ".", "Check github.com/codespaces");
                } else {
                    mainHandler.postDelayed(this::doPoll, POLL_INTERVAL_MS);
                }

            } catch (Exception e) {
                mainHandler.postDelayed(this::doPoll, POLL_INTERVAL_MS);
            }
        });
    }

    // ── Picker (multiple codespaces) ──────────────────────────────────────────

    private void showPicker(JSONArray list) {
        mainHandler.post(() -> {
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

                    Button btn = new Button(this);
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
                    btn.setOnClickListener(v -> executor.submit(() -> pickCodespace(chosen)));
                    root.addView(btn);
                }
            } catch (Exception ignored) {}

            setContentView(root);
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildVncUrl(JSONObject cs) {
        try {
            String webUrl = cs.optString("web_url", "");
            if (!webUrl.isEmpty()) {
                String host = new java.net.URL(webUrl).getHost();
                String base = host.replace(".github.dev", "");
                return "https://" + base + "-6080.app.github.dev/vnc.html";
            }
        } catch (Exception ignored) {}
        return "https://" + codespaceName + "-6080.app.github.dev/vnc.html";
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

    private void handleBadToken() {
        setStatus("Authentication failed.", "Your PAT may be expired or missing the 'codespace' scope.");
        // Clear stored PAT so user is prompted to re-enter
        prefs.edit().remove(PREF_PAT).apply();
        mainHandler.postDelayed(this::goToPATEntry, 2000);
    }

    private void goToPATEntry() {
        startActivity(new Intent(this, PATEntryActivity.class));
        finish();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
