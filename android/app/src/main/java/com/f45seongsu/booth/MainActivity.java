package com.f45seongsu.booth;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 포토부스 전용 앱: 전체화면 WebView + USB UVC 카메라 직접 연결.
 *
 * 웹페이지(index.html)는 window.BoothNative 가 있으면 getUserMedia 대신
 * /__booth_cam/frame.jpg 를 계속 받아 canvas에 그리고 canvas.captureStream()을 카메라 stream으로 쓴다.
 *
 * 숨은 설정: 화면 왼쪽 위 모서리를 3초 안에 5번 탭.
 */
public class MainActivity extends Activity {
    private static final String TAG = "Booth";
    static final String DEFAULT_URL = "https://f45seongsu.github.io/booth/";
    private static final String ALLOWED_HOST = "f45seongsu.github.io";
    private static final String CAM_PATH = "/__booth_cam/frame.jpg";
    private static final int REQ_CAMERA = 1;

    private WebView web;
    private UvcSource uvc;
    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("booth", MODE_PRIVATE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        uvc = new UvcSource(this);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        setContentView(web);
        WebView.setWebContentsDebuggingEnabled(true); // PC 크롬 chrome://inspect 로 콘솔 확인 가능

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // localStorage (CRM 토큰, 카메라 설정)
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false); // BGM·효과음 자동재생
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(s.getUserAgentString() + " F45BoothApp/" + BuildConfig.VERSION_NAME);

        web.addJavascriptInterface(new Bridge(), "BoothNative");
        web.setWebViewClient(new BoothClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage m) {
                Log.d(TAG, "[js] " + m.message() + " @" + m.sourceId() + ":" + m.lineNumber());
                return true;
            }
        });

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(startUrl());

        // 안드로이드 10+는 USB 카메라를 열 때 CAMERA 권한이 먼저 있어야 함
        // 마이크 달린 USB 카메라는 RECORD_AUDIO 도 있어야 USB 연결 창에서 "항상"을 고를 수 있음
        if (Build.VERSION.SDK_INT >= 23 && (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)) {
            requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, REQ_CAMERA);
        } else {
            uvc.start();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA) uvc.start();
    }

    private String startUrl() {
        String u = prefs.getString("url", DEFAULT_URL);
        return (u == null || u.trim().isEmpty()) ? DEFAULT_URL : u.trim();
    }

    // =========================================================
    // 키오스크: 전체화면 · 화면 꺼짐 방지 · 뒤로가기 막기
    // =========================================================
    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        web.onResume();
    }

    @Override
    protected void onPause() {
        web.onPause();
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        View d = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = d.getWindowInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        // 뒤로가기 막음 (손님이 앱을 나가지 못하게)
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // 카메라를 다시 꽂으면 USB_DEVICE_ATTACHED 로 이 화면이 다시 불림 → 카메라 다시 찾기
        uvc.start();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onDestroy() {
        uvc.destroy();
        web.destroy();
        super.onDestroy();
    }

    // =========================================================
    // 숨은 설정: 왼쪽 위 모서리 5번 탭
    // =========================================================
    private int cornerTaps;
    private long cornerFirst;

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            float lim = 90 * getResources().getDisplayMetrics().density;
            if (e.getX() < lim && e.getY() < lim) {
                long now = SystemClock.elapsedRealtime();
                if (now - cornerFirst > 3000) { cornerFirst = now; cornerTaps = 0; }
                if (++cornerTaps >= 5) { cornerTaps = 0; showSettings(); }
            }
        }
        return super.dispatchTouchEvent(e);
    }

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);

        TextView st = new TextView(this);
        st.setText("앱 " + BuildConfig.VERSION_NAME + "\n" + uvc.stateText() + "\n\n시작 주소 (처음 한 번 ?key=토큰 붙여서 저장):");
        box.addView(st);

        EditText url = new EditText(this);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(startUrl());
        box.addView(url);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("포토부스 설정")
                .setView(box)
                .setPositiveButton("저장 후 열기", (d, w) -> {
                    String u = url.getText().toString().trim();
                    if (u.isEmpty()) u = DEFAULT_URL;
                    prefs.edit().putString("url", u).apply();
                    web.loadUrl(u);
                })
                .setNeutralButton("카메라 다시 연결", (d, w) -> uvc.restart())
                .setNegativeButton("새로고침", (d, w) -> { web.clearCache(true); web.loadUrl(startUrl()); })
                .create();
        dlg.setOnDismissListener(d -> hideSystemBars());
        dlg.show();
    }

    // =========================================================
    // 웹페이지 ↔ 앱
    // =========================================================
    private class BoothClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            String path = u.getPath();
            if (path == null || !path.endsWith(CAM_PATH)) return null;
            long after = 0;
            try { after = Long.parseLong(u.getQueryParameter("after")); } catch (Exception ignored) {}
            UvcSource.Frame f = uvc.waitFrame(after, 150);
            Map<String, String> h = new HashMap<>();
            h.put("Cache-Control", "no-store");
            h.put("Access-Control-Allow-Origin", "*");
            h.put("Access-Control-Expose-Headers", "X-Frame-No");
            if (f == null) {
                return new WebResourceResponse("text/plain", "utf-8", 503, "No Frame", h, new ByteArrayInputStream(new byte[0]));
            }
            h.put("X-Frame-No", String.valueOf(f.seq));
            return new WebResourceResponse("image/jpeg", null, 200, "OK", h, new ByteArrayInputStream(f.data, 0, f.len));
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            if ("https".equals(u.getScheme()) && ALLOWED_HOST.equals(u.getHost())) return false;
            // 포토부스 밖으로 나가는 링크는 열지 않음 (키오스크)
            Log.i(TAG, "blocked navigation: " + u);
            return true;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            hideSystemBars();
        }
    }

    /** window.BoothNative */
    private class Bridge {
        @JavascriptInterface public String camStatus() { return uvc.statusJson(); }
        @JavascriptInterface public void restartCamera() { uvc.restart(); }
        @JavascriptInterface public String appVersion() { return BuildConfig.VERSION_NAME; }
        @JavascriptInterface public void openSettings() { ui.post(MainActivity.this::showSettings); }
    }
}
