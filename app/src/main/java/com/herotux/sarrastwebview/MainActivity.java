package com.herotux.sarrastwebview;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.print.PrintDocumentAdapter;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.View;
import android.view.Window;
import android.webkit.ValueCallback;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.ViewGroup;
import android.net.Uri;
import org.json.JSONArray;
import android.widget.Button;
import android.widget.ArrayAdapter;
import android.app.AlertDialog;
import android.widget.ProgressBar;
import android.widget.Toast;
import android.widget.EditText;
import android.text.InputType;
import java.io.File;

public class MainActivity extends Activity {
    private static final String START_URL =
            "https://www.sarrast.com/";

    private static final String ALLOWED_TARGET_HOST = "sarrast.com";
    private static final String PREFS_NAME = "webview_state";
    private static final String LAST_URL_KEY = "last_url";

    private SharedPreferences preferences;
    private ProgressBar loadingProgress;
    private Button pdfButton;
    private Button linksPdfButton;
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
        String script =
                "(function() {" +
                " const seen = new Set();" +
                " const out = [];" +
                " document.querySelectorAll('a[href]').forEach(function(a) {" +
                "   try {" +
                "     const u = new URL(a.href, location.href);" +
                "     if ((u.protocol === 'http:' || u.protocol === 'https:') &&" +
                "         (u.hostname === 'sarrast.com' || u.hostname.endsWith('.sarrast.com')) &&" +
                "         !seen.has(u.href)) {" +
                "       seen.add(u.href);" +
                "       const t = (a.innerText || a.textContent || u.pathname)" +
                "           .replace(/\\s+/g, ' ').trim();" +
                "       out.push({title: t || u.pathname, url: u.href});" +
                "     }" +
                "   } catch (e) {}" +
                " });" +
                " return JSON.stringify(out);" +
                "})()";

        webView.evaluateJavascript(script, value -> {
            try {
                JSONArray array = new JSONArray(value);
                final java.util.ArrayList<LinkItem> links = new java.util.ArrayList<>();

                for (int i = 0; i < array.length(); i++) {
                    org.json.JSONObject object = array.getJSONObject(i);
                    String url = object.optString("url", "");
                    String title = object.optString("title", url);

                    if (isAllowedTarget(Uri.parse(url))) {
                        links.add(new LinkItem(
                                title.length() > 100
                                        ? title.substring(0, 100)
                                        : title,
                                url));
                    }
                }

                if (links.isEmpty()) {
                    Toast.makeText(this, "لینکی برای ذخیره پیدا نشد",
                            Toast.LENGTH_SHORT).show();
                    return;
                }

                showLinkSelectionDialog(links);
            } catch (Exception e) {
                Toast.makeText(this, "خطا در خواندن لینک‌های صفحه",
                        Toast.LENGTH_SHORT).show();
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
            root.addView(backgroundWebView, new ViewGroup.LayoutParams(1, 1));
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
        String script =
                "(async function() {" +
                " const wait = ms => new Promise(r => setTimeout(r, ms));" +
                " let lastHeight = 0, stable = 0;" +
                " for (let i = 0; i < 60 && stable < 2; i++) {" +
                "   const h = Math.max(document.body.scrollHeight," +
                "     document.documentElement.scrollHeight);" +
                "   window.scrollTo(0, h);" +
                "   await wait(120);" +
                "   const nh = Math.max(document.body.scrollHeight," +
                "     document.documentElement.scrollHeight);" +
                "   if (nh === lastHeight) stable++; else stable = 0;" +
                "   lastHeight = nh;" +
                " }" +
                " window.scrollTo(0, 0);" +
                " await wait(300);" +
                " return true;" +
                "})()";

        backgroundWebView.evaluateJavascript(script,
                value -> saveBackgroundPageAsPdf());
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
            String jobName = title;
            PrintDocumentAdapter adapter =
                    backgroundWebView.createPrintDocumentAdapter(jobName);

            PrintAttributes attributes = new PrintAttributes.Builder()
                    .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                    .setResolution(new PrintAttributes.Resolution(
                            "sarrast_batch", "PDF", 300, 300))
                    .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                    .build();

            writeAdapterToFile(adapter, attributes, tempFile, new PdfWriteCallback() {
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

        askForPdfPassword(password -> {
            if (pdfButton != null) pdfButton.setEnabled(false);

            Toast.makeText(this, "در حال آماده‌سازی و PDF…", Toast.LENGTH_SHORT).show();

            String script =
                    "(async function() {" +
                    "  const wait = ms => new Promise(r => setTimeout(r, ms));" +
                    "  let lastHeight = 0, stable = 0;" +
                    "  for (let i = 0; i < 80 && stable < 3; i++) {" +
                    "    const h = Math.max(document.body.scrollHeight, document.documentElement.scrollHeight);" +
                    "    window.scrollTo(0, h); await wait(180);" +
                    "    const nh = Math.max(document.body.scrollHeight, document.documentElement.scrollHeight);" +
                    "    if (nh === lastHeight) stable++; else stable = 0; lastHeight = nh;" +
                    "  }" +
                    "  window.scrollTo(0, 0); await wait(500); return true;" +
                    "})()";

            webView.evaluateJavascript(script, value -> {
                String outputTitle = sanitizeFileName(webView.getTitle());
                if (TextUtils.isEmpty(outputTitle)) outputTitle = "Sarrast";
                final String title = outputTitle;
                File tempFile = new File(getCacheDir(), "sarrast_current_" + System.nanoTime() + ".pdf");

                PrintDocumentAdapter adapter = webView.createPrintDocumentAdapter(title);
                PrintAttributes attributes = new PrintAttributes.Builder()
                        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                        .setResolution(new PrintAttributes.Resolution("sarrast_pdf", "PDF", 300, 300))
                        .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                        .build();

                writeAdapterToFile(adapter, attributes, tempFile, new PdfWriteCallback() {
                    @Override public void onSuccess() {
                        try {
                            if (android.os.Build.VERSION.SDK_INT < 29) {
                                throw new IllegalStateException("Android 10 or newer is required");
                            }
                            ContentValues values = new ContentValues();
                            values.put(MediaStore.Downloads.DISPLAY_NAME, title + ".pdf");
                            values.put(MediaStore.Downloads.MIME_TYPE, "application/pdf");
                            values.put(MediaStore.Downloads.RELATIVE_PATH,
                                    android.os.Environment.DIRECTORY_DOWNLOADS + "/Sarrast/");
                            values.put(MediaStore.Downloads.IS_PENDING, 1);
                            Uri uri = getContentResolver().insert(
                                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                            if (uri == null) throw new IllegalStateException("Cannot create output file");

                            copyFileToUri(tempFile, uri);

                            ContentValues done = new ContentValues();
                            done.put(MediaStore.Downloads.IS_PENDING, 0);
                            getContentResolver().update(uri, done, null, null);
                            Toast.makeText(MainActivity.this,
                                    "PDF در Downloads/Sarrast ذخیره شد",
                                    Toast.LENGTH_LONG).show();
                        } catch (Exception ex) {
                            Toast.makeText(MainActivity.this,
                                    "خطا در ساخت PDF رمزگذاری‌شده", Toast.LENGTH_LONG).show();
                        } finally {
                            tempFile.delete();
                            if (pdfButton != null) pdfButton.setEnabled(true);
                        }
                    }

                    @Override public void onFailure() {
                        tempFile.delete();
                        if (pdfButton != null) pdfButton.setEnabled(true);
                        Toast.makeText(MainActivity.this,
                                "خطا در ساخت PDF", Toast.LENGTH_SHORT).show();
                    }
                });
            });
        });
    }

    private interface PdfWriteCallback {
        void onSuccess();
        void onFailure();
    }

    private void writeAdapterToFile(PrintDocumentAdapter adapter,
                                    PrintAttributes attributes,
                                    File file,
                                    PdfWriteCallback callback) {
        try {
            android.os.ParcelFileDescriptor pfd =
                    android.os.ParcelFileDescriptor.open(file,
                            android.os.ParcelFileDescriptor.MODE_WRITE_ONLY
                                    | android.os.ParcelFileDescriptor.MODE_CREATE
                                    | android.os.ParcelFileDescriptor.MODE_TRUNCATE);

            adapter.onLayout(null, attributes, new CancellationSignal(),
                    new PrintDocumentAdapter.LayoutResultCallback() {
                        @Override public void onLayoutFinished(
                                android.print.PrintDocumentInfo info, boolean changed) {
                            adapter.onWrite(
                                    new PrintDocumentAdapter.PageRange[]{android.print.PageRange.ALL_PAGES},
                                    pfd, new CancellationSignal(),
                                    new PrintDocumentAdapter.WriteResultCallback() {
                                        @Override public void onWriteFinished(
                                                android.print.PageRange[] pages) {
                                            try { pfd.close(); } catch (Exception ignored) {}
                                            adapter.onFinish();
                                            callback.onSuccess();
                                        }

                                        @Override public void onWriteFailed(CharSequence error) {
                                            try { pfd.close(); } catch (Exception ignored) {}
                                            adapter.onFinish();
                                            callback.onFailure();
                                        }
                                    });
                        }

                        @Override public void onLayoutFailed(CharSequence error) {
                            try { pfd.close(); } catch (Exception ignored) {}
                            adapter.onFinish();
                            callback.onFailure();
                        }
                    });
        } catch (Exception e) {
            adapter.onFinish();
            callback.onFailure();
        }
    }

    private void copyFileToUri(File inputFile, Uri outputUri) throws Exception {
        try (java.io.InputStream input = new java.io.FileInputStream(inputFile);
             java.io.OutputStream output = getContentResolver().openOutputStream(outputUri, "w")) {
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
        final long[] times = {System.currentTimeMillis() + 60000L, System.currentTimeMillis() + 3600000L};
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.getDefault());
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
                    ((android.widget.Button)v).setText((index == 0 ? "شروع: " : "پایان: ") + fmt.format(cal.getTime()));
                }, cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), true).show();
            }, cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH)).show();
        };
        start.setOnClickListener(picker);
        end.setOnClickListener(picker);

        new AlertDialog.Builder(this)
                .setTitle("زمان‌بندی دانلود")
                .setMessage(selected.size() + " قسمت انتخاب شده")
                .setView(box)
                .setNegativeButton("لغو", null)
                .setPositiveButton("زمان‌بندی", (d, w) -> scheduleDownload(selected, times[0], times[1]))
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
        String[] projection = {MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME};
        try (android.database.Cursor cursor = getContentResolver().query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, null, null,
                MediaStore.Downloads.DATE_ADDED + " DESC")) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME));
                    if (name != null && name.toLowerCase().endsWith(".pdf")) {
                        long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID));
                        uris.add(Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, String.valueOf(id)));
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
                        (d, which) -> startActivity(new Intent(this, PdfViewerActivity.class).setData(uris.get(which))))
                .show();
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Draw the app edge-to-edge: the WebView reaches the physical screen
        // edges instead of leaving system-bar margins around the app.
        Window window = getWindow();
        window.setStatusBarColor(android.graphics.Color.TRANSPARENT);
        window.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        hideSystemBars();

        setContentView(R.layout.activity_main);

        PDFBoxResourceLoader.init(getApplicationContext());
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        loadingProgress = findViewById(R.id.loadingProgress);

        WebView webView = findViewById(R.id.webView);
        pdfButton = findViewById(R.id.pdfButton);
        linksPdfButton = findViewById(R.id.linksPdfButton);

        pdfButton.setOnClickListener(v -> exportCurrentPageToPdf(webView));
        linksPdfButton.setOnClickListener(v -> chooseLinksForPdf(webView));
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
            public void onPageStarted(WebView view, String url,
                                      android.graphics.Bitmap favicon) {
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
                if (!queue.isEmpty() && System.currentTimeMillis() >= preferences.getLong("scheduled_start", 0L)) {
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
        if (hasFocus) {
            hideSystemBars();
        }
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
