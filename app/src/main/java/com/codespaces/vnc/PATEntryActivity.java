package com.codespaces.vnc;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Lets the user enter a GitHub Personal Access Token (PAT).
 *
 * Required scopes: codespace  (or repo + codespace)
 * Create at: https://github.com/settings/tokens
 *
 * The token is stored in SharedPreferences and used for all GitHub API calls.
 */
public class PATEntryActivity extends Activity {

    static final String PREFS_NAME   = "VncPrefs";
    static final String PREF_PAT     = "github_pat";

    private EditText tokenInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#0d1117"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(64, 100, 64, 64);
        root.setGravity(Gravity.TOP);

        // ── Header ──────────────────────────────────────────────────────────
        TextView emoji = new TextView(this);
        emoji.setText("🔑");
        emoji.setTextSize(48f);
        emoji.setGravity(Gravity.CENTER);
        root.addView(emoji);

        TextView title = new TextView(this);
        title.setText("GitHub Personal Access Token");
        title.setTextSize(20f);
        title.setTextColor(Color.WHITE);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 20, 0, 0);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Required to start and connect to your Codespace via the GitHub API.");
        subtitle.setTextSize(13f);
        subtitle.setTextColor(Color.parseColor("#8b949e"));
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, 12, 0, 48);
        root.addView(subtitle);

        // ── Token input ──────────────────────────────────────────────────────
        TextView label = new TextView(this);
        label.setText("Personal Access Token (classic)");
        label.setTextSize(13f);
        label.setTextColor(Color.parseColor("#8b949e"));
        label.setPadding(0, 0, 0, 8);
        root.addView(label);

        tokenInput = new EditText(this);
        tokenInput.setHint("ghp_xxxxxxxxxxxxxxxxxxxx");
        tokenInput.setHintTextColor(Color.parseColor("#484f58"));
        tokenInput.setTextColor(Color.WHITE);
        tokenInput.setBackgroundColor(Color.parseColor("#161b22"));
        tokenInput.setPadding(32, 28, 32, 28);
        tokenInput.setTextSize(13f);
        // Hide token by default
        tokenInput.setInputType(InputType.TYPE_CLASS_TEXT |
                InputType.TYPE_TEXT_VARIATION_PASSWORD);
        tokenInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        tokenInput.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // Pre-fill saved token
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String saved = prefs.getString(PREF_PAT, "");
        if (!saved.isEmpty()) {
            tokenInput.setText(saved);
            tokenInput.setSelection(saved.length());
        }

        tokenInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) { saveAndContinue(); return true; }
            return false;
        });
        root.addView(tokenInput);

        // Show/hide toggle
        CheckBox showToken = new CheckBox(this);
        showToken.setText("Show token");
        showToken.setTextColor(Color.parseColor("#8b949e"));
        showToken.setPadding(0, 16, 0, 0);
        showToken.setOnCheckedChangeListener((cb, checked) -> {
            int sel = tokenInput.getSelectionEnd();
            tokenInput.setInputType(InputType.TYPE_CLASS_TEXT |
                    (checked ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                             : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            tokenInput.setSelection(Math.max(sel, 0));
        });
        root.addView(showToken);

        // ── Save button ──────────────────────────────────────────────────────
        Button saveBtn = new Button(this);
        saveBtn.setText("Save & Connect");
        saveBtn.setTextColor(Color.WHITE);
        saveBtn.setBackgroundColor(Color.parseColor("#238636"));
        saveBtn.setTextSize(16f);
        saveBtn.setPadding(32, 24, 32, 24);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.setMargins(0, 32, 0, 0);
        saveBtn.setLayoutParams(btnLp);
        saveBtn.setOnClickListener(v -> saveAndContinue());
        root.addView(saveBtn);

        // ── Help text ────────────────────────────────────────────────────────
        addHelpSection(root,
                "How to create a PAT",
                "1. Go to github.com/settings/tokens\n" +
                "2. Click \"Generate new token (classic)\"\n" +
                "3. Select scope:  codespace  (tick the checkbox)\n" +
                "4. Click Generate, then copy the token here.");

        addHelpSection(root,
                "Why is a PAT needed?",
                "GitHub's Codespaces REST API requires OAuth or PAT authentication. " +
                "Session cookies work only in a browser — not in API calls from this app.");

        scroll.addView(root);
        setContentView(scroll);
    }

    private void saveAndContinue() {
        String token = tokenInput.getText().toString().trim();

        if (token.isEmpty()) {
            Toast.makeText(this, "Please enter your GitHub PAT", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!token.startsWith("ghp_") && !token.startsWith("ghu_") &&
                !token.startsWith("github_pat_")) {
            Toast.makeText(this,
                    "Token should start with ghp_, ghu_, or github_pat_",
                    Toast.LENGTH_LONG).show();
            // Allow override anyway
        }

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putString(PREF_PAT, token).apply();

        startActivity(new Intent(this, CodespaceStartActivity.class));
        finish();
    }

    private void addHelpSection(LinearLayout parent, String heading, String body) {
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        mp.setMargins(0, 48, 0, 0);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.parseColor("#161b22"));
        box.setPadding(32, 24, 32, 24);
        box.setLayoutParams(mp);

        TextView h = new TextView(this);
        h.setText(heading);
        h.setTextSize(13f);
        h.setTextColor(Color.parseColor("#58a6ff"));
        h.setTypeface(null, Typeface.BOLD);
        h.setPadding(0, 0, 0, 12);
        box.addView(h);

        TextView b = new TextView(this);
        b.setText(body);
        b.setTextSize(12f);
        b.setTextColor(Color.parseColor("#8b949e"));
        b.setLineSpacing(4f, 1f);
        box.addView(b);

        parent.addView(box);
    }
}
