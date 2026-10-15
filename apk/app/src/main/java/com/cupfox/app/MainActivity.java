package com.cupfox.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.AudioManager;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.nio.ByteBuffer;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final String APP_URL = "http://127.0.0.1:5000/";
    private static final String UPSTREAM_URL = "http://www.cupfox.ai/";
    private static final String OFFLINE_URL = "http://127.0.0.1:5000/offline";
    private static final long FULLSCREEN_UI_DELAY_MS = 1800;
    private static final Pattern PLAY_URL_PATTERN = Pattern.compile("/play/(\\d+)-\\d+-\\d+\\.html");
    private static final String[] BLOCKED_AD_HOSTS = {
            "mdtj2ui.com",
            "4obyayl.com",
            "gvjz1n4.com",
            "lp-is.com"
    };
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hideSystemUiRunnable = this::hideFullscreenSystemUi;
    private FrameLayout rootLayout;
    private AudioManager audioManager;
    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private WebChromeClient chromeClient;
    private TextView nativeBackButton;
    private TextView gestureHud;
    private WebView webView;
    private WebView bridgeWebView;
    private static android.content.Context sAppContext;
    private float gestureStartX;
    private float gestureStartY;
    private float gestureLastY;
    private boolean gestureTracking;
    private boolean gestureVertical;
    private int gestureSide;
    private int touchSlop;
    private float currentBrightness = -1f;
    private float currentPlayerVolume = 1f;
    private float gestureStartPlayerVolume = 1f;
    private float gestureStartBrightness = -1f;
    private volatile String lastCapturedMediaUrl = "";
    private volatile long lastCapturedMediaTs = 0L;
    private final AtomicLong downloadIdGenerator = new AtomicLong(1L);
    private final Map<Long, DownloadState> downloadStates = new ConcurrentHashMap<>();
    private final Runnable hideHudRunnable = () -> {
        if (gestureHud != null) gestureHud.setVisibility(View.GONE);
    };

    private static class DownloadState {
        volatile String status = "pending";
        volatile int progress = 0;
        volatile long downloadedBytes = 0L;
        volatile long totalBytes = -1L;
        volatile String error = "";
        volatile String path = "";
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sAppContext = getApplicationContext();
        WebView.setWebContentsDebuggingEnabled(true);
        rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(Color.WHITE);
        webView = createWebView();
        webView.addJavascriptInterface(new DownloadBridge(), "AndroidBridge");
        bridgeWebView = createWebView();
        rootLayout.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams hidden = new FrameLayout.LayoutParams(1, 1);
        bridgeWebView.setAlpha(0.01f);
        rootLayout.addView(bridgeWebView, hidden);
        nativeBackButton = createNativeBackButton();
        rootLayout.addView(nativeBackButton);
        gestureHud = createGestureHud();
        rootLayout.addView(gestureHud);
        setContentView(rootLayout);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(bridgeWebView, true);
        BrowserBridge.initialize(bridgeWebView, handler, this);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        extractFfmpeg();

        chromeClient = new WebChromeClient() {
            @Override public void onShowCustomView(View view, CustomViewCallback callback) {
                if (fullscreenView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                fullscreenView = view;
                fullscreenCallback = callback;
                webView.setVisibility(View.GONE);
                bridgeWebView.setVisibility(View.GONE);
                nativeBackButton.setVisibility(View.GONE);
                rootLayout.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                if (gestureHud != null) rootLayout.bringChildToFront(gestureHud);
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                scheduleHideFullscreenSystemUi();
            }

            @Override public void onHideCustomView() {
                if (fullscreenView == null) return;
                handler.removeCallbacks(hideSystemUiRunnable);
                rootLayout.removeView(fullscreenView);
                fullscreenView = null;
                webView.setVisibility(View.VISIBLE);
                bridgeWebView.setVisibility(View.VISIBLE);
                syncNativeBackButton(webView.getUrl());
                showNormalSystemUi();
                if (fullscreenCallback != null) {
                    fullscreenCallback.onCustomViewHidden();
                    fullscreenCallback = null;
                }
            }
        };
        webView.setWebChromeClient(chromeClient);
        webView.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (request != null) {
                    String reqUrl = request.getUrl() != null ? request.getUrl().toString() : null;
                    captureMediaRequest(reqUrl);
                }
                if (request != null && isBlockedAdRequest(request.getUrl().getHost())) {
                    return emptyResponseFor(request);
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith(APP_URL) || url.startsWith(UPSTREAM_URL)) return false;
                return true;
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request == null || !request.isForMainFrame()) return;
                String failingUrl = request.getUrl() != null ? request.getUrl().toString() : "";
                Log.e("CupfoxBridge", "main frame error: " + error.getErrorCode() + " url=" + failingUrl);
                if (failingUrl.contains("cupfox.ai")) {
                    handler.post(() -> {
                        if (!isNetworkAvailable()) {
                            webView.loadUrl(OFFLINE_URL);
                        } else {
                            webView.loadUrl(APP_URL);
                        }
                    });
                }
            }

            @Override public void onPageFinished(WebView view, String url) {
                installAdBlocker(view);
                syncNativeBackButton(url);
                if (!url.contains("cupfox.ai")) return;
                String path = url.replaceFirst("^https?://[^/]+", "");
                if (!"/".equals(path) && !"".equals(path) && !"/index.html".equals(path)) return;
                view.evaluateJavascript(
                        "(()=>{var t=(document.body&&document.body.innerText||'');var hasCaptcha=t.includes('系统安全验证')||t.includes('安全验证')||t.includes('请完成验证')||document.querySelector('#verify, .verify, [class*=captcha], [id*=captcha]');var hasContent=Boolean(document.querySelector('.banner-box,.movie-list-body,.movie-list,.vodList'));return hasCaptcha?'captcha':(hasContent?'ok':'wait');})()",
                        result -> {
                            String r = result == null ? "error" : result.replace("\"", "");
                            Log.i("CupfoxBridge", "page check: " + r + " url=" + url);
                            if ("ok".equals(r)) {
                                CookieManager.getInstance().flush();
                                getSharedPreferences("cupfox", MODE_PRIVATE).edit().putBoolean("verified", true).apply();
                                view.evaluateJavascript("document.body.style.display='none';", null);
                                handler.postDelayed(() -> view.loadUrl(APP_URL), 50);
                            }
                        });
            }
        });

        new Thread(() -> {
            try {
                File webRoot = new File(getFilesDir(), "web");
                copyAssetTree("web", webRoot);
                if (!Python.isStarted()) Python.start(new AndroidPlatform(getApplicationContext()));
                PyObject module = Python.getInstance().getModule("server_main");
                module.callAttr("start", webRoot.getAbsolutePath());
                waitForServer(60);
            } catch (Exception error) {
                handler.post(() -> webView.loadData(
                        "<h2>启动失败</h2><pre>" + escapeHtml(error.toString()) + "</pre>",
                        "text/html", "UTF-8"));
            }
        }, "cupfox-bootstrap").start();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private WebView createWebView() {
        WebView view = new WebView(this);
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setUserAgentString("Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36");
        view.setHorizontalScrollBarEnabled(false);
        view.setOverScrollMode(View.OVER_SCROLL_NEVER);
        return view;
    }

    public class DownloadBridge {
        private String lastDownloadError = "";

        @JavascriptInterface
        public void clearCapturedMediaUrl() {
            lastCapturedMediaUrl = "";
            lastCapturedMediaTs = 0L;
        }

        @JavascriptInterface
        public String getCapturedMediaUrl() {
            if (lastCapturedMediaUrl == null || lastCapturedMediaUrl.isEmpty()) return "";
            if ((System.currentTimeMillis() - lastCapturedMediaTs) > 30 * 60 * 1000L) return "";
            return lastCapturedMediaUrl;
        }

        @JavascriptInterface
        public long downloadVideo(String url, String filename) {
            try {
                lastDownloadError = "";
                if (url == null || url.isEmpty() || !url.startsWith("http")) {
                    lastDownloadError = "URL 无效";
                    return -1;
                }
                String low = url.toLowerCase();
                if (low.contains(".m3u8") || low.contains(".ts") || low.contains(".flv")) {
                    lastDownloadError = "不支持此格式的下载（" + (low.contains(".m3u8") ? "HLS" : low.contains(".ts") ? "TS" : "FLV") + "）";
                    return -1;
                }
                String safeName = filename.replaceAll("[^\\w\\u4e00-\\u9fa5.\\-]", "_");
                if (safeName.length() > 120) safeName = safeName.substring(0, 120);
                if (!safeName.endsWith(".mp4")) safeName += ".mp4";
                final String name = safeName;
                final long id = downloadIdGenerator.getAndIncrement();
                final DownloadState state = new DownloadState();
                downloadStates.put(id, state);
                new Thread(() -> runDirectDownload(state, url, name), "cupfox-download-" + id).start();
                return id;
            } catch (Exception e) {
                lastDownloadError = e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage());
                Log.e("CupfoxDL", "download failed", e);
            }
            return -1;
        }

        private void runDirectDownload(DownloadState state, String url, String name) {
            HttpURLConnection conn = null;
            InputStream in = null;
            FileOutputStream out = null;
            try {
                state.status = "running";
                File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir == null) throw new IOException("应用下载目录不可用");
                if (!dir.exists() && !dir.mkdirs()) throw new IOException("创建下载目录失败");
                File target = new File(dir, name);
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(30000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("Referer", "http://www.cupfox.ai/");
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36");
                conn.connect();
                int code = conn.getResponseCode();
                if (code < 200 || code >= 400) throw new IOException("HTTP " + code);
                state.totalBytes = conn.getContentLengthLong();
                in = conn.getInputStream();
                out = new FileOutputStream(target);
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    state.downloadedBytes += read;
                    if (state.totalBytes > 0) {
                        state.progress = (int) Math.min(100L, (state.downloadedBytes * 100L) / state.totalBytes);
                    }
                }
                out.flush();
                out.close();
                out = null;
                if (conn != null) { conn.disconnect(); conn = null; }
                boolean valid = target.length() > 1024 * 100 && hasMoovAtom(target);
                if (!valid) {
                    target.delete();
                    state.status = "failed";
                    state.error = "视频文件不完整，无法播放";
                    lastDownloadError = state.error;
                    Log.e("CupfoxDL", "downloaded file invalid, size=" + target.length() + " hasMoov=false");
                } else {
                    state.progress = 100;
                    state.status = "complete";
                    state.path = target.getAbsolutePath();
                    Log.d("CupfoxDL", "download complete: " + state.path + " size=" + target.length());
                }
            } catch (Exception e) {
                state.status = "failed";
                state.error = e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage());
                lastDownloadError = state.error;
                Log.e("CupfoxDL", "download failed", e);
            } finally {
                try { if (in != null) in.close(); } catch (Exception ignored) {}
                try { if (out != null) out.close(); } catch (Exception ignored) {}
                if (conn != null) conn.disconnect();
            }
        }

        @JavascriptInterface
        public String getLastDownloadError() {
            return lastDownloadError == null ? "" : lastDownloadError;
        }

        private boolean hasMoovAtom(File file) {
            try {
                java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r");
                long len = raf.length();
                long pos = 0;
                while (pos + 8 < len) {
                    raf.seek(pos);
                    int size = raf.readInt();
                    byte[] typeBytes = new byte[4];
                    raf.readFully(typeBytes);
                    String type = new String(typeBytes);
                    if (size < 8 || pos + size > len + 1024) break;
                    if (type.equals("moov")) return true;
                    pos += size;
                }
                raf.close();
            } catch (Exception e) {
                Log.e("CupfoxDL", "moov check failed", e);
            }
            return false;
        }

        private String remuxWithFfmpeg(String tsPath) {
            try {
                String mp4Path = tsPath.replaceAll("\\.ts$", "") + ".mp4";
                String[] candidates = {"/data/local/tmp/cupfox_ffmpeg", new File(getFilesDir(), "ffmpeg").getAbsolutePath()};
                String ffmpegPath = null;
                for (String c : candidates) {
                    File f = new File(c);
                    if (f.exists() && f.canExecute()) { ffmpegPath = c; break; }
                }
                if (ffmpegPath == null) { Log.e("CupfoxDL", "no ffmpeg found"); return null; }
                Log.d("CupfoxDL", "using ffmpeg: " + ffmpegPath);
                ProcessBuilder pb = new ProcessBuilder(ffmpegPath, "-i", tsPath, "-c", "copy", "-movflags", "+faststart", "-y", mp4Path);
                pb.redirectErrorStream(true);
                Process p = pb.start();
                p.waitFor();
                if (p.exitValue() == 0 && new File(mp4Path).exists() && new File(mp4Path).length() > 1024) {
                    Log.d("CupfoxDL", "ffmpeg remux ok: " + mp4Path + " size=" + new File(mp4Path).length());
                    return mp4Path;
                }
                Log.e("CupfoxDL", "ffmpeg remux failed, exit=" + p.exitValue());
                new File(mp4Path).delete();
                return null;
            } catch (Exception e) {
                Log.e("CupfoxDL", "ffmpeg remux error", e);
                return null;
            }
        }

        @JavascriptInterface
        public String getDownloadProgress(long downloadId) {
            try {
                DownloadState state = downloadStates.get(downloadId);
                if (state == null) return "{\"status\":\"unknown\"}";
                String err = state.error == null ? "" : state.error.replace("\"", "'");
                return "{\"status\":\"" + state.status + "\",\"progress\":" + state.progress + ",\"downloaded\":" + state.downloadedBytes + ",\"total\":" + state.totalBytes + ",\"error\":\"" + err + "\"}";
            } catch (Exception e) {
                Log.e("CupfoxDL", "progress query failed", e);
            }
            return "{\"status\":\"unknown\"}";
        }

        @JavascriptInterface
        public void saveDownloadRecord(String title, String episode, int vid, int src, int ep) {
            saveDownloadRecordImpl(title, episode, vid, src, ep, null);
        }

        @JavascriptInterface
        public void saveDownloadRecordWithPath(String title, String episode, int vid, int src, int ep, String serverPath) {
            saveDownloadRecordImpl(title, episode, vid, src, ep, serverPath);
        }

        private void saveDownloadRecordImpl(String title, String episode, int vid, int src, int ep, String serverPath) {
            try {
                SharedPreferences prefs = getSharedPreferences("cupfox_downloads", MODE_PRIVATE);
                String key = vid + "_" + src + "_" + ep;
                String path = serverPath;
                if (path == null || path.isEmpty()) {
                    String name = safeFilename(title + "_" + episode + ".mp4");
                    File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    path = dir == null ? "" : new File(dir, name).getAbsolutePath();
                }
                prefs.edit()
                    .putString(key, title + "|" + episode + "|" + vid + "|" + src + "|" + ep + "|" + System.currentTimeMillis() + "|" + path)
                    .apply();
            } catch (Exception e) {
                Log.e("CupfoxDL", "save record failed", e);
            }
        }

        @JavascriptInterface
        public String getDownloadRecords() {
            try {
                SharedPreferences prefs = getSharedPreferences("cupfox_downloads", MODE_PRIVATE);
                File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                StringBuilder sb = new StringBuilder("[");
                boolean first = true;
                for (java.util.Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                    String val = entry.getValue().toString();
                    String[] parts = val.split("\\|", -1);
                    if (parts.length < 5) continue;
                    String path = parts.length > 6 ? parts[6] : "";
                    if (path.isEmpty() && dir != null) {
                        String name = safeFilename(parts[0] + "_" + parts[1] + ".mp4");
                        File f = new File(dir, name);
                        path = f.getAbsolutePath();
                    }
                    if (!first) sb.append(",");
                    first = false;
                    sb.append("{\"key\":\"").append(entry.getKey()).append("\"");
                    sb.append(",\"title\":\"").append(parts[0].replace("\"", "\\\"")).append("\"");
                    sb.append(",\"episode\":\"").append(parts[1].replace("\"", "\\\"")).append("\"");
                    sb.append(",\"vid\":").append(parts[2]);
                    sb.append(",\"src\":").append(parts[3]);
                    sb.append(",\"ep\":").append(parts[4]);
                    if (parts.length > 5) sb.append(",\"ts\":").append(parts[5]);
                    sb.append(",\"path\":\"").append(path.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"");
                    long fileSize = 0;
                    if (!path.isEmpty()) {
                        File f = new File(path);
                        if (f.exists()) fileSize = f.length();
                    }
                    sb.append(",\"size\":").append(fileSize);
                    sb.append("}");
                }
                sb.append("]");
                return sb.toString();
            } catch (Exception e) {
                return "[]";
            }
        }

        @JavascriptInterface
        public void deleteDownloadRecord(String key, String filePath) {
            try {
                if (key != null && !key.isEmpty()) {
                    SharedPreferences prefs = getSharedPreferences("cupfox_downloads", MODE_PRIVATE);
                    prefs.edit().remove(key).apply();
                }
                if (filePath != null && !filePath.isEmpty()) {
                    File f = new File(filePath);
                    if (f.exists() && f.delete()) {
                        Log.d("CupfoxDL", "deleted file: " + filePath);
                    }
                }
            } catch (Exception e) {
                Log.e("CupfoxDL", "delete record failed", e);
            }
        }

        @JavascriptInterface
        public void openLocalVideo(String path) {
            try {
                if (path == null || path.isEmpty()) {
                    handler.post(() -> {
                        try {
                            android.widget.Toast.makeText(MainActivity.this, "文件路径为空", android.widget.Toast.LENGTH_SHORT).show();
                        } catch (Exception ignored) {}
                    });
                    return;
                }
                File file = new File(path);
                if (!file.exists()) {
                    Log.e("CupfoxDL", "file not found: " + path);
                    handler.post(() -> {
                        try {
                            android.widget.Toast.makeText(MainActivity.this, "文件不存在: " + file.getName(), android.widget.Toast.LENGTH_SHORT).show();
                        } catch (Exception ignored) {}
                    });
                    return;
                }
                final String filePath = file.getAbsolutePath();
                handler.post(() -> {
                    try {
                        android.content.Intent intent = new android.content.Intent(MainActivity.this, VideoPlayerActivity.class);
                        intent.putExtra("filePath", filePath);
                        intent.putExtra("title", file.getName());
                        startActivity(intent);
                    } catch (Exception e) {
                        Log.e("CupfoxDL", "launch player failed", e);
                    }
                });
            } catch (Exception e) {
                Log.e("CupfoxDL", "open local video failed", e);
            }
        }

        private String safeFilename(String value) {
            String name = value == null ? "video.mp4" : value.replaceAll("[^\\w\\u4e00-\\u9fa5.\\-]", "_");
            if (name.length() > 120) name = name.substring(0, 120);
            return name.endsWith(".mp4") ? name : name + ".mp4";
        }
    }

    private void captureMediaRequest(String url) {
        if (!isMediaRequestUrl(url)) return;
        String low = url.toLowerCase();
        boolean isNewM3u8 = low.contains(".m3u8");
        boolean isTs = low.contains(".ts") && !low.contains(".m3u8");
        boolean currentIsM3u8 = lastCapturedMediaUrl != null && lastCapturedMediaUrl.toLowerCase().contains(".m3u8");
        if (isTs && currentIsM3u8) return;
        lastCapturedMediaUrl = url;
        lastCapturedMediaTs = System.currentTimeMillis();
        Log.i("CupfoxDL", "captured media url: " + url);
    }

    private boolean isMediaRequestUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        String low = url.toLowerCase();
        if (!(low.startsWith("http://") || low.startsWith("https://"))) return false;
        if (low.contains("doubanic.com") || low.contains("doubanio.com") || low.contains("/upload/photo/")) return false;
        if (low.matches(".*\\.(jpg|jpeg|png|gif|webp|bmp|svg)(\\?.*)?$")) return false;
        if (low.matches(".*\\.(m3u8|mp4|m4v|ts|flv|mov|mkv|avi)(\\?.*)?$")) return true;
        return low.contains("m3u8") || low.contains("mime=video") || low.contains("/obj/tos") || low.contains("video_mp4") || low.contains("/playlist/");
    }

    private boolean handlePlayerGesture(MotionEvent event) {
        if (!isPlayPageUrl(webView != null ? webView.getUrl() : null)) return false;
        if (fullscreenView == null) return false;
        float width = Math.max(rootLayout.getWidth(), 1);
        float height = Math.max(rootLayout.getHeight(), 1);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                gestureTracking = true;
                gestureVertical = false;
                gestureStartX = event.getX();
                gestureStartY = event.getY();
                gestureLastY = event.getY();
                gestureSide = event.getX() < (width / 2f) ? 0 : 1;
                gestureStartPlayerVolume = currentPlayerVolume;
                WindowManager.LayoutParams attrs = getWindow().getAttributes();
                gestureStartBrightness = attrs.screenBrightness >= 0
                        ? attrs.screenBrightness
                        : (currentBrightness >= 0 ? currentBrightness : 0.5f);
                return false;
            case MotionEvent.ACTION_MOVE:
                if (!gestureTracking) return false;
                float dx = event.getX() - gestureStartX;
                float dy = event.getY() - gestureStartY;
                if (!gestureVertical) {
                    if (Math.abs(dy) < touchSlop || Math.abs(dy) < Math.abs(dx)) return false;
                    gestureVertical = true;
                }
                float fraction = (gestureStartY - event.getY()) / height;
                if (gestureSide == 0) showGestureHud(adjustVolume(fraction));
                else showGestureHud(adjustBrightness(fraction));
                gestureLastY = event.getY();
                return true;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                boolean consumed = gestureVertical;
                gestureTracking = false;
                gestureVertical = false;
                hideGestureHud();
                return consumed;
            default:
                return false;
        }
    }

    private boolean isPlayPageUrl(String url) {
        return detailUrlFromPlayUrl(url) != null;
    }

    private String adjustVolume(float fraction) {
        currentPlayerVolume = Math.max(0f, Math.min(1f, gestureStartPlayerVolume + fraction));
        applyPlayerVolume(currentPlayerVolume);
        int percent = Math.round(currentPlayerVolume * 100f);
        return "音量: " + percent + "%";
    }

    private String adjustBrightness(float fraction) {
        WindowManager.LayoutParams attrs = getWindow().getAttributes();
        currentBrightness = Math.max(0.01f, Math.min(1f, gestureStartBrightness + fraction));
        attrs.screenBrightness = currentBrightness;
        getWindow().setAttributes(attrs);
        return "亮度: " + Math.round(currentBrightness * 100f) + "%";
    }

    private void applyPlayerVolume(float volume) {
        if (webView == null) return;
        String script = "(function(){"
                + "var v=null;"
                + "try{var f=document.querySelector('iframe.player-iframe');"
                + "if(f&&f.contentDocument)v=f.contentDocument.querySelector('video');}catch(e){}"
                + "if(!v)v=document.querySelector('video');"
                + "if(!v)return;"
                + "v.volume=" + volume + ";"
                + "v.muted=" + (volume <= 0.001f ? "true" : "false") + ";"
                + "})();";
        webView.evaluateJavascript(script, null);
    }

    private TextView createNativeBackButton() {
        TextView button = new TextView(this);
        button.setText("<");
        button.setTextColor(Color.WHITE);
        button.setTextSize(22);
        button.setGravity(17);
        button.setBackgroundColor(Color.parseColor("#73000000"));
        button.setVisibility(View.GONE);
        int size = dp(38);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
        lp.leftMargin = dp(12);
        lp.topMargin = dp(12);
        button.setLayoutParams(lp);
        button.setOnClickListener(v -> {
            String url = webView != null ? webView.getUrl() : null;
            String videoUrl = detailUrlFromPlayUrl(url);
            if (videoUrl != null) webView.loadUrl(videoUrl);
            else if (webView != null && webView.canGoBack()) webView.goBack();
            else if (webView != null) webView.loadUrl(APP_URL);
        });
        return button;
    }

    private TextView createGestureHud() {
        TextView hud = new TextView(this);
        hud.setTextColor(Color.WHITE);
        hud.setTextSize(16);
        hud.setGravity(17);
        hud.setBackgroundColor(Color.parseColor("#B3000000"));
        hud.setPadding(dp(18), dp(12), dp(18), dp(12));
        hud.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = 17;
        hud.setLayoutParams(lp);
        return hud;
    }

    private void showGestureHud(String text) {
        if (gestureHud == null || text == null) return;
        rootLayout.bringChildToFront(gestureHud);
        gestureHud.setText(text);
        gestureHud.setVisibility(View.VISIBLE);
        gestureHud.removeCallbacks(hideHudRunnable);
    }

    private void hideGestureHud() {
        if (gestureHud == null) return;
        gestureHud.removeCallbacks(hideHudRunnable);
        gestureHud.postDelayed(hideHudRunnable, 500);
    }

    private void syncNativeBackButton(String url) {
        if (nativeBackButton == null) return;
        nativeBackButton.setVisibility(fullscreenView == null && detailUrlFromPlayUrl(url) != null ? View.VISIBLE : View.GONE);
        if (detailUrlFromPlayUrl(url) != null) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private String detailUrlFromPlayUrl(String url) {
        if (url == null) return null;
        Matcher matcher = PLAY_URL_PATTERN.matcher(url);
        if (!matcher.find()) return null;
        return APP_URL + "video/" + matcher.group(1) + ".html";
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private boolean isBlockedAdRequest(String host) {
        if (host == null || host.isEmpty()) return false;
        String lower = host.toLowerCase();
        for (String blocked : BLOCKED_AD_HOSTS) {
            if (lower.equals(blocked) || lower.endsWith("." + blocked)) return true;
        }
        return false;
    }

    private WebResourceResponse emptyResponseFor(WebResourceRequest request) {
        String path = request != null && request.getUrl() != null ? request.getUrl().getPath() : "";
        String mime = (path != null && path.endsWith(".js")) ? "application/javascript" : "text/plain";
        return new WebResourceResponse(mime, "UTF-8", new ByteArrayInputStream(new byte[0]));
    }

    private void installAdBlocker(WebView view) {
        view.evaluateJavascript("(function(){"
                + "if(window.__cupfoxAdBlockerInstalled)return;"
                + "window.__cupfoxAdBlockerInstalled=true;"
                + "var sel='.aQYeVZni,[id^=\"FFCRmkzh_\"],[id^=\"aQYeVZni_\"],.ec-footer_scroll,.ec-lrmenu,.ec-lrshow,.ShareButton,[style*=\"position:fixed\"][style*=\"opacity:0.01\"],[style*=\"position: fixed\"][style*=\"opacity:0.01\"]';"
                + "var style=document.createElement('style');style.textContent=sel+'{display:none !important;visibility:hidden !important;pointer-events:none !important;}';document.documentElement.appendChild(style);"
                + "function kill(root){if(!root||root.nodeType!==1)return;if(root.matches&&root.matches(sel))root.remove();if(root.querySelectorAll)root.querySelectorAll(sel).forEach(function(el){el.remove();});}"
                + "document.addEventListener('click',function(e){var n=e.target;while(n&&n!==document.documentElement){if(n.matches&&n.matches(sel)){e.preventDefault();e.stopPropagation();if(e.stopImmediatePropagation)e.stopImmediatePropagation();n.remove();return false;}n=n.parentElement;}},true);"
                + "document.addEventListener('touchstart',function(e){var n=e.target;while(n&&n!==document.documentElement){if(n.matches&&n.matches(sel)){e.preventDefault();e.stopPropagation();if(e.stopImmediatePropagation)e.stopImmediatePropagation();n.remove();return false;}n=n.parentElement;}},true);"
                + "new MutationObserver(function(records){records.forEach(function(record){record.addedNodes.forEach(function(node){kill(node);});});}).observe(document.documentElement,{childList:true,subtree:true});"
                + "kill(document.documentElement);"
                + "})();", null);
    }

    private void waitForServer(int attempts) throws Exception {
        for (int i = 0; i < attempts; i++) {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(APP_URL).openConnection();
                connection.setConnectTimeout(500);
                connection.setReadTimeout(1000);
                if (connection.getResponseCode() == 200) {
                    handler.post(() -> {
                        if (!isNetworkAvailable()) {
                            Log.i("CupfoxBridge", "no network, loading offline page");
                            webView.loadUrl(OFFLINE_URL);
                            return;
                        }
                        SharedPreferences prefs = getSharedPreferences("cupfox", MODE_PRIVATE);
                        boolean verified = prefs.getBoolean("verified", false);
                        webView.loadUrl(verified ? APP_URL : UPSTREAM_URL);
                        if (verified) checkSessionAsync();
                    });
                    return;
                }
            } catch (IOException ignored) { }
            Thread.sleep(250);
        }
        throw new IOException("Flask 服务启动超时");
    }

    private boolean isNetworkAvailable() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    private void copyAssetTree(String assetPath, File destination) throws IOException {
        String[] children = getAssets().list(assetPath);
        if (children == null || children.length == 0) {
            if (assetPath.startsWith("web/data/") && destination.exists()) return;
            destination.getParentFile().mkdirs();
            try (InputStream input = getAssets().open(assetPath);
                 FileOutputStream output = new FileOutputStream(destination)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
            return;
        }
        destination.mkdirs();
        for (String child : children) copyAssetTree(assetPath + "/" + child, new File(destination, child));
    }

    private String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void checkSessionAsync() {
        new Thread(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(APP_URL + "session_status").openConnection();
                connection.setConnectTimeout(1500);
                connection.setReadTimeout(3000);
                if (connection.getResponseCode() != 200) return;
                StringBuilder body = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) body.append(line);
                }
                if (!body.toString().contains("\"valid\":true")) {
                    handler.post(() -> {
                        getSharedPreferences("cupfox", MODE_PRIVATE).edit().putBoolean("verified", false).apply();
                        if (!isNetworkAvailable()) {
                            webView.loadUrl(OFFLINE_URL);
                        } else {
                            webView.loadUrl(UPSTREAM_URL);
                        }
                    });
                }
            } catch (Exception e) {
                Log.w("CupfoxBridge", "session check failed (network?), keeping current state: " + e.getMessage());
            }
        }, "cupfox-session-check").start();
    }

    private void scheduleHideFullscreenSystemUi() {
        handler.removeCallbacks(hideSystemUiRunnable);
        handler.postDelayed(hideSystemUiRunnable, FULLSCREEN_UI_DELAY_MS);
    }

    private void hideFullscreenSystemUi() {
        if (fullscreenView == null) return;
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        decor.setOnSystemUiVisibilityChangeListener(visibility -> {
            if (fullscreenView != null
                    && (visibility & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0) {
                scheduleHideFullscreenSystemUi();
            }
        });
    }

    private void showNormalSystemUi() {
        View decor = getWindow().getDecorView();
        decor.setOnSystemUiVisibilityChangeListener(null);
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
    }

    private void extractFfmpeg() {
        try {
            File ffmpegFile = new File(getFilesDir(), "ffmpeg");
            if (ffmpegFile.exists() && ffmpegFile.length() > 16000000) return;
            InputStream in = getAssets().open("ffmpeg");
            FileOutputStream out = new FileOutputStream(ffmpegFile);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            out.close();
            in.close();
            ffmpegFile.setExecutable(true);
            ffmpegFile.setReadable(true);
            Log.d("CupfoxDL", "ffmpeg extracted: " + ffmpegFile.getAbsolutePath() + " size=" + ffmpegFile.length());
            new Thread(() -> {
                try {
                    String dst = "/data/local/tmp/cupfox_ffmpeg";
                    Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", "cp " + ffmpegFile.getAbsolutePath() + " " + dst + " && chmod 755 " + dst});
                    p.waitFor();
                    Log.d("CupfoxDL", "ffmpeg copied to /data/local/tmp exit=" + p.exitValue());
                } catch (Exception e) {
                    Log.e("CupfoxDL", "ffmpeg copy to /data/local/tmp failed", e);
                }
            }, "ffmpeg-copy").start();
        } catch (Exception e) {
            Log.e("CupfoxDL", "ffmpeg extract failed", e);
        }
    }

    @Override public void onBackPressed() {
        if (fullscreenView != null) {
            if (chromeClient != null) chromeClient.onHideCustomView();
            return;
        }
        if (webView.canGoBack()) webView.goBack(); else super.onBackPressed();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (handlePlayerGesture(event)) return true;
        return super.dispatchTouchEvent(event);
    }

    @Override protected void onDestroy() {
        BrowserBridge.shutdown();
        if (bridgeWebView != null) bridgeWebView.destroy();
        if (webView != null) webView.destroy();
        super.onDestroy();
    }

    public static boolean convertTsDirToMp4(String segDir, String mp4Path) {
        MediaMuxer muxer = null;
        try {
            File dir = new File(segDir);
            File[] segFiles = dir.listFiles((d, n) -> n.endsWith(".ts"));
            if (segFiles == null || segFiles.length == 0) {
                Log.e("CupfoxDL", "no ts segments in " + segDir);
                return false;
            }
            java.util.Arrays.sort(segFiles);
            Log.d("CupfoxDL", "converting " + segFiles.length + " segments to mp4");
            muxer = new MediaMuxer(mp4Path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            java.util.HashMap<String, Integer> trackMap = new java.util.HashMap<>();
            java.util.HashMap<String, Long> lastPts = new java.util.HashMap<>();
            boolean started = false;
            for (File segFile : segFiles) {
                MediaExtractor extractor = null;
                try {
                    extractor = new MediaExtractor();
                    extractor.setDataSource(segFile.getAbsolutePath());
                    int tc = extractor.getTrackCount();
                    for (int i = 0; i < tc; i++) {
                        MediaFormat fmt = extractor.getTrackFormat(i);
                        String mime = fmt.getString("mime");
                        if (!trackMap.containsKey(mime)) {
                            int idx = muxer.addTrack(fmt);
                            trackMap.put(mime, idx);
                            lastPts.put(mime, 0L);
                            Log.d("CupfoxDL", "added track: " + mime + " idx=" + idx);
                        }
                    }
                    if (!started && !trackMap.isEmpty()) {
                        muxer.start();
                        started = true;
                    }
                    android.media.MediaCodec.BufferInfo bi = new android.media.MediaCodec.BufferInfo();
                    java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocateDirect(2 * 1024 * 1024);
                    for (int i = 0; i < tc; i++) {
                        String mime = extractor.getTrackFormat(i).getString("mime");
                        Integer muxIdx = trackMap.get(mime);
                        if (muxIdx == null) continue;
                        extractor.selectTrack(i);
                        long offset = lastPts.getOrDefault(mime, 0L);
                        long maxPts = offset;
                        while (true) {
                            buf.clear();
                            bi.offset = 0;
                            bi.size = extractor.readSampleData(buf, 0);
                            if (bi.size < 0) break;
                            long rawPts = extractor.getSampleTime();
                            bi.presentationTimeUs = rawPts + offset;
                            bi.flags = extractor.getSampleFlags();
                            if (rawPts + offset > maxPts) maxPts = rawPts + offset;
                            try { muxer.writeSampleData(muxIdx, buf, bi); }
                            catch (Exception ignored) {}
                            extractor.advance();
                        }
                        lastPts.put(mime, maxPts + 3600);
                        extractor.unselectTrack(i);
                    }
                } catch (Exception segErr) {
                    Log.w("CupfoxDL", "skip segment " + segFile.getName() + ": " + segErr.getMessage());
                } finally {
                    try { if (extractor != null) extractor.release(); } catch (Exception ignored) {}
                }
            }
            if (started) {
                try { muxer.stop(); } catch (Exception stopErr) {
                    Log.w("CupfoxDL", "muxer stop warning: " + stopErr.getMessage());
                }
            }
            boolean ok = new File(mp4Path).exists() && new File(mp4Path).length() > 1024;
            Log.d("CupfoxDL", "MediaMuxer result: " + ok + " path=" + mp4Path + " size=" + new File(mp4Path).length());
            return ok;
        } catch (Exception e) {
            Log.e("CupfoxDL", "MediaMuxer error", e);
            return false;
        } finally {
            try { if (muxer != null) muxer.release(); } catch (Exception ignored) {}
        }
    }

    public static String runFfmpeg(String[] args) {
        try {
            android.content.Context ctx = sAppContext;
            String nativeDir = ctx.getApplicationInfo().nativeLibraryDir;
            java.io.File ffmpegFile = new java.io.File(nativeDir, "libffmpeg.so");
            Log.d("CupfoxDL", "ffmpeg native path: " + ffmpegFile.getAbsolutePath() + " exists=" + ffmpegFile.exists());
            if (!ffmpegFile.exists()) {
                return "error: libffmpeg.so not found in " + nativeDir;
            }
            StringBuilder cmd = new StringBuilder(ffmpegFile.getAbsolutePath());
            for (String a : args) {
                cmd.append(" '").append(a.replace("'", "'\\''")).append("'");
            }
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", cmd.toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            java.io.InputStream is = p.getInputStream();
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[4096];
            while (true) {
                int r = is.read(buf);
                if (r < 0) break;
                sb.append(new String(buf, 0, r));
                if (sb.length() > 4096) break;
            }
            int rc = p.waitFor();
            return "rc=" + rc + " " + sb.toString();
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    public static boolean convertTsToMp4(String tsPath, String mp4Path) {
        MediaExtractor extractor = null;
        MediaMuxer muxer = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(tsPath);
            muxer = new MediaMuxer(mp4Path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int trackCount = extractor.getTrackCount();
            int[] trackMap = new int[trackCount];
            for (int i = 0; i < trackCount; i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                trackMap[i] = muxer.addTrack(format);
            }
            muxer.start();
            android.media.MediaCodec.BufferInfo bi = new android.media.MediaCodec.BufferInfo();
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocateDirect(2 * 1024 * 1024);
            for (int i = 0; i < trackCount; i++) {
                extractor.selectTrack(i);
                while (true) {
                    buf.clear();
                    bi.offset = 0;
                    bi.size = extractor.readSampleData(buf, 0);
                    if (bi.size < 0) break;
                    bi.presentationTimeUs = extractor.getSampleTime();
                    bi.flags = extractor.getSampleFlags();
                    try {
                        muxer.writeSampleData(trackMap[i], buf, bi);
                    } catch (Exception writeErr) {
                        Log.w("CupfoxDL", "writeSampleData skip: " + writeErr.getMessage());
                    }
                    extractor.advance();
                }
                extractor.unselectTrack(i);
            }
            muxer.stop();
            Log.d("CupfoxDL", "MediaMuxer ok: " + mp4Path + " size=" + new File(mp4Path).length());
            return true;
        } catch (Exception e) {
            Log.e("CupfoxDL", "MediaMuxer error", e);
            return false;
        } finally {
            try { if (muxer != null) muxer.release(); } catch (Exception ignored) {}
            try { if (extractor != null) extractor.release(); } catch (Exception ignored) {}
        }
    }
}
