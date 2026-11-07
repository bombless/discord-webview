package dev.bombless.discordwebview;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.File;

public final class MainActivity extends Activity {
    private WebView webView;
    private ScriptServer server;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setDefaultTextEncodingName("UTF-8");
        WebView.setWebContentsDebuggingEnabled(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                try {
                    if (server != null) applyScript(server.getCurrentScript());
                } catch (Exception e) {
                    android.util.Log.e("DiscordWebView", "Unable to load active script", e);
                }
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                android.util.Log.d("DiscordWebView",
                        message.message() + " -- line " + message.lineNumber());
                return true;
            }
        });

        File root = new File(getFilesDir(), "script-runtime");
        server = new ScriptServer(this, root, script -> runOnUiThread(() -> applyScript(script)));
        try {
            server.start();
        } catch (Exception e) {
            Toast.makeText(this, "Script server failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }

        webView.loadUrl("file:///android_asset/index.html");
    }

    private void applyScript(String script) {
        if (webView == null) return;
        String safe = org.json.JSONObject.quote(script);
        webView.evaluateJavascript(
                "window.__applyUserScript && window.__applyUserScript(" + safe + ");", null);
    }

    @Override
    protected void onDestroy() {
        if (server != null) server.close();
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
