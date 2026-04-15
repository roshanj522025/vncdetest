package com.codespaces.vnc;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Screen shown after GitHub login. The user pastes their Codespace VNC URL here.
 * The URL is saved so they don't have to re-enter it next time.
 *
 * Expected URL format:
 *   https://<codespace-name>-6080.app.github.dev/vnc.html
 */
public class UrlEntryActivity extends Activity {

    private static final String PREFS_NAME = "VncPrefs";
    private static final String PREF_VNC_URL = "last_vnc_url";

    private EditText urlInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0d1117"));
        root.setPadding(64, 80, 64, 48);

        // Title
        TextView title = new TextView(this);
        title.setText("Enter Codespace VNC URL");
        title.setTextSize(20f);
        title.setTextColor(Color.WHITE);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);

        // Subtitle
        TextView subtitle = new TextView(this);
        subtitle.setText("Paste the forwarded port URL for your Codespace (port 6080)");
        subtitle.setTextSize(13f);
        subtitle.setTextColor(Color.parseColor("#8b949e"));
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, 16, 0, 48);

        // URL input field
        urlInput = new EditText(this);
        urlInput.setHint("https://xxx-6080.app.github.dev/vnc.html");
        urlInput.setHintTextColor(Color.parseColor("#484f58"));
        urlInput.setTextColor(Color.WHITE);
        urlInput.setBackgroundColor(Color.parseColor("#161b22"));
        urlInput.setPadding(32, 28, 32, 28);
        urlInput.setTextSize(13f);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlInput.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // Pre-fill last used URL if any
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String lastUrl = prefs.getString(PREF_VNC_URL, "");
        if (!lastUrl.isEmpty()) {
            urlInput.setText(lastUrl);
            urlInput.setSelection(lastUrl.length());
        }

        // Handle keyboard "Go" action
        urlInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO) {
                launchVnc();
                return true;
            }
            return false;
        });

        // Connect button
        Button connectBtn = new Button(this);
        connectBtn.setText("Connect to VNC");
        connectBtn.setTextColor(Color.WHITE);
        connectBtn.setBackgroundColor(Color.parseColor("#238636"));
        connectBtn.setTextSize(16f);
        connectBtn.setPadding(32, 24, 32, 24);
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        btnParams.setMargins(0, 32, 0, 0);
        connectBtn.setLayoutParams(btnParams);
        connectBtn.setOnClickListener(v -> launchVnc());

        // Help text
        TextView help = new TextView(this);
        help.setText("In your Codespace: Ports tab → port 6080 → copy the Forwarded Address.\nThen append /vnc.html to the URL.");
        help.setTextSize(12f);
        help.setTextColor(Color.parseColor("#6e7681"));
        help.setPadding(0, 48, 0, 0);
        help.setGravity(Gravity.CENTER);

        root.addView(title);
        root.addView(subtitle);
        root.addView(urlInput);
        root.addView(connectBtn);
        root.addView(help);

        setContentView(root);
    }

    private void launchVnc() {
        String url = urlInput.getText().toString().trim();

        if (url.isEmpty()) {
            Toast.makeText(this, "Please enter a URL", Toast.LENGTH_SHORT).show();
            return;
        }

        // Auto-fix common mistakes
        if (!url.startsWith("http")) {
            url = "https://" + url;
        }
        if (!url.endsWith("/vnc.html") && !url.endsWith("/vnc_lite.html")) {
            // Strip trailing slash then append
            url = url.replaceAll("/$", "") + "/vnc.html";
        }

        // Save for next time
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(PREF_VNC_URL, url)
                .apply();

        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_VNC_URL, url);
        startActivity(intent);
        finish();
    }
}
