package app.pulp.messenger;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

/**
 * پالپ — پوسته‌ی اندروید (WebView) برای نمایش سایت پالپ.
 * آدرس سایت در res/values/strings.xml (site_url) تنظیم می‌شود.
 */
public class MainActivity extends Activity {

    private static final int REQ_FILE = 1;
    private static final int REQ_STORAGE = 2;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private String siteUrl;
    private String siteHost;
    private String pendingUrl;
    private String pendingName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        siteUrl = getString(R.string.site_url);
        siteHost = Uri.parse(siteUrl).getHost();

        web = new WebView(this);
        setContentView(web, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false); // گیف‌ها خودکار پخش شوند
        s.setAllowFileAccess(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setUserAgentString(s.getUserAgentString() + " PulpApp/1.0");

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(new Bridge(), "PulpApp");
        web.setWebViewClient(new PulpClient());
        web.setWebChromeClient(new PulpChrome());
        web.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) ->
                startDownload(url, URLUtil.guessFileName(url, contentDisposition, mimetype)));

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(siteUrl);
        }
    }

    /* ------------------------------------------------------------ WebViewClient */

    private class PulpClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri u = request.getUrl();
            if ("file".equals(u.getScheme())) return false;
            String host = u.getHost();
            if (host != null && host.equalsIgnoreCase(siteHost)) return false;
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, u)); // لینک‌های بیرونی در مرورگر باز می‌شوند
            } catch (ActivityNotFoundException ignored) {
            }
            return true;
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) {
                view.loadUrl("file:///android_asset/offline.html");
            }
        }
    }

    /* ------------------------------------------------------------ انتخاب فایل (گالری/فایل) */

    private class PulpChrome extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = callback;
            try {
                startActivityForResult(params.createIntent(), REQ_FILE);
            } catch (Exception e) {
                fileCallback = null;
                return false;
            }
            return true;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileCallback = null;
        }
    }

    /* ------------------------------------------------------------ دانلود / ذخیره در گالری */

    private class Bridge {
        @JavascriptInterface
        public void download(final String url, final String name) {
            runOnUiThread(() -> {
                Uri u = Uri.parse(url);
                // فقط فایل‌های همین سایت
                if (u.getHost() != null && u.getHost().equalsIgnoreCase(siteHost)) startDownload(url, name);
            });
        }

        @JavascriptInterface
        public void reload() {
            runOnUiThread(() -> web.loadUrl(siteUrl));
        }
    }

    private void startDownload(String url, String name) {
        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingUrl = url;
            pendingName = name;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            return;
        }
        try {
            String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_");
            int dot = safe.lastIndexOf('.');
            String ext = dot >= 0 ? safe.substring(dot + 1).toLowerCase() : "";
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mime == null) mime = "application/octet-stream";
            String dir = mime.startsWith("image/") ? Environment.DIRECTORY_PICTURES
                    : mime.startsWith("video/") ? Environment.DIRECTORY_MOVIES
                    : Environment.DIRECTORY_DOWNLOADS;

            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            r.setTitle(safe);
            r.setMimeType(mime);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.allowScanningByMediaScanner();
            r.setDestinationInExternalPublicDir(dir, "Pulp/" + safe);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) r.addRequestHeader("Cookie", cookie);
            ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(r);
            Toast.makeText(this, "در حال ذخیره…", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "ذخیره‌ی فایل انجام نشد.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED && pendingUrl != null) {
                startDownload(pendingUrl, pendingName);
            } else {
                Toast.makeText(this, "برای ذخیره‌ی فایل باید دسترسی حافظه را بدهید.", Toast.LENGTH_LONG).show();
            }
            pendingUrl = null;
            pendingName = null;
        }
    }

    /* ------------------------------------------------------------ دکمه‌ی بازگشت و چرخه‌ی حیات */

    @Override
    public void onBackPressed() {
        // اول پنجره‌ها/منوها/چت باز را ببند؛ اگر چیزی باز نبود از برنامه خارج شو
        String js = "(function(){"
                + "if(document.querySelector('.overlay,.ctx')){document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true}));return 1;}"
                + "if(document.querySelector('.info-panel:not(.closing)')&&window.P){P.closeInfo();return 1;}"
                + "var s=history.state;if(s&&s.pulp==='chat'){history.back();return 1;}"
                + "return 0;})()";
        web.evaluateJavascript(js, value -> {
            if (!"1".equals(value)) {
                if (!moveTaskToBack(true)) finish();
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
        web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.removeJavascriptInterface("PulpApp");
            web.destroy();
        }
        super.onDestroy();
    }
}
