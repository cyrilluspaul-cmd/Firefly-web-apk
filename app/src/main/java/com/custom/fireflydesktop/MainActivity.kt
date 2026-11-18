package com.custom.fireflydesktop

import android.annotation.SuppressLint
import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    private val desktopUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val desktopSpoofScript = """
        (function() {
            try {
                Object.defineProperty(navigator, 'platform', { get: () => 'Win32' });
                Object.defineProperty(navigator, 'maxTouchPoints', { get: () => 0 });
                Object.defineProperty(navigator, 'userAgentData', {
                    get: () => ({
                        brands: [
                            { brand: 'Google Chrome', version: '131' },
                            { brand: 'Chromium', version: '131' },
                            { brand: 'Not_A Brand', version: '24' }
                        ],
                        mobile: false,
                        platform: 'Windows',
                        getHighEntropyValues: async () => ({
                            architecture: 'x86',
                            bitness: '64',
                            mobile: false,
                            model: '',
                            platform: 'Windows',
                            platformVersion: '15.0.0',
                            uaFullVersion: '131.0.0.0'
                        })
                    })
                });
                Object.defineProperty(window.screen, 'width', { get: () => 1920 });
                Object.defineProperty(window.screen, 'height', { get: () => 1080 });
                Object.defineProperty(window.screen, 'availWidth', { get: () => 1920 });
                Object.defineProperty(window.screen, 'availHeight', { get: () => 1040 });
            } catch (e) {}

            const targetViewport = 'width=1440, initial-scale=0.35, user-scalable=yes';
            function lockViewport() {
                var meta = document.querySelector('meta[name="viewport"]');
                if (!meta) {
                    if (document.head) {
                        meta = document.createElement('meta');
                        meta.name = 'viewport';
                        meta.content = targetViewport;
                        document.head.appendChild(meta);
                    }
                } else if (meta.content !== targetViewport) {
                    meta.content = targetViewport;
                }
            }

            lockViewport();
            const observer = new MutationObserver(lockViewport);
            observer.observe(document.documentElement, {
                childList: true,
                subtree: true,
                attributes: true,
                attributeFilter: ['content']
            });

            if (!window._blobHooked) {
                window._blobHooked = true;
                const origClick = HTMLAnchorElement.prototype.click;
                HTMLAnchorElement.prototype.click = function() {
                    if (this.href && this.href.startsWith('blob:')) {
                        const filename = this.download || ('firefly_vector_' + Date.now() + '.svg');
                        fetch(this.href)
                            .then(r => r.blob())
                            .then(blob => {
                                const reader = new FileReader();
                                reader.onloadend = function() {
                                    AndroidDownloader.saveBase64File(
                                        reader.result,
                                        filename,
                                        blob.type || 'image/svg+xml'
                                    );
                                };
                                reader.readAsDataURL(blob);
                            });
                        return;
                    }
                    return origClick.apply(this, arguments);
                };
            }
        })();
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.clearCache(true)
        webView.setInitialScale(30)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            userAgentString = desktopUserAgent
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, desktopSpoofScript, setOf("*"))
        }

        webView.addJavascriptInterface(BlobDownloadBridge(), "AndroidDownloader")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                view?.evaluateJavascript(desktopSpoofScript, null)
            }
        }

        webView.webChromeClient = WebChromeClient()

        webView.setDownloadListener { url, _, _, mimeType, _ ->
            if (url.startsWith("data:")) {
                val filename = "firefly_vector_${System.currentTimeMillis()}.svg"
                saveDataUrl(url, filename, mimeType ?: "image/svg+xml")
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })

        if (savedInstanceState == null) {
            webView.loadUrl("https://firefly.adobe.com/generate/vector")
        }
    }

    private fun saveDataUrl(dataUrl: String, filename: String, mimeType: String) {
        try {
            val base64Part = dataUrl.substringAfter(",")
            val bytes = Base64.decode(base64Part, Base64.DEFAULT)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                uri?.let {
                    contentResolver.openOutputStream(it)?.use { out -> out.write(bytes) }
                }
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val outFile = File(downloadsDir, filename)
                FileOutputStream(outFile).use { out -> out.write(bytes) }
            }

            runOnUiThread {
                Toast.makeText(this, "Saved to Downloads: $filename", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    inner class BlobDownloadBridge {
        @JavascriptInterface
        fun saveBase64File(dataUrl: String, filename: String, mimeType: String) {
            saveDataUrl(dataUrl, filename, mimeType)
        }
    }
}
