package com.cupfox.app;

import android.app.Dialog;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
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

    public static void initialize(WebView view, Handler mainHandler, Context context) {
        webView = view;
        handler = mainHandler;
        appContext = context;
    }

    public static void shutdown() {
        webView = null;
        handler = null;
    }

    public static synchronized String fetch(String type, String value) {
        try {
            Log.i(TAG, "fetch " + type + " " + value);
            if (handler == null) return "{\"error\":\"bridge unavailable\"}";
            String url;
            String js;
            if ("enc".equals(type)) {
                url = ORIGIN + value;
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

    private static String load(String url, String script) throws Exception {
        return loadWithRetry(url, script, 0);
    }

    private static String loadWithRetry(String url, String script, int retryCount) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        final String[] result = new String[1];
        handler.post(() -> {
            webView.setWebViewClient(new WebViewClient() {
                private boolean captured;

                @Override public void onPageCommitVisible(WebView view, String loadedUrl) {
                    capture(view, loadedUrl);
                }

                @Override public void onPageFinished(WebView view, String loadedUrl) {
                    capture(view, loadedUrl);
                }

                private void capture(WebView view, String loadedUrl) {
                    Log.i(TAG, "loaded " + loadedUrl);
                    if (captured || !loadedUrl.contains("cupfox.ai")) return;
                    captured = true;
                    handler.postDelayed(() -> view.evaluateJavascript(script, encoded -> {
                            try { result[0] = new JSONArray("[" + encoded + "]").getString(0); }
                            catch (Exception error) { result[0] = "{\"error\":\"decode failed\"}"; }
                            Log.i(TAG, "result " + result[0]);
                            done.countDown();
                        }), 700);
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
        android.widget.FrameLayout container = new android.widget.FrameLayout(appContext);
        container.addView(dialogWebView, new android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        dialog.setContentView(container);
        dialog.getWindow().setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT);
        dialog.show();
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
