package com.herotux.sarrastwebview;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final String START_URL =
            "https://sarrast.com/series/cuckoldsex/5-schoolgirl";

    private static boolean isAllowed(Uri uri) {
        if (uri == null) return false;
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null) return false;

        boolean http = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        boolean sarrast = "sarrast.com".equalsIgnoreCase(host)
                || host.toLowerCase().endsWith(".sarrast.com");

        return http && sarrast;
    }

    private void block() {
        Toast.makeText(this, "این لینک مجاز نیست", Toast.LENGTH_SHORT).show();
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        WebView webView = findViewById(R.id.webView);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setJavaScriptCanOpenWindowsAutomatically(false);
        webView.getSettings().setSupportMultipleWindows(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (isAllowed(uri)) return false;
                block();
                return true;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Uri uri = Uri.parse(url);
                if (isAllowed(uri)) return false;
                block();
                return true;
            }
        });

        // Open the target directly; no Ouo redirect.
        webView.loadUrl(START_URL);
    }

    @Override
    public void onBackPressed() {
        WebView webView = findViewById(R.id.webView);
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
