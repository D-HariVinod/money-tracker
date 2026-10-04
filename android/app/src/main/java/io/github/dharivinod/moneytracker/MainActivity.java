package io.github.dharivinod.moneytracker;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.provider.Settings;
import android.util.Base64;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;

/**
 * Shows the Money Tracker page (bundled in assets/www) and gives it the things a web page
 * cannot do by itself: fingerprint unlock, saving a file, and the shake listener.
 * The page reaches these through window.MoneyNative.
 */
public class MainActivity extends Activity {
    /** True while the app is on screen; the shake service may then show its pop-up directly. */
    static volatile boolean visible;

    // Bundled files are served from this reserved https address so the page gets a normal, secure origin.
    private static final String HOST = "appassets.androidplatform.net";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final int REQ_SAVE = 1, REQ_NOTIFY = 2;

    private WebView web;
    private byte[] pendingFile;
    private boolean bioShowing, started;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) WebView.setWebContentsDebuggingEnabled(true);

        web = new WebView(this);
        web.setBackgroundColor(getColor(R.color.paper));
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true); // the page keeps its data in localStorage
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setTextZoom(100);
        web.addJavascriptInterface(new Bridge(), "MoneyNative");
        web.setWebViewClient(new Client());
        setContentView(web);

        web.loadUrl("https://" + HOST + "/index.html");
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!started) {
            started = true;
            if (ShakeService.isEnabled(this)) {
                syncShake();
                askNotifications();
            }
        }
        js("window.onNativeResume && onNativeResume()");
    }

    @Override
    protected void onStop() {
        visible = false;
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // Back closes an open sheet first; otherwise the app goes to the background.
        web.evaluateJavascript("(function(){if(document.querySelector('.sheet.on')){closeSheet();return 1}return 0})()",
                v -> { if (!"1".equals(v)) moveTaskToBack(true); });
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ_SAVE) return;
        byte[] file = pendingFile;
        pendingFile = null;
        if (result != RESULT_OK) return; // backed out of the save screen
        boolean ok = false;
        if (file != null && data != null && data.getData() != null) {
            try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                if (out != null) {
                    out.write(file);
                    ok = true;
                }
            } catch (IOException | RuntimeException e) {
                ok = false;
            }
        }
        js("window.onNativeSaved && onNativeSaved(" + ok + ")");
    }

    private void js(String code) {
        web.post(() -> web.evaluateJavascript(code, null));
    }

    private void syncShake() {
        try {
            ShakeService.sync(this);
        } catch (RuntimeException e) {
            // The system refused to start the listener right now; it is tried again on the next launch.
        }
    }

    private void askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
        }
    }

    private boolean canUseBiometrics() {
        if (Build.VERSION.SDK_INT >= 30) {
            return getSystemService(BiometricManager.class).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS;
        }
        if (Build.VERSION.SDK_INT == 29) {
            return getSystemService(BiometricManager.class).canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS;
        }
        return Build.VERSION.SDK_INT == 28 && getPackageManager().hasSystemFeature(PackageManager.FEATURE_FINGERPRINT);
    }

    private void showBioPrompt() {
        if (bioShowing) return;
        if (Build.VERSION.SDK_INT < 28) {
            bioShowing = true;
            bioResult(false, "unavailable");
            return;
        }
        bioShowing = true;
        BiometricPrompt prompt = new BiometricPrompt.Builder(this)
                .setTitle(getString(R.string.app_name))
                .setSubtitle(getString(R.string.bio_subtitle))
                .setNegativeButton(getString(R.string.bio_use_pin), getMainExecutor(), (dialog, which) -> bioResult(false, "cancel"))
                .build();
        prompt.authenticate(new CancellationSignal(), getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                bioResult(true, "");
            }

            @Override
            public void onAuthenticationError(int code, CharSequence message) {
                boolean cancelled = code == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED || code == BiometricPrompt.BIOMETRIC_ERROR_CANCELED;
                bioResult(false, cancelled ? "cancel" : String.valueOf(message));
            }
        });
    }

    private void bioResult(boolean ok, String why) {
        if (!bioShowing) return;
        bioShowing = false;
        js("window.onNativeBio && onNativeBio(" + ok + "," + JSONObject.quote(why) + ")");
    }

    private class Client extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri u = request.getUrl();
            if (!HOST.equals(u.getHost())) return null; // the web fonts come from the network
            String path = u.getPath();
            if (path == null || path.equals("/")) path = "/index.html";
            try {
                InputStream in = getAssets().open("www" + path);
                boolean png = path.endsWith(".png");
                String type = png ? "image/png" : path.endsWith(".html") ? "text/html" : path.endsWith(".webmanifest") ? "application/manifest+json" : "application/octet-stream";
                return new WebResourceResponse(type, png ? null : "UTF-8", in);
            } catch (IOException e) {
                return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", new HashMap<String, String>(), new ByteArrayInputStream(new byte[0]));
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (HOST.equals(request.getUrl().getHost())) return false;
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, request.getUrl()));
            } catch (ActivityNotFoundException e) {
                // nothing on the phone can open the link
            }
            return true;
        }
    }

    /** What the page can call as window.MoneyNative. These run on a background thread. */
    private class Bridge {
        @JavascriptInterface
        public boolean bioAvailable() {
            try {
                return canUseBiometrics();
            } catch (RuntimeException e) {
                return false;
            }
        }

        /** Shows the fingerprint prompt; the answer arrives in the page as onNativeBio(ok, why). */
        @JavascriptInterface
        public void bioPrompt() {
            runOnUiThread(MainActivity.this::showBioPrompt);
        }

        /** Opens the phone's "save" screen for a file; the answer arrives as onNativeSaved(ok). */
        @JavascriptInterface
        public void saveFile(String name, String base64) {
            final byte[] data;
            try {
                data = Base64.decode(base64, Base64.DEFAULT);
            } catch (IllegalArgumentException e) {
                js("window.onNativeSaved && onNativeSaved(false)");
                return;
            }
            runOnUiThread(() -> {
                pendingFile = data;
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(XLSX).putExtra(Intent.EXTRA_TITLE, name);
                try {
                    startActivityForResult(i, REQ_SAVE);
                } catch (ActivityNotFoundException e) {
                    pendingFile = null;
                    js("window.onNativeSaved && onNativeSaved(false)");
                }
            });
        }

        @JavascriptInterface
        public boolean shakeEnabled() {
            return ShakeService.isEnabled(MainActivity.this);
        }

        @JavascriptInterface
        public void setShake(boolean on) {
            ShakeService.setEnabled(MainActivity.this, on);
            runOnUiThread(() -> {
                syncShake();
                if (on) askNotifications();
            });
        }

        /** Entries typed into the shake pop-up since the page last asked, as a JSON array. */
        @JavascriptInterface
        public String takeQuick() {
            return Quick.take(MainActivity.this);
        }

        /** The categories the pop-up should offer, in the page's order. */
        @JavascriptInterface
        public void setCategories(String json) {
            Quick.setCategories(MainActivity.this, json);
        }

        /** "Display over other apps": Android only lets a background app show a window when this is allowed. */
        @JavascriptInterface
        public boolean overlayAllowed() {
            return Settings.canDrawOverlays(MainActivity.this);
        }

        @JavascriptInterface
        public void openOverlaySettings() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
                } catch (ActivityNotFoundException e) {
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
                }
            });
        }
    }
}
