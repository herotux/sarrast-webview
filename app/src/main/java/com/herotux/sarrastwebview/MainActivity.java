package com.herotux.sarrastwebview;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.view.View;
import android.view.Window;
import android.webkit.ValueCallback;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final String START_URL =
            "https://www.sarrast.com/";

    private static final String ALLOWED_TARGET_HOST = "sarrast.com";
    private static final String PREFS_NAME = "webview_state";
    private static final String LAST_URL_KEY = "last_url";

    private SharedPreferences preferences;
    private ProgressBar loadingProgress;
    private Button pdfButton;

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

    private void exportCurrentPageToPdf(WebView webView) {
        if (webView == null || webView.getUrl() == null) {
            Toast.makeText(this, "صفحه‌ای برای خروجی PDF وجود ندارد",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        if (pdfButton != null) {
            pdfButton.setEnabled(false);
        }

        Toast.makeText(this, "در حال آماده‌سازی کل محتوای صفحه…",
                Toast.LENGTH_SHORT).show();

        // Walk through the complete document first. This triggers lazy-loaded
        // content/images as the page is scrolled, then returns to the top.
        String script =
                "(async function() {" +
                "  const wait = ms => new Promise(r => setTimeout(r, ms));" +
                "  let lastHeight = 0, stable = 0;" +
                "  for (let i = 0; i < 80 && stable < 3; i++) {" +
                "    const h = Math.max(document.body.scrollHeight," +
                "      document.documentElement.scrollHeight);" +
                "    window.scrollTo(0, h);" +
                "    await wait(180);" +
                "    const nh = Math.max(document.body.scrollHeight," +
                "      document.documentElement.scrollHeight);" +
                "    if (nh === lastHeight) stable++; else stable = 0;" +
                "    lastHeight = nh;" +
                "  }" +
                "  window.scrollTo(0, 0);" +
                "  await wait(500);" +
                "  return true;" +
                "})()";

        webView.evaluateJavascript(script, value -> {
            PrintManager printManager =
                    (PrintManager) getSystemService(Context.PRINT_SERVICE);

            if (printManager == null) {
                if (pdfButton != null) pdfButton.setEnabled(true);
                Toast.makeText(this, "امکان ساخت PDF در این دستگاه وجود ندارد",
                        Toast.LENGTH_SHORT).show();
                return;
            }

            String title = webView.getTitle();
            if (title == null || title.trim().isEmpty()) {
                title = "Sarrast";
            }

            String jobName = title.replaceAll("[\\\\/:*?\\\"<>|]", "_")
                    .trim();
            if (jobName.isEmpty()) {
                jobName = "Sarrast";
            }

            android.print.PrintDocumentAdapter adapter =
                    webView.createPrintDocumentAdapter(jobName);

            PrintAttributes attributes = new PrintAttributes.Builder()
                    .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                    .setResolution(new PrintAttributes.Resolution(
                            "sarrast_pdf", "PDF", 300, 300))
                    .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                    .build();

            printManager.print(jobName, adapter, attributes);

            if (pdfButton != null) {
                pdfButton.postDelayed(() -> pdfButton.setEnabled(true), 1000);
            }
        });
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

        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        loadingProgress = findViewById(R.id.loadingProgress);

        WebView webView = findViewById(R.id.webView);
        pdfButton = findViewById(R.id.pdfButton);

        pdfButton.setOnClickListener(v -> exportCurrentPageToPdf(webView));

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
