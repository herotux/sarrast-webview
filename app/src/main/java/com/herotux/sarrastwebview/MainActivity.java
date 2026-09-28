package com.herotux.sarrastwebview;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Bundle;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.pdf.PdfDocument;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.View;
import android.view.Window;
import android.webkit.ValueCallback;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.ViewGroup;
import org.json.JSONArray;
import android.widget.Button;
import android.widget.ArrayAdapter;
import android.app.AlertDialog;
import android.widget.ProgressBar;
import android.widget.Toast;
import java.io.File;

public class MainActivity extends Activity {
    static { WebView.enableSlowWholeDocumentDraw(); }
    private static final String START_URL =
            "https://www.sarrast.com/";

    private static final String ALLOWED_TARGET_HOST = "sarrast.com";
    private static final String PREFS_NAME = "webview_state";
    private static final String LAST_URL_KEY = "last_url";

    private SharedPreferences preferences;
    private ProgressBar loadingProgress;
    private Button pdfButton;
    private Button linksPdfButton;
    private Button downloadsButton;
    private WebView backgroundWebView;
    private java.util.ArrayList<LinkItem> downloadQueue;
    private int downloadIndex = 0;
    private int downloadedCount = 0;
    private String batchFolderName = "صفحه";
    private Uri currentPdfUri;
    private long batchEndTimeMillis = 0L;

    private static class LinkItem {
        final String title;
        final String url;

        LinkItem(String title, String url) {
            this.title = title;
            this.url = url;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    private static boolean isAllowedTarget(Uri uri) {
        if (uri == null) return false;

        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null) return false;

        boolean http = "http".equalsIgnoreCase(scheme)
                || "https".equalsIgnoreCase(scheme);

        String normalizedHost = host.toLowerCase();
        boolean allowedHost = ALLOWED_TARGET_HOST.equalsIgnoreCase(normalizedHost)
                || normalizedHost.endsWith("." + ALLOWED_TARGET_HOST);

        return http && allowedHost;
    }

    private static boolean isOuo(Uri uri) {
        if (uri == null) return false;

        String scheme = uri.getScheme();
        String host = uri.getHost();

        return ("http".equalsIgnoreCase(scheme)
                || "https".equalsIgnoreCase(scheme))
                && "ouo.io".equalsIgnoreCase(host);
    }

    private void block() {
        Toast.makeText(this, "این لینک مجاز نیست", Toast.LENGTH_SHORT).show();
    }

    private void saveLastUrl(String url) {
        if (url == null || url.isEmpty()) return;

        Uri uri = Uri.parse(url);
        if (isAllowedTarget(uri)) {
            preferences.edit().putString(LAST_URL_KEY, url).apply();
        }
    }

    private boolean handleUrl(WebView view, Uri uri) {
        if (uri == null) {
            block();
            return true;
        }

        // Ouo is only an accepted link wrapper. Never open Ouo itself.
        if (isOuo(uri)) {
            String target = uri.getQueryParameter("s");

            if (target != null) {
                try {
                    Uri destination = Uri.parse(target);

                    if (isAllowedTarget(destination)) {
                        showLoading();
                        view.loadUrl(destination.toString());
                        return true;
                    }
                } catch (Exception ignored) {
                    // Fall through to blocked state.
                }
            }

            block();
            return true;
        }

        // Only sarrast.com and its subdomains may load.
        if (isAllowedTarget(uri)) {
            showLoading();
            return false;
        }

        block();
        return true;
    }

    private void showLoading() {
        if (loadingProgress != null) {
            loadingProgress.setProgress(0);
            loadingProgress.setVisibility(View.VISIBLE);
        }
    }

    private void chooseLinksForPdf(WebView webView) {
        String script = buildLazyLoadScript(
                "(function(){" +
                " const seen = new Set(), out = [];" +
                " document.querySelectorAll('a[href]').forEach(function(a) {" +
                "   try { const u = new URL(a.href, location.href); let target=u;" +
                "     if (u.hostname.toLowerCase()==='ouo.io') { const s=u.searchParams.get('s'); if(!s)return; target=new URL(s,location.href); }" +
                "     const host=target.hostname.toLowerCase();" +
                "     if ((target.protocol==='http:'||target.protocol==='https:')&&(host==='sarrast.com'||host.endsWith('.sarrast.com'))&&!seen.has(target.href)) {" +
                "       seen.add(target.href); let t=(a.innerText||a.textContent||'').replace(/\\s+/g,' ').trim();" +
                "       if(!t)t=target.pathname.split('/').filter(Boolean).pop()||target.pathname; out.push({title:t,url:target.href});" +
                "     }" +
                "   } catch(e) {}" +
                " }); return JSON.stringify(out);" +
                "})()");
        webView.evaluateJavascript(script, value -> {
            try {
                Object parsed = new org.json.JSONTokener(value).nextValue();
                if (parsed instanceof String) parsed = new org.json.JSONTokener((String) parsed).nextValue();
                JSONArray array = (JSONArray) parsed;
                final java.util.ArrayList<LinkItem> links = new java.util.ArrayList<>();
                for (int i = 0; i < array.length(); i++) {
                    org.json.JSONObject object = array.getJSONObject(i);
                    String url = object.optString("url", "");
                    String title = object.optString("title", url);
                    if (isAllowedTarget(Uri.parse(url))) {
                        links.add(new LinkItem(
                                title.length() > 120 ? title.substring(0, 120) : title,
                                url));
                    }
                }
                if (links.isEmpty()) {
                    Toast.makeText(this, "لینک قسمت‌ها در صفحه پیدا نشد", Toast.LENGTH_LONG).show();
                    return;
                }
                showLinkSelectionDialog(links);
            } catch (Exception e) {
                Toast.makeText(this, "خطا در خواندن لینک‌های صفحه", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showLinkSelectionDialog(java.util.ArrayList<LinkItem> links) {
        String[] labels = new String[links.size()];
        boolean[] checked = new boolean[links.size()];

        for (int i = 0; i < links.size(); i++) {
            labels[i] = links.get(i).title;
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("انتخاب صفحات برای PDF (" + links.size() + ")")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> {
                    checked[which] = isChecked;
                })
                .setNegativeButton("لغو", null)
                .setNeutralButton("انتخاب همه", null)
                .setPositiveButton("ذخیره", null)
                .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                for (int i = 0; i < checked.length; i++) {
                    checked[i] = true;
                    dialog.getListView().setItemChecked(i, true);
                }
            });

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                java.util.ArrayList<LinkItem> selected = new java.util.ArrayList<>();

                for (int i = 0; i < checked.length; i++) {
                    if (checked[i]) {
                        selected.add(links.get(i));
                    }
                }

                if (selected.isEmpty()) {
                    Toast.makeText(this, "حداقل یک لینک را انتخاب کنید",
                            Toast.LENGTH_SHORT).show();
                    return;
                }

                dialog.dismiss();
                showScheduleDialog(selected);
            });
        });

        dialog.show();
    }

    private void startBatchPdfDownload(java.util.ArrayList<LinkItem> selected) {
        downloadQueue = selected;
        CharSequence pageTitle = ((WebView) findViewById(R.id.webView)).getTitle();
        String sourceTitle = pageTitle == null ? "" : pageTitle.toString();
        if (TextUtils.isEmpty(sourceTitle)) {
            sourceTitle = "صفحه";
        }
        batchFolderName = sanitizeFileName(sourceTitle);
        if (TextUtils.isEmpty(batchFolderName)) {
            batchFolderName = "صفحه";
        }
        downloadIndex = 0;
        downloadedCount = 0;

        if (backgroundWebView == null) {
            backgroundWebView = new WebView(this);
            backgroundWebView.getSettings().setJavaScriptEnabled(true);
            backgroundWebView.getSettings().setDomStorageEnabled(true);

            backgroundWebView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    if (downloadQueue == null || downloadIndex >= downloadQueue.size()) {
                        finishBatchPdfDownload();
                        return;
                    }

                    if (!isAllowedTarget(Uri.parse(url))) {
                        skipCurrentDownload();
                        return;
                    }

                    prepareBackgroundPageForPdf();
                }

                @Override
                public boolean shouldOverrideUrlLoading(
                        WebView view, WebResourceRequest request) {
                    return !isAllowedTarget(request.getUrl());
                }

                @Override
                public void onReceivedError(
                        WebView view, WebResourceRequest request,
                        android.webkit.WebResourceError error) {
                    if (request.isForMainFrame()) {
                        skipCurrentDownload();
                    }
                }
            });

            ViewGroup root = findViewById(android.R.id.content);
            int screenWidth = Math.max(720, getResources().getDisplayMetrics().widthPixels);
            int screenHeight = Math.max(1280, getResources().getDisplayMetrics().heightPixels);
            ViewGroup.LayoutParams bgParams = new ViewGroup.LayoutParams(screenWidth, screenHeight);
            backgroundWebView.setAlpha(0.01f);
            root.addView(backgroundWebView, bgParams);
        }

        loadNextBackgroundPage();
    }

    private void loadNextBackgroundPage() {
        if (downloadQueue == null || downloadIndex >= downloadQueue.size()) {
            finishBatchPdfDownload();
            return;
        }
        if (batchEndTimeMillis > 0 && System.currentTimeMillis() >= batchEndTimeMillis) {
            finishBatchPdfDownload();
            return;
        }

        LinkItem item = downloadQueue.get(downloadIndex);
        backgroundWebView.loadUrl(item.url);
    }

    private void prepareBackgroundPageForPdf() {
        // The page must be fully warmed up in the background before PDF capture.
        backgroundWebView.evaluateJavascript(buildLazyLoadScript("JSON.stringify({images:document.images.length,loaded:Array.from(document.images).filter(i=>i.complete&&i.naturalWidth>0).length})"), value -> {
            try {
                Object parsed = new org.json.JSONTokener(value).nextValue();
                if (parsed instanceof String) parsed = new org.json.JSONTokener((String) parsed).nextValue();
                org.json.JSONObject state = (org.json.JSONObject) parsed;
                int total = state.optInt("images", 0);
                int loaded = state.optInt("loaded", 0);
                if (total > 0 && loaded < Math.max(1, total - 1)) {
                    backgroundWebView.postDelayed(this::prepareBackgroundPageForPdf, 1500);
                    return;
                }
            } catch (Exception ignored) {
                backgroundWebView.postDelayed(this::prepareBackgroundPageForPdf, 1000);
                return;
            }
            saveBackgroundPageAsPdf();
        });
    }

    private void saveBackgroundPageAsPdf() {
        if (downloadQueue == null || downloadIndex >= downloadQueue.size()) {
            finishBatchPdfDownload();
            return;
        }

        LinkItem item = downloadQueue.get(downloadIndex);
        String title = sanitizeFileName(item.title);

        if (TextUtils.isEmpty(title)) {
            title = "sarrast-" + (downloadIndex + 1);
        }

        String fileName = title + ".pdf";

        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        values.put(MediaStore.Downloads.MIME_TYPE, "application/pdf");

        if (android.os.Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.Downloads.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_DOWNLOADS
                            + "/Sarrast/" + batchFolderName + "/");
            values.put(MediaStore.Downloads.IS_PENDING, 1);
        }

        try {
            currentPdfUri = getContentResolver().insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);

            if (currentPdfUri == null) {
                skipCurrentDownload();
                return;
            }

            File tempFile = new File(getCacheDir(), "sarrast_batch_" + System.nanoTime() + ".pdf");
            writeWebViewToPdf(backgroundWebView, tempFile, new PdfWriteCallback() {
                @Override public void onSuccess() {
                    try {
                        copyFileToUri(tempFile, currentPdfUri);
                        if (android.os.Build.VERSION.SDK_INT >= 29) {
                            ContentValues done = new ContentValues();
                            done.put(MediaStore.Downloads.IS_PENDING, 0);
                            getContentResolver().update(currentPdfUri, done, null, null);
                        }
                        tempFile.delete();
                        downloadedCount++;
                        currentPdfUri = null;
                        downloadIndex++;
                        loadNextBackgroundPage();
                    } catch (Exception e) {
                        tempFile.delete();
                        deleteCurrentPdf();
                        skipCurrentDownload();
                    }
                }
                @Override public void onFailure() {
                    tempFile.delete();
                    deleteCurrentPdf();
                    skipCurrentDownload();
                }
            });
        } catch (Exception e) {
            deleteCurrentPdf();
            skipCurrentDownload();
        }
    }

    private void skipCurrentDownload() {
        deleteCurrentPdf();
        currentPdfUri = null;
        downloadIndex++;
        loadNextBackgroundPage();
    }

    private void deleteCurrentPdf() {
        if (currentPdfUri != null) {
            try {
                getContentResolver().delete(
                        currentPdfUri, null, null);
            } catch (Exception ignored) {
            }
        }
    }

    private void finishBatchPdfDownload() {
        if (downloadQueue == null) return;

        int total = downloadQueue.size();
        Toast.makeText(this,
                downloadedCount + " از " + total +
                        " صفحه در پوشه Downloads/Sarrast ذخیره شد",
                Toast.LENGTH_LONG).show();

        downloadQueue = null;
        preferences.edit().remove("scheduled_queue").remove("scheduled_start").remove("scheduled_end").apply();
        batchEndTimeMillis = 0L;
        downloadIndex = 0;
        downloadedCount = 0;
        batchFolderName = "صفحه";
    }

    private String sanitizeFileName(String name) {
        if (name == null) return "";
        return name.replaceAll("[\\/:*?\"<>|]", "_")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private void exportCurrentPageToPdf(WebView webView) {
        if (webView == null || webView.getUrl() == null) {
            Toast.makeText(this, "صفحه‌ای برای خروجی PDF وجود ندارد", Toast.LENGTH_SHORT).show();
            return;
        }
        startCurrentPagePdfExport(webView);
    }

    private void startCurrentPagePdfExport(WebView webView) {
        Toast.makeText(this, "در حال بارگذاری کامل تصاویر…", Toast.LENGTH_SHORT).show();
        webView.evaluateJavascript(buildLazyLoadScript("true"), value -> {
            final String rawTitle=webView.getTitle();
            final String title=TextUtils.isEmpty(sanitizeFileName(rawTitle))?"Sarrast":sanitizeFileName(rawTitle);
            File temp=new File(getCacheDir(),"sarrast_"+System.nanoTime()+".pdf");
            writeWebViewToPdf(webView,temp,new PdfWriteCallback(){
                public void onSuccess(){try{ContentValues cv=new ContentValues();cv.put(MediaStore.Downloads.DISPLAY_NAME,title+".pdf");cv.put(MediaStore.Downloads.MIME_TYPE,"application/pdf");if(Build.VERSION.SDK_INT>=29)cv.put(MediaStore.Downloads.RELATIVE_PATH,android.os.Environment.DIRECTORY_DOWNLOADS+"/Sarrast/");Uri u=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,cv);if(u==null)throw new Exception("output");copyFileToUri(temp,u);temp.delete();Toast.makeText(MainActivity.this,"PDF ذخیره شد",Toast.LENGTH_LONG).show();}catch(Exception e){temp.delete();Toast.makeText(MainActivity.this,"خطا در ساخت PDF",Toast.LENGTH_SHORT).show();}}
                public void onFailure(){temp.delete();Toast.makeText(MainActivity.this,"خطا در ساخت PDF",Toast.LENGTH_SHORT).show();}
            });
        });
    }

    /** Automatic lazy-load controller: scrolls the real page/container incrementally and waits for content/images. */
    private String buildLazyLoadScript(String resultExpression) {
        return "(async function(){"+
                "const wait=ms=>new Promise(r=>setTimeout(r,ms));"+
                "const raf=()=>new Promise(r=>requestAnimationFrame(()=>r()));"+
                "const root=document.scrollingElement||document.documentElement;"+
                "function candidates(){const a=[root];document.querySelectorAll('*').forEach(e=>{try{const s=getComputedStyle(e);if((s.overflowY==='auto'||s.overflowY==='scroll')&&e.scrollHeight>e.clientHeight+40)a.push(e);}catch(_){} });return a;}"+
                "let sc=root;"+
                "function pick(){return candidates().sort((a,b)=>(b.scrollHeight-b.clientHeight)-(a.scrollHeight-a.clientHeight))[0]||root;}"+
                "function top(){return sc===root?(window.scrollY||root.scrollTop):sc.scrollTop;}"+
                "function max(){return Math.max(0,sc.scrollHeight-(sc===root?window.innerHeight:sc.clientHeight));}"+
                "function move(y){if(sc===root)window.scrollTo(0,y);else sc.scrollTop=y;sc.dispatchEvent(new Event('scroll',{bubbles:true}));}"+
                "function imgState(){const a=Array.from(document.images);return {total:a.length,loaded:a.filter(i=>i.complete&&i.naturalWidth>0).length};}"+
                "await wait(1800);"+
                "let stable=0,lastH=-1,lastCount=-1,lastLoaded=-1,lastTop=-1;"+
                "for(let round=0;round<240&&stable<7;round++){"+
                "sc=pick();"+
                "const step=Math.max(180,(sc===root?window.innerHeight:sc.clientHeight)*0.55);"+
                "const target=Math.min(max(),top()+step);"+
                "move(target);await raf();await wait(1100);"+
                "const imgs=Array.from(document.images);"+
                "const near=imgs.filter(i=>{const r=i.getBoundingClientRect();return r.bottom>-900&&r.top<(window.innerHeight+1400);});"+
                "await Promise.all(near.map(i=>i.decode?i.decode().catch(()=>{}):Promise.resolve()));"+
                "await wait(500);"+
                "const h=sc.scrollHeight,count=imgs.length,loaded=imgs.filter(i=>i.complete&&i.naturalWidth>0).length,cur=top(),mx=max(),atEnd=cur>=mx-8;"+
                "if(h!==lastH||count!==lastCount||loaded!==lastLoaded)stable=0;else if(atEnd&&Math.abs(cur-lastTop)<8)stable++;else stable=0;"+
                "lastH=h;lastCount=count;lastLoaded=loaded;lastTop=cur;"+
                "if(atEnd)await wait(1400);}"+
                "/* Second pass from top to bottom. This re-triggers IntersectionObserver/lazy loaders that need a real viewport. */"+
                "move(0);await wait(1000);"+
                "for(let pass=0;pass<2;pass++){"+
                "sc=pick();const end=max();const step=Math.max(160,(sc===root?window.innerHeight:sc.clientHeight)*0.45);"+
                "for(let y=0;y<=end;y+=step){move(Math.min(y,end));await raf();await wait(900);}"+
                "move(end);await wait(1800);"+
                "}"+
                "window.__sarrastScroller=sc;"+
                "move(0);await wait(1500);"+
                "const all=Array.from(document.images);"+
                "await Promise.all(all.map(i=>i.decode?i.decode().catch(()=>{}):Promise.resolve()));"+
                "await wait(1200);"+
                "return "+resultExpression+";})()";
    }
