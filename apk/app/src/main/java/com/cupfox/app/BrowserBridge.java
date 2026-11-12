package com.cupfox.app;

import android.app.Dialog;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.util.Log;

import org.json.JSONArray;

import java.net.URLEncoder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class BrowserBridge {
    private static final String TAG = "CupfoxBridge";
    private static final String ORIGIN = "http://www.cupfox.ai";
    private static WebView webView;
    private static Handler handler;
    private static Context appContext;
    private static volatile CountDownLatch captchaLatch;
    // The upstream verification response currently identifies itself through
    // the page title ("系统安全验证"), while body text can be empty.
    private static final String CAPTCHA_PAGE_EXPRESSION =
            "(()=>{const t=((document.title||'')+' '+((document.body&&document.body.innerText)||'')).toLowerCase();" +
            "return t.includes('系统安全验证')||t.includes('安全验证')||t.includes('请完成验证')||" +
            "t.includes('security verification')||t.includes('captcha')||" +
            "!!document.querySelector('#verify,.verify,[class*=captcha],[id*=captcha],input[name=check]');})()";

    public static void initialize(WebView view, Handler mainHandler, Context context) {
        webView = view;
        handler = mainHandler;
        appContext = context;
    }

    public static void shutdown() {
        webView = null;
        handler = null;
    }

    /**
     * Check the upstream session as soon as the app launches. This uses the
     * hidden bridge WebView only: the local Flask UI remains visible. If the
     * upstream presents a captcha, loadWithRetry opens the existing dialog.
     */
    public static void ensureSession() {
        new Thread(() -> {
            try {
                if (handler == null || !isNetworkAvailable()) return;
                String probe = "(()=>{const captcha=" + CAPTCHA_PAGE_EXPRESSION + ";" +
                        "return JSON.stringify({status:captcha?403:200,captcha:captcha});})()";
                String result = load(ORIGIN + "/", probe);
                Log.i(TAG, "startup session probe: " + result);
            } catch (Exception error) {
                // A probe failure must never block the bundled local UI.
                Log.w(TAG, "startup session probe failed", error);
            }
        }, "cupfox-session-probe").start();
    }

    public static synchronized String fetch(String type, String value) {
        try {
            Log.i(TAG, "fetch " + type + " " + value);
            if (handler == null) return "{\"error\":\"bridge unavailable\"}";
            String url;
            String js;
            if ("enc".equals(type)) {
                // value = /play/vid-src-ep.html (play page path)
                // 播放页里 muiplayer 在 iframe 中,顶层 player_aaaa 不存在。
                // 直接加载 muiplayer.php,从它的 window.player_aaaa.url 读 enc。
                // 从播放页路径解析出 muiplayer 的 iframe src:
                //   /play/123-1-1.html  →  读该页 iframe 的 src → /foxplay/muiplayer.php?vid=<enc>
                // Playback enc values belong to the upstream verification
                // session. Never reuse a persisted value: it may predate a
                // freshly completed captcha and causes playback to fail.
                url = ORIGIN + value;
                // Keep the original, known-good probe from the pre-landscape APK.
                // The upstream playback page exposes player_aaaa on its top window;
                // complex iframe/HTML parsing here caused evaluateJavascript to
                // return null before the player page could be rendered.
                js = "JSON.stringify({status:200,len:document.documentElement.outerHTML.length,enc:(window.player_aaaa&&window.player_aaaa.url)||null})";
            } else if ("detail".equals(type)) {
                url = ORIGIN + "/video/" + value + ".html";
                js = detailScript();
            } else if ("search".equals(type)) {
                url = ORIGIN + "/search/-------------.html?wd=" + URLEncoder.encode(value, "UTF-8");
                js = searchScript();
            } else if ("hot".equals(type)) {
                url = ORIGIN + "/";
                js = hotScript();
            } else if ("hotKeywords".equals(type)) {
                url = ORIGIN + "/";
                js = hotKeywordsScript();
            } else {
                return "{\"error\":\"bad request\"}";
            }
            return load(url, js);
        } catch (Exception error) {
            Log.e(TAG, "fetch failed", error);
            return "{\"error\":\"" + error.toString().replace("\"", "'") + "\"}";
        }
    }

    private static synchronized String load(String url, String script) throws Exception {
        return loadWithRetry(url, script, 0);
    }

    private static String loadWithRetry(String url, String script, int retryCount) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        final String[] result = new String[1];
        handler.post(() -> {
            webView.setWebViewClient(new WebViewClient() {
                private boolean captured;
                private int playbackProbeAttempts;

                @Override public void onPageCommitVisible(WebView view, String loadedUrl) {
                    Log.i(TAG, "commit " + loadedUrl + " requested=" + url);
                    capture(view, loadedUrl);
                }

                @Override public void onPageFinished(WebView view, String loadedUrl) {
                    capture(view, loadedUrl);
                }

                @Override public void onReceivedError(WebView view, android.webkit.WebResourceRequest request,
                                                       android.webkit.WebResourceError error) {
                    if (request != null && request.isForMainFrame()) {
                        Log.e(TAG, "bridge main frame error code=" + error.getErrorCode()
                                + " desc=" + error.getDescription() + " url=" + request.getUrl());
                    }
                }

                private void capture(WebView view, String loadedUrl) {
                    Log.i(TAG, "loaded " + loadedUrl);
                    if (captured || !loadedUrl.contains("cupfox.ai")) return;
                    captured = true;
                    handler.postDelayed(() -> evaluateWhenReady(view, loadedUrl), 700);
                }

                private void evaluateWhenReady(WebView view, String loadedUrl) {
                    view.evaluateJavascript(script, encoded -> {
                        Log.i(TAG, "evaluate raw url=" + loadedUrl + " raw=" + encoded);
                        try { result[0] = new JSONArray("[" + encoded + "]").getString(0); }
                        catch (Exception error) { result[0] = "{\"error\":\"decode failed\"}"; }
                        boolean playbackMissing = loadedUrl.contains("/play/") &&
                                (result[0] == null || result[0].contains("\"enc\":null") ||
                                 result[0].equals("null") || result[0].contains("\"enc\":\"\""));
                        if (playbackMissing && playbackProbeAttempts++ < 24) {
                            // The real player iframe is populated asynchronously
                            // after the parent page finishes loading.
                            handler.postDelayed(() -> evaluateWhenReady(view, loadedUrl), 300);
                            return;
                        }
                        Log.i(TAG, "result " + result[0]);
                        done.countDown();
                    });
                }
            });
            webView.loadUrl(url);
        });
        if (!done.await(20, TimeUnit.SECONDS)) {
            Log.e(TAG, "browser timeout " + url);
            return "{\"error\":\"browser timeout\"}";
        }
        String res = result[0] == null ? "{\"error\":\"empty response\"}" : result[0];

        if (retryCount < 2 && res.contains("\"captcha\":true")) {
            if (!isNetworkAvailable()) {
                Log.i(TAG, "no network, skip captcha");
                return res;
            }
            Log.i(TAG, "captcha detected, showing dialog for user to solve");
            captchaLatch = new CountDownLatch(1);
            handler.post(() -> showCaptchaDialog(url));
            try {
                captchaLatch.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Log.e(TAG, "captcha wait interrupted");
            }
            // Cookies set in the captcha WebView are shared by CookieManager.
            // Flush them before reloading the bridge page. Keep the base APK's
            // known-good playenc values; a fresh bridge value is only needed
            // when a path is not already present in that cache.
            CookieManager.getInstance().flush();
            return loadWithRetry(url, script, retryCount + 1);
        }

        return res;
    }

    private static void showCaptchaDialog(String url) {
        if (appContext == null) {
            if (captchaLatch != null) captchaLatch.countDown();
            return;
        }
        if (!isNetworkAvailable()) {
            Log.i(TAG, "no network, skip captcha dialog");
            if (captchaLatch != null) captchaLatch.countDown();
            return;
        }
        Dialog dialog = new Dialog(appContext);
        dialog.setTitle("请完成安全验证");
        dialog.setCancelable(true);
        dialog.setOnDismissListener(d -> {
            if (captchaLatch != null) captchaLatch.countDown();
        });

        WebView dialogWebView = new WebView(appContext);
        dialogWebView.getSettings().setJavaScriptEnabled(true);
        dialogWebView.getSettings().setDomStorageEnabled(true);
        dialogWebView.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        dialogWebView.getSettings().setUserAgentString("Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36");

        dialogWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String loadedUrl) {
                Log.i(TAG, "captcha page finished: " + loadedUrl);
            }
        });

        dialogWebView.loadUrl(url);
        // Never infer success from a single page-finished callback: the
        // upstream may render the challenge asynchronously. Keep the dialog
        // visible until the person explicitly confirms completion.
        android.widget.LinearLayout container = new android.widget.LinearLayout(appContext);
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        container.addView(dialogWebView, new android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        android.widget.Button completed = new android.widget.Button(appContext);
        completed.setText("验证完成，继续");
        completed.setAllCaps(false);
        completed.setOnClickListener(v -> dialog.dismiss());
        container.addView(completed, new android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog.setContentView(container);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT);
        }
    }

    private static boolean isNetworkAvailable() {
        if (appContext == null) return true;
        try {
            ConnectivityManager cm = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    private static String detailScript() {
        return "(()=>{const c=s=>(s||'').replace(/\\s+/g,' ').trim();if((document.body&&document.body.innerText||'').includes('系统安全验证'))return JSON.stringify({status:403,captcha:true,title:''});" +
                "const info=l=>{const e=[...document.querySelectorAll('.info-data')].find(x=>x.innerText.includes(l));return e?c(e.innerText.replace(l,'')):''};" +
                "const im=document.querySelector('.poster img,[data-original]');" +
                "const sources=[...document.querySelectorAll('#tagContent .play_list_box')].map(b=>{const sum=c((b.querySelector('.summary')||{}).textContent);return {name:sum.replace(/^当前资源由/,'').replace(/提供.*$/,''),eps:[...b.querySelectorAll('a[href*=\"/play/\"]')].map(a=>({url:a.getAttribute('href'),name:c(a.textContent)}))}}).filter(s=>s.name&&s.eps.length);" +
                "return JSON.stringify({status:200,title:c((document.querySelector('h1.movie-title,.movie-title')||{}).textContent),img:im?(im.getAttribute('data-original')||im.src||''):'',desc:c((document.querySelector('.detailsTxt,.summary')||{}).textContent),year:((document.title.match(/(19|20)\\d{2}/)||[])[0]||''),tags:[...document.querySelectorAll('.scroll-content a')].map(a=>c(a.textContent)).slice(0,8),director:info('导演：'),actors:info('演员：').split(/\\s+/).filter(Boolean),status:info('状态：'),sources})})()";
    }

    private static String searchScript() {
        return "(()=>{const c=s=>(s||'').replace(/\\s+/g,' ').trim();if((document.body&&document.body.innerText||'').includes('系统安全验证'))return JSON.stringify({status:403,captcha:true,list:[]});const list=[];const seen={};" +
                "document.querySelectorAll('a[href*=\"/video/\"]').forEach(a=>{" +
                "const m=(a.getAttribute('href')||'').match(/(\\d+)\\.html/);if(!m||seen[m[1]])return;seen[m[1]]=1;" +
                "const n=a.closest('.movie-list-item,.vod-search-list,.search-list-item,li')||a.parentElement;" +
                "const im=n&&n.querySelector('[data-original],img');" +
                "const title=n&&n.querySelector('.movie-title,.title,h1,h2,h3')||a;" +
                "const note=n&&n.querySelector('.movie-item-note,.note,.remarks');" +
                "const year=n&&n.querySelector('.movie-item-score,.year');" +
                "const desc=n&&n.querySelector('.movie-sub,.desc,.summary');" +
                "const d=n&&n.querySelector('a[href*=\"/play/\"]');" +
                "list.push({id:Number(m[1]),title:c(title.textContent),year:c(note&&note.textContent)||c(year&&year.textContent),note:c(note&&note.textContent),img:im?(im.getAttribute('data-original')||im.src||''):'',desc:c(desc&&desc.textContent),direct:d?d.getAttribute('href'):null});" +
                "});return JSON.stringify({status:200,list:list})})()";
    }

    private static String hotKeywordsScript() {
        return "(()=>{const kws=[];" +
                "document.querySelectorAll('a[href*=\"/search/\"][href*=\"wd=\"]').forEach(a=>{" +
                "const m=a.href.match(/wd=([^&]+)/);if(!m)return;" +
                "const kw=decodeURIComponent(m[1]).trim();" +
                "if(kw&&kws.indexOf(kw)===-1)kws.push(kw);" +
                "});" +
                "return JSON.stringify({status:200,keywords:kws.slice(0,15)})})()";
    }

    private static String hotScript() {
        return "(()=>{const c=s=>(s||'').replace(/\\s+/g,' ').trim();const list=[];const seen={};" +
                "document.querySelectorAll('a[href*=\"/video/\"]').forEach(a=>{" +
                "const m=(a.getAttribute('href')||'').match(/(\\d+)\\.html/);if(!m||seen[m[1]])return;seen[m[1]]=1;" +
                "const n=a.closest('.movie-list-item,.vod-search-list,.search-list-item,li,.card')||a.parentElement;" +
                "const im=n&&n.querySelector('[data-original],img');" +
                "const title=n&&n.querySelector('.movie-title,.title,.movie-list-title')||a;" +
                "const note=n&&n.querySelector('.movie-item-note,.note,.remarks');" +
                "const year=n&&n.querySelector('.movie-item-score,.year');" +
                "list.push({id:Number(m[1]),title:c(title.textContent),year:c(year&&year.textContent),note:c(note&&note.textContent),img:im?(im.getAttribute('data-original')||im.src||''):''});" +
                "});return JSON.stringify({status:200,list:list.slice(0,12)})})()";
    }
}
