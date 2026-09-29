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

    private interface PdfWriteCallback {
        void onSuccess();
        void onFailure();
    }

    private void writeWebViewToPdf(WebView webView, File file, PdfWriteCallback callback) {
        webView.post(() -> webView.evaluateJavascript(
                "(function(){const out=[];const seen=new Set();document.querySelectorAll('img').forEach(function(i){try{const u=i.currentSrc||i.src||i.getAttribute('data-src')||i.getAttribute('data-lazy-src');if(u&&/^https?:/i.test(u)&&!seen.has(u)){seen.add(u);out.push(u);}}catch(e){}});return JSON.stringify(out);})()",
                value -> {
                    try {
                        Object parsed = new org.json.JSONTokener(value).nextValue();
                        if (parsed instanceof String) parsed = new org.json.JSONTokener((String) parsed).nextValue();
                        JSONArray urls = (JSONArray) parsed;
                        if (urls.length() == 0) { callback.onFailure(); return; }
                        createImagePdf(urls, 0, file, callback, new PdfState());
                    } catch (Exception e) { callback.onFailure(); }
                }));
    }

    private static class PdfState { final java.util.ArrayList<Bitmap> bitmaps = new java.util.ArrayList<>(); }

    private static class PdfState { final java.util.ArrayList<Bitmap> bitmaps = new java.util.ArrayList<>(); }

    private void createImagePdf(JSONArray urls, int index, File file, PdfWriteCallback callback, PdfState state) {
        if (index >= urls.length()) {
            new Thread(() -> {
                PdfDocument document = new PdfDocument();
                try {
                    for (Bitmap bitmap : state.bitmaps) appendBitmapToPdf(document, bitmap);
                    if (document.getPages().isEmpty()) throw new Exception("No PDF pages");
                    try (java.io.FileOutputStream output = new java.io.FileOutputStream(file)) { document.writeTo(output); }
                    runOnUiThread(callback::onSuccess);
                } catch (Throwable e) {
                    runOnUiThread(callback::onFailure);
                } finally {
                    document.close();
                    for (Bitmap bitmap : state.bitmaps) try { bitmap.recycle(); } catch (Exception ignored) {}
                    state.bitmaps.clear();
                }
            }).start();
            return;
        }
        final String imageUrl = urls.optString(index, "");
        new Thread(() -> {
            Bitmap bitmap = downloadImageBitmap(imageUrl);
            if (bitmap == null) { runOnUiThread(callback::onFailure); return; }
            state.bitmaps.add(bitmap);
            runOnUiThread(() -> createImagePdf(urls, index + 1, file, callback, state));
        }).start();
    }

    private Bitmap downloadImageBitmap(String imageUrl) {
        try {
            java.net.URL url = new java.net.URL(imageUrl);
            String cookies = android.webkit.CookieManager.getInstance().getCookie(imageUrl);
            android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            java.net.HttpURLConnection c = openImageConnection(url, cookies);
            java.io.InputStream in = c.getInputStream();
            android.graphics.BitmapFactory.decodeStream(in, null, bounds);
            in.close(); c.disconnect();
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1, maxDimension = 2200;
            while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > maxDimension) sample *= 2;
            c = openImageConnection(url, cookies);
            in = c.getInputStream();
            android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap bitmap = android.graphics.BitmapFactory.decodeStream(in, null, options);
            in.close(); c.disconnect();
            return bitmap;
        } catch (Throwable e) { return null; }
    }

    private java.net.HttpURLConnection openImageConnection(java.net.URL url, String cookies) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) url.openConnection();
        c.setConnectTimeout(20000); c.setReadTimeout(30000); c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
        if (cookies != null && !cookies.isEmpty()) c.setRequestProperty("Cookie", cookies);
        c.connect();
        if (c.getResponseCode() < 200 || c.getResponseCode() >= 300) { c.disconnect(); throw new java.io.IOException("HTTP " + c.getResponseCode()); }
        return c;
    }

    private void appendBitmapToPdf(PdfDocument document, Bitmap bitmap) {
        final int pageWidth = 595, pageHeight = 842;
        float scale = Math.min((float) pageWidth / bitmap.getWidth(), (float) pageHeight / bitmap.getHeight());
        float drawWidth = bitmap.getWidth() * scale, drawHeight = bitmap.getHeight() * scale;
        if (drawHeight <= pageHeight + 1) {
            PdfDocument.Page page = document.startPage(new PdfDocument.PageInfo.Builder(pageWidth, pageHeight, document.getPages().size() + 1).create());
            float left = (pageWidth - drawWidth) / 2f, top = (pageHeight - drawHeight) / 2f;
            page.getCanvas().drawBitmap(bitmap, null, new android.graphics.RectF(left, top, left + drawWidth, top + drawHeight), null);
            document.finishPage(page); return;
        }
        int sourceSliceHeight = Math.max(1, (int) (pageHeight / scale));
        for (int y = 0; y < bitmap.getHeight(); y += sourceSliceHeight) {
            int h = Math.min(sourceSliceHeight, bitmap.getHeight() - y);
            Bitmap slice = Bitmap.createBitmap(bitmap, 0, y, bitmap.getWidth(), h);
            PdfDocument.Page page = document.startPage(new PdfDocument.PageInfo.Builder(pageWidth, pageHeight, document.getPages().size() + 1).create());
            page.getCanvas().drawBitmap(slice, null, new android.graphics.RectF(0, 0, pageWidth, h * scale), null);
            document.finishPage(page); slice.recycle();
        }
    }

    private void copyFileToUri(File inputFile, Uri outputUri) throws Exception {
        try (java.io.InputStream input = new java.io.FileInputStream(inputFile);
             java.io.OutputStream output =
                     getContentResolver().openOutputStream(outputUri, "w")) {
            if (output == null) throw new java.io.IOException("Cannot open output");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.flush();
        }
    }

    private void showScheduleDialog(java.util.ArrayList<LinkItem> selected) {
        final android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int)(20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, 0, pad, 0);

        final android.widget.Button start = new android.widget.Button(this);
        final android.widget.Button end = new android.widget.Button(this);
        final long[] times = {
                System.currentTimeMillis() + 60000L,
                System.currentTimeMillis() + 3600000L
        };
        java.text.SimpleDateFormat fmt =
                new java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.getDefault());
        start.setText("شروع: " + fmt.format(new java.util.Date(times[0])));
        end.setText("پایان: " + fmt.format(new java.util.Date(times[1])));
        box.addView(start);
        box.addView(end);

        android.view.View.OnClickListener picker = v -> {
            int index = v == start ? 0 : 1;
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.setTimeInMillis(times[index]);
            new android.app.DatePickerDialog(this, (d, y, m, day) -> {
                cal.set(y, m, day);
                new android.app.TimePickerDialog(this, (t, h, min) -> {
                    cal.set(java.util.Calendar.HOUR_OF_DAY, h);
                    cal.set(java.util.Calendar.MINUTE, min);
                    cal.set(java.util.Calendar.SECOND, 0);
                    times[index] = cal.getTimeInMillis();
                    ((android.widget.Button)v).setText(
                            (index == 0 ? "شروع: " : "پایان: ") + fmt.format(cal.getTime()));
                }, cal.get(java.util.Calendar.HOUR_OF_DAY),
                        cal.get(java.util.Calendar.MINUTE), true).show();
            }, cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH),
                    cal.get(java.util.Calendar.DAY_OF_MONTH)).show();
        };
        start.setOnClickListener(picker);
        end.setOnClickListener(picker);

        new AlertDialog.Builder(this)
                .setTitle("زمان‌بندی دانلود")
                .setMessage(selected.size() + " قسمت انتخاب شده")
                .setView(box)
                .setNegativeButton("لغو", null)
                .setPositiveButton("زمان‌بندی",
                        (d, w) -> scheduleDownload(selected, times[0], times[1]))
                .show();
    }

    private void scheduleDownload(java.util.ArrayList<LinkItem> selected, long start, long end) {
        if (end <= start) {
            Toast.makeText(this, "زمان پایان باید بعد از شروع باشد", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            JSONArray array = new JSONArray();
            for (LinkItem item : selected) {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("title", item.title);
                o.put("url", item.url);
                array.put(o);
            }
            preferences.edit()
                    .putString("scheduled_queue", array.toString())
                    .putLong("scheduled_start", start)
                    .putLong("scheduled_end", end)
                    .apply();

            Intent intent = new Intent(this, DownloadAlarmReceiver.class);
            intent.setAction("SARRAST_START_DOWNLOAD");
            PendingIntent pi = PendingIntent.getBroadcast(this, 77, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            AlarmManager alarm = (AlarmManager)getSystemService(ALARM_SERVICE);
            if (Build.VERSION.SDK_INT >= 23) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, start, pi);
            } else {
                alarm.setExact(AlarmManager.RTC_WAKEUP, start, pi);
            }
            Toast.makeText(this, "زمان‌بندی ذخیره شد", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "خطا در زمان‌بندی", Toast.LENGTH_SHORT).show();
        }
    }

    private void openDownloads() {
        java.util.ArrayList<Uri> uris = new java.util.ArrayList<>();
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        String[] projection = {
                MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME
        };
        try (android.database.Cursor cursor = getContentResolver().query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, null, null,
                MediaStore.Downloads.DATE_ADDED + " DESC")) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String name = cursor.getString(
                            cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME));
                    if (name != null && name.toLowerCase().endsWith(".pdf")) {
                        long id = cursor.getLong(
                                cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID));
                        uris.add(Uri.withAppendedPath(
                                MediaStore.Downloads.EXTERNAL_CONTENT_URI, String.valueOf(id)));
                        names.add(name);
                    }
                }
            }
        } catch (Exception ignored) {}

        if (names.isEmpty()) {
            Toast.makeText(this, "PDF دانلودشده‌ای پیدا نشد", Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("PDFهای دانلودشده")
                .setItems(names.toArray(new String[0]),
                        (d, which) -> startActivity(
                                new Intent(this, PdfViewerActivity.class)
                                        .setData(uris.get(which))))
                .show();
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        window.setStatusBarColor(android.graphics.Color.TRANSPARENT);
        window.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        hideSystemBars();

        setContentView(R.layout.activity_main);

        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        loadingProgress = findViewById(R.id.loadingProgress);

        WebView webView = findViewById(R.id.webView);
        pdfButton = findViewById(R.id.pdfButton);
        linksPdfButton = findViewById(R.id.linksPdfButton);

        pdfButton.setOnClickListener(v -> exportCurrentPageToPdf(webView));
        linksPdfButton.setOnClickListener(v -> chooseLinksForPdf(webView));
        downloadsButton = findViewById(R.id.downloadsButton);
        downloadsButton.setOnClickListener(v -> openDownloads());

        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setJavaScriptCanOpenWindowsAutomatically(false);
        webView.getSettings().setSupportMultipleWindows(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view, WebResourceRequest request) {
                return handleUrl(view, request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(view, Uri.parse(url));
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                showLoading();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                saveLastUrl(url);
                if (loadingProgress != null) {
                    loadingProgress.setProgress(100);
                    loadingProgress.setVisibility(View.GONE);
                }
            }

            @Override
            public void onReceivedError(
                    WebView view, WebResourceRequest request,
                    android.webkit.WebResourceError error) {
                if (request.isForMainFrame() && loadingProgress != null) {
                    loadingProgress.setVisibility(View.GONE);
                }
            }
        });

        webView.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (loadingProgress != null) {
                    if (newProgress < 100) {
                        loadingProgress.setVisibility(View.VISIBLE);
                        loadingProgress.setProgress(newProgress);
                    } else {
                        loadingProgress.setProgress(100);
                    }
                }
            }
        });

        String scheduled = preferences.getString("scheduled_queue", null);
        long scheduledEnd = preferences.getLong("scheduled_end", 0L);
        if (scheduled != null && System.currentTimeMillis() < scheduledEnd) {
            try {
                JSONArray array = new JSONArray(scheduled);
                java.util.ArrayList<LinkItem> queue = new java.util.ArrayList<>();
                for (int i = 0; i < array.length(); i++) {
                    org.json.JSONObject o = array.getJSONObject(i);
                    queue.add(new LinkItem(o.optString("title"), o.optString("url")));
                }
                if (!queue.isEmpty()
                        && System.currentTimeMillis() >=
                        preferences.getLong("scheduled_start", 0L)) {
                    batchEndTimeMillis = scheduledEnd;
                    startBatchPdfDownload(queue);
                }
            } catch (Exception ignored) {}
        }

        String lastUrl = preferences.getString(LAST_URL_KEY, null);
        if (lastUrl != null && isAllowedTarget(Uri.parse(lastUrl))) {
            showLoading();
            webView.loadUrl(lastUrl);
        } else {
            showLoading();
            webView.loadUrl(START_URL);
        }
    }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    public void onBackPressed() {
        WebView webView = findViewById(R.id.webView);
        if (webView.canGoBack()) {
            showLoading();
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

}
