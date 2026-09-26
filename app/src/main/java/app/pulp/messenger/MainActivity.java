package app.pulp.messenger;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.ContactsContract;
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

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * پالپ — پوسته‌ی اندروید (WebView) برای نمایش سایت پالپ.
 * آدرس سایت در res/values/strings.xml (site_url) تنظیم می‌شود.
 */
public class MainActivity extends Activity {

    private static final int REQ_FILE = 1;
    private static final int REQ_STORAGE = 2;
    private static final int REQ_NOTIF = 3;
    private static final int REQ_CONTACTS = 4;
    private static final String CHANNEL_ID = "pulp_messages";
    private static final String EXTRA_CHAT_ID = "pulp_chat_id";

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private String siteUrl;
    private String siteHost;
    private String pendingDownloadUrl;
    private String pendingDownloadName;
    private String pendingChatId = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        siteUrl = getString(R.string.site_url);
        siteHost = Uri.parse(siteUrl).getHost();
        createNotificationChannel();
        readPendingChatFromIntent(getIntent());

        web = new WebView(this);
        setContentView(web, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false); // گیف‌ها خودکار پخش شوند
        s.setAllowFileAccess(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setUserAgentString(s.getUserAgentString() + " PulpApp/3.0");

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

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        readPendingChatFromIntent(intent);
        if (web != null && !pendingChatId.isEmpty()) {
            web.evaluateJavascript("if(window.P&&P.openPendingChat)P.openPendingChat();", null);
        }
    }

    private void readPendingChatFromIntent(Intent intent) {
        String c = intent != null ? intent.getStringExtra(EXTRA_CHAT_ID) : null;
        if (c != null) pendingChatId = c;
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
                startActivityForResult(buildPickerIntent(params), REQ_FILE);
            } catch (Exception e) {
                fileCallback = null;
                return false;
            }
            return true;
        }
    }

    /**
     * عمداً از params.createIntent() استفاده نمی‌کنیم. وقتی accept روی image/video باشد آن متد
     * گزینشگر جدید عکس اندروید (Photo Picker) را باز می‌کند که در بسیاری از دستگاه‌ها یک باگ شناخته‌شده
     * دارد: فایل انتخاب می‌شود ولی هرگز به صفحه برنمی‌گردد. ACTION_GET_CONTENT ساده هم روی برخی دستگاه‌ها
     * باز به همان اپ Photos و همان مسیر معیوب می‌رسد. ACTION_OPEN_DOCUMENT گزینشگر رسمی «فایل‌ها»ی اندروید
     * (Storage Access Framework) را باز می‌کند که کاملاً مسیر جداگانه‌ای دارد و این باگ را ندارد.
     */
    private Intent buildPickerIntent(WebChromeClient.FileChooserParams params) {
        String[] mimeTypes = params.getAcceptTypes();
        boolean hasTypes = mimeTypes != null && mimeTypes.length > 0 && mimeTypes[0] != null && mimeTypes[0].length() > 0;
        boolean multiple = params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE;

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(hasTypes ? mimeTypes[0] : "*/*");
        if (hasTypes && mimeTypes.length > 1) {
            intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        }
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        // اگر روی این دستگاه هیچ برنامه‌ای «فایل‌ها» نداشت (خیلی نادر)، به گزینشگر کلاسیک برمی‌گردیم
        PackageManager pm = getPackageManager();
        if (intent.resolveActivity(pm) == null) {
            Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
            fallback.addCategory(Intent.CATEGORY_OPENABLE);
            fallback.setType(hasTypes ? mimeTypes[0] : "*/*");
            if (hasTypes && mimeTypes.length > 1) fallback.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
            fallback.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple);
            return fallback;
        }
        return intent;
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
                if (u.getHost() != null && u.getHost().equalsIgnoreCase(siteHost)) startDownload(url, name);
            });
        }

        @JavascriptInterface
        public void reload() {
            runOnUiThread(() -> web.loadUrl(siteUrl));
        }

        @JavascriptInterface
        public String appVersion() {
            return "3.0.0";
        }

        /* ---------------- نوتیفیکیشن ---------------- */

        @JavascriptInterface
        public boolean hasNotificationPermission() {
            return NotificationManagerCompat.from(MainActivity.this).areNotificationsEnabled();
        }

        /** فقط در اندروید ۱۳ به بالا دیالوگ سیستمی نشان می‌دهد؛ پایین‌تر همیشه مجاز است. */
        @JavascriptInterface
        public void requestNotificationPermission() {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 33) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
                }
            });
        }

        /** نمایش یک نوتیفیکیشن محلی برای پیام تازه؛ با لمس، همان گفتگو باز می‌شود. */
        @JavascriptInterface
        public void notify(String title, String body, String chatId, int notifId) {
            runOnUiThread(() -> {
                if (!NotificationManagerCompat.from(MainActivity.this).areNotificationsEnabled()) return;
                Intent tap = new Intent(MainActivity.this, MainActivity.class);
                tap.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                tap.putExtra(EXTRA_CHAT_ID, chatId);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
                PendingIntent pi = PendingIntent.getActivity(MainActivity.this, notifId, tap, flags);
                NotificationCompat.Builder b = new NotificationCompat.Builder(MainActivity.this, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_dialog_email)
                        .setContentTitle(title)
                        .setContentText(body)
                        .setAutoCancel(true)
                        .setContentIntent(pi)
                        .setPriority(NotificationCompat.PRIORITY_HIGH);
                try {
                    NotificationManagerCompat.from(MainActivity.this).notify(notifId, b.build());
                } catch (SecurityException ignored) {
                }
            });
        }

        /** اگر برنامه از روی لمس یک نوتیفیکیشن باز شده، شناسه‌ی همان گفتگو را برمی‌گرداند و پاک می‌کند. */
        @JavascriptInterface
        public String consumePendingChat() {
            String c = pendingChatId;
            pendingChatId = "";
            return c;
        }

        /* ---------------- مخاطبین ---------------- */

        @JavascriptInterface
        public boolean hasContactsPermission() {
            return checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED;
        }

        /**
         * اگر دسترسی از قبل بود، بلافاصله مخاطبین را برای جاوااسکریپت می‌فرستد؛ وگرنه دسترسی را درخواست
         * می‌کند و بعد از پاسخ کاربر (در onRequestPermissionsResult) نتیجه فرستاده می‌شود.
         * نتیجه با فراخوانی P.onNativeContacts(arrayOrNull) در صفحه تحویل داده می‌شود.
         */
        @JavascriptInterface
        public void requestContacts() {
            runOnUiThread(() -> {
                if (checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                    sendContactsToWeb();
                } else {
                    requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, REQ_CONTACTS);
                }
            });
        }
    }

    private void sendContactsToWeb() {
        JSONArray arr = new JSONArray();
        Map<String, String> seen = new LinkedHashMap<>(); // phone -> name، برای حذف موارد تکراری
        ContentResolver cr = getContentResolver();
        try (Cursor c = cr.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER},
                null, null, null)) {
            if (c != null) {
                int nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
                int numIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);
                while (c.moveToNext()) {
                    String name = nameIdx >= 0 ? c.getString(nameIdx) : null;
                    String num = numIdx >= 0 ? c.getString(numIdx) : null;
                    if (name == null || num == null) continue;
                    num = num.replaceAll("[^0-9+]", "");
                    if (num.isEmpty()) continue;
                    if (!seen.containsKey(num)) seen.put(num, name);
                }
            }
        } catch (Exception ignored) {
        }
        for (Map.Entry<String, String> e : seen.entrySet()) {
            JSONObject o = new JSONObject();
            try {
                o.put("name", e.getValue());
                o.put("phone", e.getKey());
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        final String json = arr.toString();
        runOnUiThread(() -> web.evaluateJavascript("if(window.P&&P.onNativeContacts)P.onNativeContacts(" + json + ");", null));
    }

    /* ------------------------------------------------------------ دانلود / ذخیره در گالری (پیاده‌سازی) */

    private void startDownload(String url, String name) {
        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingDownloadUrl = url;
            pendingDownloadName = name;
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

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "پیام‌های پالپ", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("اعلان پیام‌های تازه‌ی پالپ");
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQ_STORAGE) {
            if (granted && pendingDownloadUrl != null) {
                startDownload(pendingDownloadUrl, pendingDownloadName);
            } else if (!granted) {
                Toast.makeText(this, "برای ذخیره‌ی فایل باید دسترسی حافظه را بدهید.", Toast.LENGTH_LONG).show();
            }
            pendingDownloadUrl = null;
            pendingDownloadName = null;
        } else if (requestCode == REQ_CONTACTS) {
            if (granted) {
                sendContactsToWeb();
            } else {
                web.evaluateJavascript("if(window.P&&P.onNativeContacts)P.onNativeContacts(null);", null);
            }
        }
        // REQ_NOTIF: نتیجه لازم نیست جایی خوانده شود؛ دفعه‌ی بعد hasNotificationPermission() آن را نشان می‌دهد.
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
