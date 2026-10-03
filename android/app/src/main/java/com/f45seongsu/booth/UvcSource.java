package com.f45seongsu.booth;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import com.serenegiant.usb.IFrameCallback;
import com.serenegiant.usb.Size;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;
import com.serenegiant.usb.UVCParam;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * USB UVC 카메라(Insta360 Link 2C / OBSBOT 등)를 직접 열어서 최신 프레임을 JPEG로 들고 있는다.
 * 웹페이지는 MainActivity가 가로채는 /__booth_cam/frame.jpg 로 프레임을 가져간다.
 *
 * - MJPEG 1920x1080 우선. 라이브러리가 MJPEG 원본을 그대로 주면 재압축 없이 전달,
 *   아니면 NV21로 받아서 요청이 올 때만 JPEG로 압축.
 * - 4초 이상 프레임이 없으면 카메라를 다시 연다.
 */
public class UvcSource implements USBMonitor.OnDeviceConnectListener {
    private static final String TAG = "BoothUvc";
    static final int INSTA360_VID = 0x2E1A;
    static final int LINK2C_PID = 0x4C03;
    private static final int JPEG_QUALITY = 85;
    private static final long STALL_MS = 4000;

    /** 웹으로 넘길 프레임 한 장 */
    public static final class Frame {
        public final byte[] data;
        public final int len;
        public final long seq;
        Frame(byte[] d, int l, long s) { data = d; len = l; seq = s; }
    }

    private final Context ctx;
    private final HandlerThread thread;
    private final Handler worker;
    private USBMonitor monitor;
    private UVCCamera camera;
    private UsbDevice device;

    // ---- 프레임 버퍼 (lock으로 보호) ----
    private final Object lock = new Object();
    private byte[] raw = new byte[0];
    private int rawLen;
    private boolean rawIsJpeg;
    private long seq;
    private long lastFrameAt;
    private Frame jpegCache;
    private boolean switchingFormat;

    // ---- 상태 (웹/설정 화면 표시용) ----
    private volatile String state = "idle";
    private volatile String error = "";
    private volatile String deviceName = "";
    private volatile int width, height, fps, frameType;
    private volatile String mode = "";
    private volatile int pixelFormat = UVCCamera.PIXEL_FORMAT_RAW;
    private volatile int restarts;

    public UvcSource(Context context) {
        ctx = context.getApplicationContext();
        thread = new HandlerThread("booth-uvc");
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    // =========================================================
    // 시작 / 종료
    // =========================================================
    public void start() {
        worker.post(() -> {
            if (monitor == null) {
                monitor = new USBMonitor(ctx, this);
                monitor.register();
                state = "searching";
            }
            pickAndRequest();
            worker.removeCallbacks(watchdog);
            worker.postDelayed(watchdog, 2000);
        });
    }

    public void destroy() {
        worker.removeCallbacksAndMessages(null);
        worker.post(() -> {
            closeCamera();
            if (monitor != null) { try { monitor.unregister(); monitor.destroy(); } catch (Exception ignored) {} monitor = null; }
            thread.quitSafely();
        });
    }

    /** 웹페이지나 설정 화면에서 "카메라 다시 연결" */
    public void restart() {
        worker.post(() -> {
            closeCamera();
            device = null;
            state = "searching";
            pickAndRequest();
        });
    }

    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            try {
                long now = SystemClock.elapsedRealtime();
                long last;
                synchronized (lock) { last = lastFrameAt; }
                if (camera != null && ("streaming".equals(state) || "starting".equals(state))) {
                    long since = now - (last > 0 ? last : openedAt);
                    if (since > STALL_MS) {
                        Log.w(TAG, "no frames for " + since + "ms → reopen");
                        restarts++;
                        UsbDevice d = device;
                        closeCamera();
                        state = "reconnecting";
                        if (d != null && monitor != null) monitor.requestPermission(d);
                    }
                } else if (camera == null && device == null && !"permission-denied".equals(state)) {
                    pickAndRequest();
                }
            } catch (Exception e) {
                Log.w(TAG, "watchdog", e);
            }
            worker.postDelayed(this, 2000);
        }
    };
    private long openedAt;

    // =========================================================
    // 장치 고르기: Insta360 → 그 외 UVC
    // =========================================================
    private static boolean isUvc(UsbDevice d) {
        if (d.getDeviceClass() == UsbConstants.USB_CLASS_VIDEO) return true;
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            if (d.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_VIDEO) return true;
        }
        return false;
    }

    private void pickAndRequest() {
        if (monitor == null || camera != null) return;
        UsbDevice best = null;
        for (UsbDevice d : monitor.getDeviceList()) {
            if (!isUvc(d)) continue;
            if (best == null || (d.getVendorId() == INSTA360_VID && best.getVendorId() != INSTA360_VID)) best = d;
        }
        if (best == null) {
            if (!"permission-denied".equals(state)) state = "no-device";
            return;
        }
        device = best;
        deviceName = describe(best);
        state = "permission";
        monitor.requestPermission(best);  // 이미 허용돼 있으면 바로 onDeviceOpen
    }

    private static String describe(UsbDevice d) {
        String n = d.getProductName();
        return (n != null ? n : "USB Camera") + String.format(" (%04X:%04X)", d.getVendorId(), d.getProductId());
    }

    // =========================================================
    // USBMonitor 콜백
    // =========================================================
    @Override public void onAttach(UsbDevice d) {
        Log.i(TAG, "attach " + describe(d));
        worker.post(() -> { if (camera == null) pickAndRequest(); });
    }

    @Override public void onDetach(UsbDevice d) {
        Log.i(TAG, "detach " + describe(d));
        worker.post(() -> {
            if (device != null && device.getDeviceName().equals(d.getDeviceName())) {
                closeCamera();
                device = null;
                state = "no-device";
            }
        });
    }

    @Override public void onDeviceOpen(UsbDevice d, USBMonitor.UsbControlBlock ctrlBlock, boolean createNew) {
        Log.i(TAG, "open " + describe(d));
        worker.post(() -> openCamera(d, ctrlBlock));
    }

    @Override public void onDeviceClose(UsbDevice d, USBMonitor.UsbControlBlock ctrlBlock) {
        Log.i(TAG, "close " + describe(d));
    }

    @Override public void onCancel(UsbDevice d) {
        Log.w(TAG, "permission denied " + describe(d));
        worker.post(() -> { state = "permission-denied"; error = "USB 권한이 거부됨"; device = null; });
    }

    // =========================================================
    // 카메라 열기 / 닫기
    // =========================================================
    private void openCamera(UsbDevice d, USBMonitor.UsbControlBlock ctrlBlock) {
        closeCamera();
        device = d;
        deviceName = describe(d);
        state = "opening";
        error = "";
        UVCCamera cam = null;
        try {
            cam = new UVCCamera(new UVCParam());
            int r = cam.open(ctrlBlock);
            if (r != 0) throw new IllegalStateException("open() = " + r);

            List<Size> sizes = cam.getSupportedSizeList();
            Log.i(TAG, "sizes: " + sizes);
            Size used = null;
            for (Size s : candidates(sizes)) {
                try {
                    int f = pickFps(s);
                    cam.setPreviewSize(s.width, s.height, s.type, f);
                    used = s;
                    fps = f;
                    break;
                } catch (Exception e) {
                    Log.w(TAG, "size rejected " + s + ": " + e);
                }
            }
            if (used == null) throw new IllegalStateException("지원하는 해상도를 못 찾음");
            width = used.width;
            height = used.height;
            frameType = used.type;

            synchronized (lock) {
                rawLen = 0; seq = 0; lastFrameAt = 0; jpegCache = null; switchingFormat = false;
            }
            pixelFormat = UVCCamera.PIXEL_FORMAT_RAW;  // MJPEG 원본을 그대로 받아보고, 아니면 NV21로 전환
            cam.setFrameCallback(frameCallback, pixelFormat);
            cam.startPreview();
            camera = cam;
            openedAt = SystemClock.elapsedRealtime();
            state = "starting";
            Log.i(TAG, "preview " + width + "x" + height + "@" + fps + " type=" + frameType);
        } catch (Exception e) {
            Log.e(TAG, "openCamera failed", e);
            error = String.valueOf(e.getMessage());
            state = "error";
            if (cam != null) { try { cam.destroy(); } catch (Exception ignored) {} }
            camera = null;
            // 잠시 후 다시 시도
            worker.postDelayed(() -> { if (camera == null && device != null && monitor != null) monitor.requestPermission(device); }, 3000);
        }
    }

    private void closeCamera() {
        UVCCamera cam = camera;
        camera = null;
        if (cam == null) return;
        try { cam.setFrameCallback(null, 0); } catch (Exception ignored) {}
        try { cam.stopPreview(); } catch (Exception ignored) {}
        try { cam.destroy(); } catch (Exception ignored) {}
        synchronized (lock) { seq = 0; rawLen = 0; jpegCache = null; lastFrameAt = 0; }
    }

    /** MJPEG 1920x1080 → MJPEG 1280x720 → 기타 MJPEG(큰 것부터, 가로 1920 이하) → 무압축 */
    private static List<Size> candidates(List<Size> sizes) {
        List<Size> mj = new ArrayList<>(), other = new ArrayList<>(), out = new ArrayList<>();
        if (sizes != null) for (Size s : sizes) {
            if (s.type == UVCCamera.UVC_VS_FRAME_MJPEG) mj.add(s); else other.add(s);
        }
        java.util.Comparator<Size> big = (a, b) -> b.width * b.height - a.width * a.height;
        mj.sort(big);
        other.sort(big);
        for (Size s : mj) if (s.width == 1920 && s.height == 1080) out.add(s);
        for (Size s : mj) if (s.width == 1280 && s.height == 720) out.add(s);
        for (Size s : mj) if (s.width <= 1920 && !out.contains(s)) out.add(s);
        for (Size s : other) if (s.width <= 1280) out.add(s);
        if (out.isEmpty()) {
            Size def = new Size(UVCCamera.UVC_VS_FRAME_MJPEG, 1920, 1080, 30, new ArrayList<>());
            out.add(def);
            out.add(new Size(UVCCamera.UVC_VS_FRAME_MJPEG, 1280, 720, 30, new ArrayList<>()));
        }
        return out;
    }

    private static int pickFps(Size s) {
        if (s.fpsList != null && !s.fpsList.isEmpty()) {
            if (s.fpsList.contains(30)) return 30;
            int best = 0;
            for (Integer f : s.fpsList) if (f != null && f <= 30 && f > best) best = f;
            if (best > 0) return best;
        }
        return s.fps > 0 ? Math.min(s.fps, 30) : 30;
    }

    // =========================================================
    // 프레임 수신 (네이티브 스레드)
    // =========================================================
    private final IFrameCallback frameCallback = new IFrameCallback() {
        @Override public void onFrame(ByteBuffer buf) {
            if (buf == null) return;
            buf.rewind();
            int n = buf.remaining();
            if (n < 4) return;
            synchronized (lock) {
                if (switchingFormat) return;
                int pf = pixelFormat;
                boolean jpeg = false;
                if (pf == UVCCamera.PIXEL_FORMAT_RAW) {
                    jpeg = (buf.get(0) & 0xFF) == 0xFF && (buf.get(1) & 0xFF) == 0xD8;
                    if (!jpeg) {
                        // MJPEG 원본이 아님 → NV21로 받기
                        switchingFormat = true;
                        worker.post(UvcSource.this::switchToNv21);
                        return;
                    }
                } else if (pf == UVCCamera.PIXEL_FORMAT_NV21) {
                    if (n < width * height * 3 / 2) return;
                    n = width * height * 3 / 2;
                }
                if (raw.length < n) raw = new byte[n + (n >> 3)];
                buf.get(raw, 0, n);
                rawLen = jpeg ? jpegEnd(raw, n) : n;
                rawIsJpeg = jpeg;
                seq++;
                jpegCache = null;
                lastFrameAt = SystemClock.elapsedRealtime();
                lock.notifyAll();
            }
            if (!"streaming".equals(state)) {
                mode = rawIsJpeg ? "mjpeg-passthrough" : "nv21-to-jpeg";
                state = "streaming";
            }
        }
    };

    private void switchToNv21() {
        UVCCamera cam = camera;
        if (cam == null) return;
        try {
            pixelFormat = UVCCamera.PIXEL_FORMAT_NV21;
            cam.setFrameCallback(frameCallback, UVCCamera.PIXEL_FORMAT_NV21);
            Log.i(TAG, "frame callback → NV21");
        } catch (Exception e) {
            Log.e(TAG, "switch NV21", e);
        }
        synchronized (lock) { switchingFormat = false; }
    }

    /** MJPEG 버퍼 뒤에 붙은 쓰레기 데이터 잘라내기: SOS(FFDA) 이후 첫 EOI(FFD9)까지 */
    private static int jpegEnd(byte[] b, int n) {
        int i = 2;
        for (; i < n - 1; i++) if ((b[i] & 0xFF) == 0xFF && (b[i + 1] & 0xFF) == 0xDA) break;
        for (; i < n - 1; i++) if ((b[i] & 0xFF) == 0xFF && (b[i + 1] & 0xFF) == 0xD9) return i + 2;
        return n;
    }

    // =========================================================
    // 웹페이지 요청 처리 (WebView IO 스레드)
    // =========================================================
    /** after보다 새 프레임을 최대 waitMs 기다렸다가 JPEG로 돌려준다. 없으면 null */
    public Frame waitFrame(long after, long waitMs) {
        byte[] copy;
        int len, w, h;
        long s;
        synchronized (lock) {
            long end = SystemClock.elapsedRealtime() + waitMs;
            while (seq <= after || rawLen == 0) {
                long left = end - SystemClock.elapsedRealtime();
                if (left <= 0) break;
                try { lock.wait(left); } catch (InterruptedException e) { return null; }
            }
            if (rawLen == 0 || seq == 0) return null;
            if (jpegCache != null && jpegCache.seq == seq) return jpegCache;
            s = seq;
            len = rawLen;
            if (rawIsJpeg) {
                Frame f = new Frame(java.util.Arrays.copyOf(raw, len), len, s);
                jpegCache = f;
                return f;
            }
            copy = java.util.Arrays.copyOf(raw, len);
            w = width;
            h = height;
        }
        // NV21 → JPEG (lock 밖에서 압축해서 카메라 스레드를 막지 않음)
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(w * h / 4);
            new YuvImage(copy, ImageFormat.NV21, w, h, null).compressToJpeg(new Rect(0, 0, w, h), JPEG_QUALITY, out);
            byte[] j = out.toByteArray();
            Frame f = new Frame(j, j.length, s);
            synchronized (lock) { if (seq == s) jpegCache = f; }
            return f;
        } catch (Exception e) {
            Log.w(TAG, "jpeg encode", e);
            return null;
        }
    }

    public String statusJson() {
        try {
            JSONObject o = new JSONObject();
            long last;
            long frames;
            synchronized (lock) { last = lastFrameAt; frames = seq; }
            o.put("state", state);
            o.put("error", error);
            o.put("device", deviceName);
            o.put("width", width);
            o.put("height", height);
            o.put("fps", fps);
            o.put("format", frameType == UVCCamera.UVC_VS_FRAME_MJPEG ? "MJPEG" : ("type" + frameType));
            o.put("mode", mode);
            o.put("frames", frames);
            o.put("lastFrameMs", last > 0 ? SystemClock.elapsedRealtime() - last : -1);
            o.put("restarts", restarts);
            return o.toString();
        } catch (Exception e) {
            return "{\"state\":\"error\"}";
        }
    }

    public String stateText() {
        switch (state) {
            case "streaming": return "카메라 연결됨 · " + deviceName + " · " + width + "x" + height + " " + mode;
            case "no-device": return "USB 카메라가 안 보여요 (케이블/허브 확인)";
            case "permission": return "USB 권한 요청 중…";
            case "permission-denied": return "USB 권한이 거부됐어요. '카메라 다시 연결'을 누르고 허용해주세요";
            case "error": return "카메라 오류: " + error;
            default: return state + (deviceName.isEmpty() ? "" : " · " + deviceName);
        }
    }
}
