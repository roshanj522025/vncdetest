# Codespaces VNC Android App

A native Android app that logs into GitHub then opens your Codespaces noVNC session fullscreen.

## How it works
1. **First launch**: Shows a GitHub login WebView — sign in normally (supports 2FA, SSO, etc.)
2. **After login**: Session cookies are saved. The app automatically launches the VNC viewer fullscreen.
3. **Next launches**: If your session cookie is still valid, skips login and goes straight to VNC.
4. **Session expired**: Automatically returns to login screen.

## Build via GitHub Actions

### Step 1 — Push to GitHub
```bash
git init
git add .
git commit -m "Initial commit"
git remote add origin https://github.com/YOUR_USERNAME/codespaces-vnc.git
git push -u origin main
```

### Step 2 — Actions builds the APK automatically
Go to your repo → **Actions** tab → watch "Build Android APK" run (~3–5 min).

### Step 3 — Download APK
Actions run → scroll to **Artifacts** → download `codespaces-vnc-debug` → unzip → install on Android.

### Enable sideloading on Android
Settings → Apps → Special app access → Install unknown apps → allow your file manager.

## Change the VNC URL
Edit `MainActivity.java`:
```java
private static final String VNC_URL =
    "https://YOUR-NEW-CODESPACE-URL-6080.app.github.dev/vnc.html";
```
Push and GitHub Actions rebuilds automatically.

## Release build with tag
```bash
git tag v2.0
git push origin v2.0
```
Creates a GitHub Release with the APK attached.
