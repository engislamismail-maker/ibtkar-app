package com.ibtkar.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;

/**
 * غلاف أندرويد لتطبيق إبتكار: بيفتح نفس ملف index.html جوّه WebView، وبيضيف الحاجات اللي
 * المتصفح العادي كان بيعملها لوحده (رفع صور بالكاميرا/المعرض، حفظ ومشاركة ملفات PDF/Excel/صور،
 * فتح اللينكات الخارجية، زرار الرجوع).
 * المزامنة بين الأجهزة شغالة زي ما هي من خلال Firebase اللي جوّه index.html.
 */
public class MainActivity extends Activity {

    private static final String APP_HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + APP_HOST + "/assets/www/index.html";
    private static final int REQ_FILE_CHOOSER = 1001;

    private WebView web;
    private WebViewAssetLoader assetLoader;
    private ValueCallback<Uri[]> fileCallback;
    private Uri cameraOutputUri;
    private File cameraOutputFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        assetLoader = new WebViewAssetLoader.Builder()
                .setDomain(APP_HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web = new WebView(this);
        web.setBackgroundColor(0xFFF5F1EC);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setTextZoom(100);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        web.addJavascriptInterface(new NativeBridge(), "IbtkarNative");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (APP_HOST.equals(uri.getHost())) return false;
                String scheme = uri.getScheme() == null ? "" : uri.getScheme();
                if (scheme.equals("data") || scheme.equals("blob") || scheme.equals("javascript")) {
                    return true; // بيتعاملوا من جوّه native-bridge.js
                }
                openExternal(uri.toString());
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                return launchFileChooser(params);
            }
        });

        web.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            if (url.startsWith("http")) openExternal(url);
        });

        web.loadUrl(START_URL);
    }

    // ---------------------------------------------------------------- اختيار ملفات / كاميرا

    private boolean launchFileChooser(WebChromeClient.FileChooserParams params) {
        String[] accept = params.getAcceptTypes();
        boolean wantsImages = false;
        boolean onlyImages = true;
        if (accept == null || accept.length == 0 || (accept.length == 1 && accept[0].isEmpty())) {
            wantsImages = true;
            onlyImages = false;
        } else {
            for (String a : accept) {
                for (String t : a.split(",")) {
                    t = t.trim().toLowerCase();
                    if (t.isEmpty()) continue;
                    if (t.startsWith("image/")) wantsImages = true;
                    else onlyImages = false;
                }
            }
        }

        Intent cameraIntent = null;
        if (wantsImages) cameraIntent = buildCameraIntent();

        // الاختيار من المعرض/الملفات
        Intent contentIntent = new Intent(Intent.ACTION_GET_CONTENT);
        contentIntent.addCategory(Intent.CATEGORY_OPENABLE);
        if (onlyImages) {
            contentIntent.setType("image/*");
        } else {
            contentIntent.setType("*/*");
            ArrayList<String> mimes = new ArrayList<>();
            if (accept != null) {
                for (String a : accept) {
                    for (String t : a.split(",")) {
                        t = t.trim();
                        if (t.contains("/")) mimes.add(t);
                    }
                }
            }
            // ملفات JSON (النسخة الاحتياطية) غالبًا بتتسجّل في أندرويد بنوع تاني، فمنفلترهاش
            if (!mimes.isEmpty() && !mimes.contains("application/json")) {
                contentIntent.putExtra(Intent.EXTRA_MIME_TYPES, mimes.toArray(new String[0]));
            }
        }
        if (params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
            contentIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }

        Intent chooser;
        if (cameraIntent != null && params.isCaptureEnabled()) {
            chooser = cameraIntent;
        } else {
            chooser = Intent.createChooser(contentIntent, "اختار صورة أو ملف");
            if (cameraIntent != null) {
                chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cameraIntent});
            }
        }
        try {
            startActivityForResult(chooser, REQ_FILE_CHOOSER);
            return true;
        } catch (ActivityNotFoundException e) {
            fileCallback = null;
            Toast.makeText(this, "مفيش تطبيق يفتح الملفات", Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private Intent buildCameraIntent() {
        try {
            File dir = new File(getCacheDir(), "camera");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            cameraOutputFile = new File(dir, "IMG_" + System.currentTimeMillis() + ".jpg");
            cameraOutputUri = FileProvider.getUriForFile(this, getPackageName() + ".files", cameraOutputFile);
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, cameraOutputUri);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.setClipData(ClipData.newRawUri("", cameraOutputUri));
            return i;
        } catch (Exception e) {
            cameraOutputUri = null;
            cameraOutputFile = null;
            return null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE_CHOOSER || fileCallback == null) return;

        Uri[] results = null;
        if (resultCode == RESULT_OK) {
            ArrayList<Uri> list = new ArrayList<>();
            if (data != null && data.getClipData() != null) {
                ClipData clip = data.getClipData();
                for (int i = 0; i < clip.getItemCount(); i++) {
                    Uri u = clip.getItemAt(i).getUri();
                    if (u != null && !u.equals(cameraOutputUri)) list.add(u);
                }
            }
            if (list.isEmpty() && data != null && data.getData() != null) {
                list.add(data.getData());
            }
            if (list.isEmpty() && cameraOutputFile != null && cameraOutputFile.exists()
                    && cameraOutputFile.length() > 0) {
                list.add(cameraOutputUri);
            }
            if (!list.isEmpty()) results = list.toArray(new Uri[0]);
        }
        fileCallback.onReceiveValue(results);
        fileCallback = null;
    }

    // ---------------------------------------------------------------- لينكات خارجية

    private void openExternal(String url) {
        try {
            Intent i;
            if (url.startsWith("intent:")) {
                i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
            } else if (url.startsWith("tel:")) {
                i = new Intent(Intent.ACTION_DIAL, Uri.parse(url));
            } else {
                i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "مقدرتش أفتح اللينك", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- حفظ ومشاركة ملفات

    private static String safeName(String name) {
        if (name == null || name.trim().isEmpty()) name = "file";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private File writeToCache(String base64, String name) throws Exception {
        File dir = new File(getCacheDir(), "share/" + System.currentTimeMillis());
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File f = new File(dir, safeName(name));
        try (FileOutputStream os = new FileOutputStream(f)) {
            os.write(Base64.decode(base64, Base64.DEFAULT));
        }
        return f;
    }

    /** بيحفظ الملف في فولدر Download/Ibtkar، ويرجّع Uri يتفتح بيه. */
    private Uri saveToDownloads(String base64, String name, String mime) throws Exception {
        byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
        name = safeName(name);
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Ibtkar");
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new Exception("insert failed");
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os == null) throw new Exception("open failed");
                os.write(bytes);
            }
            return uri;
        } else {
            File dir = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Ibtkar");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File f = new File(dir, name);
            try (FileOutputStream os = new FileOutputStream(f)) {
                os.write(bytes);
            }
            return FileProvider.getUriForFile(this, getPackageName() + ".files", f);
        }
    }

    private void viewFile(Uri uri, String mime) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, mime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "فتح الملف"));
        } catch (Exception e) {
            Toast.makeText(this, "مفيش تطبيق يفتح الملف ده", Toast.LENGTH_SHORT).show();
        }
    }

    private void shareUris(ArrayList<Uri> uris, String mime, String title, String text) {
        Intent i;
        if (uris.size() == 1) {
            i = new Intent(Intent.ACTION_SEND);
            i.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            i.setClipData(ClipData.newRawUri("", uris.get(0)));
        } else if (uris.size() > 1) {
            i = new Intent(Intent.ACTION_SEND_MULTIPLE);
            i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            ClipData clip = ClipData.newRawUri("", uris.get(0));
            for (int k = 1; k < uris.size(); k++) clip.addItem(new ClipData.Item(uris.get(k)));
            i.setClipData(clip);
        } else {
            i = new Intent(Intent.ACTION_SEND);
        }
        i.setType(uris.isEmpty() ? "text/plain" : mime);
        if (text != null && !text.isEmpty()) i.putExtra(Intent.EXTRA_TEXT, text);
        if (title != null && !title.isEmpty()) i.putExtra(Intent.EXTRA_SUBJECT, title);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, "مشاركة"));
    }

    /** الواجهة اللي native-bridge.js بيكلّمها. */
    private class NativeBridge {

        @JavascriptInterface
        public void saveFile(String base64, String name, String mime) {
            final String m = (mime == null || mime.isEmpty()) ? "application/octet-stream" : mime;
            runOnUiThread(() -> {
                try {
                    final Uri saved = saveToDownloads(base64, name, m);
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("✅ اتحفظ الملف")
                            .setMessage(safeName(name) + "\n\nفي فولدر التنزيلات: Download/Ibtkar")
                            .setPositiveButton("مشاركة", (d, w) -> {
                                try {
                                    File f = writeToCache(base64, name);
                                    ArrayList<Uri> list = new ArrayList<>();
                                    list.add(FileProvider.getUriForFile(MainActivity.this,
                                            getPackageName() + ".files", f));
                                    shareUris(list, m, safeName(name), null);
                                } catch (Exception e) {
                                    Toast.makeText(MainActivity.this, "حصلت مشكلة في المشاركة", Toast.LENGTH_SHORT).show();
                                }
                            })
                            .setNeutralButton("فتح", (d, w) -> viewFile(saved, m))
                            .setNegativeButton("تمام", null)
                            .show();
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "مقدرتش أحفظ الملف", Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public void share(String json) {
            runOnUiThread(() -> {
                try {
                    JSONObject o = new JSONObject(json);
                    JSONArray files = o.optJSONArray("files");
                    ArrayList<Uri> uris = new ArrayList<>();
                    String mime = null;
                    if (files != null) {
                        for (int k = 0; k < files.length(); k++) {
                            JSONObject f = files.getJSONObject(k);
                            File file = writeToCache(f.getString("b64"), f.optString("name", "file"));
                            uris.add(FileProvider.getUriForFile(MainActivity.this,
                                    getPackageName() + ".files", file));
                            String fm = f.optString("mime", "*/*");
                            if (mime == null) mime = fm;
                            else if (!mime.equals(fm)) mime = "*/*";
                        }
                    }
                    String text = o.optString("text", "");
                    String url = o.optString("url", "");
                    if (!url.isEmpty()) text = text.isEmpty() ? url : text + "\n" + url;
                    shareUris(uris, mime == null ? "*/*" : mime, o.optString("title", ""), text);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "حصلت مشكلة في المشاركة", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void openExternal(String url) {
            runOnUiThread(() -> MainActivity.this.openExternal(url));
        }

        @JavascriptInterface
        public void exitApp() {
            runOnUiThread(() -> moveTaskToBack(true));
        }
    }

    // ---------------------------------------------------------------- دورة حياة + زرار الرجوع

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        web.evaluateJavascript("(window.__ibtkarBack ? window.__ibtkarBack() : false)", value -> {
            if (!"true".equals(value)) moveTaskToBack(true);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onPause() {
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
