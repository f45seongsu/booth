package com.f45seongsu.booth;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.PixelFormat;
import android.graphics.YuvImage;
import android.media.Image;
import android.media.ImageReader;
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
 * - MJPEG 1920x1080 우선. 라이브러리가 디코딩한 프레임을 NV21로 받아 요청이 올 때만 JPEG로 압축.
 * - 라이브러리는 미리보기 Surface가 없으면 스트리밍을 아예 시작하지 않음 → 안 보이는 ImageReader Surface를 붙임.
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
    private BulkUvc bulk;       // bulk 전송 카메라 직접 읽기 (Insta360 Link 2C)
    private volatile UvcControls controls;   // 화질 조정 (bulk 모드에서만)
    UvcControls controls() { return controls; }
    android.content.SharedPreferences prefs() { return ctx.getSharedPreferences("booth", Context.MODE_PRIVATE); }
    private boolean active() { return camera != null || bulk != null; }
    private UsbDevice device;
    private ImageReader sink;   // 라이브러리가 요구하는 미리보기 Surface (화면에 안 보임, 받자마자 버림)

    // ---- 프레임 버퍼 (lock으로 보호) ----
    private final Object lock = new Object();
    private byte[] raw = new byte[0];
    private int rawLen;
    private boolean rawIsJpeg;
    private long seq;
    private long lastFrameAt;
    private Frame jpegCache;
    private long callbacks;
    private long sinkFrames;
    private int rawStride;   // rawIsRgba일 때 한 줄 바이트 수
    private boolean rawIsRgba;

    // ---- 상태 (웹/설정 화면 표시용) ----
    private volatile String state = "idle";
    private volatile String error = "";
    private volatile String deviceName = "";
    private volatile int width, height, fps, frameType;
    private volatile String mode = "";
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
            noFrameOpens = 0;   // 직접 다시 연결하면 처음 해상도부터
            state = "searching";
            pickAndRequest();
        });
    }

    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            try {
                long now = SystemClock.elapsedRealtime();
                long last, cbs;
                synchronized (lock) { last = lastFrameAt; cbs = callbacks; }
                if (active() && ("streaming".equals(state) || "starting".equals(state))) {
                    long since = now - (last > 0 ? last : openedAt);
                    if (since > STALL_MS) {
                        Log.w(TAG, "no frames for " + since + "ms → reopen");
                        restarts++;
                        if (last == 0) {
                            noFrameOpens++;   // 한 장도 못 받음 → 다음엔 해상도 낮춰봄
                            error = "프레임 없음 (콜백 " + cbs + ", 재시도 " + noFrameOpens + ")";
                        }
                        lastLog = recentLog(4, true);
                        UsbDevice d = device;
                        closeCamera();
                        state = "reconnecting";
                        if (d != null && monitor != null) monitor.requestPermission(d);
                    }
                } else if (!active() && device == null && !"permission-denied".equals(state)) {
                    pickAndRequest();
                }
            } catch (Exception e) {
                Log.w(TAG, "watchdog", e);
            }
            worker.postDelayed(this, 2000);
        }
    };
    private long openedAt;
    private volatile String attemptDesc = "";
    private volatile String lastLog = "";
    private volatile String supportedJson = "";
    /** {최대 가로, MJPEG만(1)/무압축(0), 대역폭 quirk 강제(1)} */
    private static final int[][] ATTEMPTS = {
            {1920, 1, 0}, {1920, 1, 1}, {1280, 1, 1}, {1280, 1, 0}, {640, 1, 1}, {640, 0, 1},
    };
    private volatile int noFrameOpens;   // 열었는데 프레임이 한 장도 안 온 횟수

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
        if (monitor == null || active()) return;
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
        worker.post(() -> { if (!active()) pickAndRequest(); });
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
        // 1) bulk 전송 카메라면 직접 읽기 (처음 3번), 실패하면 라이브러리 방식
        if (noFrameOpens < 3 && openBulk(d, ctrlBlock)) return;
        try {
            // 프레임이 안 오면 조합을 바꿔가며 재시도 (MediaTek 등은 대역폭 quirk가 필요)
            int a = noFrameOpens % ATTEMPTS.length;
            int maxW = ATTEMPTS[a][0];
            boolean mjpegOnly = ATTEMPTS[a][1] == 1;
            int quirks = ATTEMPTS[a][2] == 1 ? UVCCamera.UVC_QUIRK_FIX_BANDWIDTH : UVCCamera.getRecommendedPlatformQuirks();
            attemptDesc = "#" + (a + 1) + " ≤" + maxW + (mjpegOnly ? " MJPEG" : " YUYV") + (quirks != 0 ? " +BW" : "");
            UVCParam param = new UVCParam();
            param.setQuirks(quirks);
            cam = new UVCCamera(param);
            int r = cam.open(ctrlBlock);
            if (r != 0) throw new IllegalStateException("open() = " + r);

            List<Size> sizes = cam.getSupportedSizeList();
            Log.i(TAG, "sizes: " + sizes);
            try { supportedJson = cam.getSupportedSize(); } catch (Exception e) { supportedJson = "err " + e; }
            Size used = null;
            List<Size> cands = candidates(sizes);
            cands.removeIf(s -> s.width > maxW || (mjpegOnly != (s.type == UVCCamera.UVC_VS_FRAME_MJPEG)));
            if (cands.isEmpty()) cands = candidates(sizes);
            for (Size s : cands) {
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
                rawLen = 0; seq = 0; lastFrameAt = 0; jpegCache = null; callbacks = 0; sinkFrames = 0;
            }
            Size cur = cam.getPreviewSize();
            if (cur != null && cur.width > 0) { width = cur.width; height = cur.height; }
            sink = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
            sink.setOnImageAvailableListener(reader -> {
                try (Image im = reader.acquireLatestImage()) {
                    if (im != null) onSinkImage(im);
                } catch (Exception e) {
                    Log.w(TAG, "sink", e);
                }
            }, worker);
            cam.setPreviewDisplay(sink.getSurface());
            cam.setFrameCallback(frameCallback, UVCCamera.PIXEL_FORMAT_NV21);
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
            worker.postDelayed(() -> { if (!active() && device != null && monitor != null) monitor.requestPermission(device); }, 3000);
        }
    }

    private boolean openBulk(UsbDevice d, USBMonitor.UsbControlBlock ctrlBlock) {
        try {
            android.hardware.usb.UsbDeviceConnection c = ctrlBlock.getConnection();
            if (c == null) { ctrlBlock.open(); c = ctrlBlock.getConnection(); }
            if (c == null) { error = "USB 연결 못 엶"; return false; }
            BulkUvc b = BulkUvc.create(d, c, this::onBulkJpeg);
            if (b == null) return false;                 // isochronous 카메라 → 라이브러리
            // 1920x1440(4:3)이 세로 네컷 칸에 쓸 수 있는 화소가 가장 많음 → 우선
            // 숨은 설정 "4K 촬영(실험)" 켜면 3840x2160 먼저 (세로 칸 1728x2160 → 화소 2배 이상). 안 되면 자동으로 아래 해상도
            int[][] sizes = prefs().getBoolean("cam_4k", false)
                    ? new int[][]{{3840, 2160}, {1920, 1440}, {1920, 1080}}
                    : new int[][]{{1920, 1440}, {1920, 1080}, {1280, 720}};
            int[] want = sizes[Math.min(noFrameOpens, sizes.length - 1)];
            BulkUvc.Mode m = b.pickMode(want[0], want[1]);
            attemptDesc = "bulk#" + (noFrameOpens + 1) + " " + (m != null ? m.toString() : "모드 없음");
            if (m == null) { error = "MJPEG 모드 못 찾음"; return false; }
            synchronized (lock) {
                rawLen = 0; seq = 0; lastFrameAt = 0; jpegCache = null; callbacks = 0; sinkFrames = 0;
            }
            width = m.width; height = m.height; fps = m.interval > 0 ? Math.round(1e7f / m.interval) : 30;
            frameType = UVCCamera.UVC_VS_FRAME_MJPEG;
            if (!b.start(m)) {
                error = b.lastError;
                Log.w(TAG, "bulk start failed: " + b.lastError);
                b.stop();
                return false;
            }
            bulk = b;
            try {
                UvcControls uc = UvcControls.probe(c);
                if (uc != null) { uc.applySaved(prefs()); controls = uc; Log.i(TAG, "controls " + uc.summary()); }
            } catch (Exception e) { Log.w(TAG, "controls", e); }
            openedAt = SystemClock.elapsedRealtime();
            state = "starting";
            Log.i(TAG, "bulk preview " + m);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "openBulk", e);
            error = "bulk: " + e.getMessage();
            return false;
        }
    }

    /** BulkUvc 읽기 스레드에서 호출: 카메라 JPEG 그대로 저장 */
    private void onBulkJpeg(byte[] b, int len, int w, int h) {
        synchronized (lock) {
            callbacks++;
            raw = b;                 // BulkUvc가 매번 새 배열을 넘김 → 복사 불필요
            rawLen = len;
            rawIsJpeg = true;
            rawIsRgba = false;
            seq++;
            jpegCache = null;
            lastFrameAt = SystemClock.elapsedRealtime();
            lock.notifyAll();
        }
        if (!"streaming".equals(state)) {
            mode = "bulk-mjpeg";
            state = "streaming";
            error = "";
            noFrameOpens = 0;
        }
    }

    private void closeCamera() {
        BulkUvc b = bulk;
        bulk = null;
        controls = null;
        if (b != null) { try { b.stop(); } catch (Exception ignored) {} }
        UVCCamera cam = camera;
        camera = null;
        if (cam == null) {
            synchronized (lock) { seq = 0; rawLen = 0; jpegCache = null; lastFrameAt = 0; }
            return;
        }
        try { cam.setFrameCallback(null, 0); } catch (Exception ignored) {}
        try { cam.stopPreview(); } catch (Exception ignored) {}
        try { cam.destroy(); } catch (Exception ignored) {}
        if (sink != null) { try { sink.close(); } catch (Exception ignored) {} sink = null; }
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
            int need = width * height * 3 / 2;
            synchronized (lock) {
                callbacks++;
                if (need <= 0 || buf.remaining() < need) return;
                if (raw.length < need) raw = new byte[need];
                buf.get(raw, 0, need);
                rawLen = need;
                rawIsJpeg = false;
                rawIsRgba = false;
                seq++;
                jpegCache = null;
                lastFrameAt = SystemClock.elapsedRealtime();
                lock.notifyAll();
            }
            if (!"streaming".equals(state)) {
                mode = "nv21-to-jpeg";
                state = "streaming";
                error = "";
                noFrameOpens = 0;
            }
        }
    };

    /** 미리보기 Surface로 들어온 RGBA 프레임. NV21 콜백이 안 오는 기기에서는 이걸 대신 씀 */
    private void onSinkImage(Image im) {
        Image.Plane pl = im.getPlanes()[0];
        ByteBuffer b = pl.getBuffer();
        int stride = pl.getRowStride();
        int h = im.getHeight();
        synchronized (lock) {
            sinkFrames++;
            if (callbacks > 0) return;              // 정상 경로(NV21 콜백)가 살아 있으면 안 씀
            int n = Math.min(b.remaining(), stride * h);
            if (raw.length < n) raw = new byte[n];
            b.get(raw, 0, n);
            rawLen = n;
            rawStride = stride;
            rawIsRgba = true;
            rawIsJpeg = false;
            seq++;
            jpegCache = null;
            lastFrameAt = SystemClock.elapsedRealtime();
            lock.notifyAll();
        }
        if (!"streaming".equals(state)) {
            mode = "surface-rgba";
            state = "streaming";
            error = "";
            noFrameOpens = 0;
        }
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
                Frame f = new Frame(raw, len, s);   // bulk 경로는 프레임마다 새 배열이라 복사 불필요
                jpegCache = f;
                return f;
            }
            copy = java.util.Arrays.copyOf(raw, len);
            w = width;
            h = height;
            if (rawIsRgba) {
                int stride = rawStride;
                try {
                    Bitmap bm = Bitmap.createBitmap(stride / 4, len / stride, Bitmap.Config.ARGB_8888);
                    bm.copyPixelsFromBuffer(ByteBuffer.wrap(copy, 0, (stride / 4) * 4 * (len / stride)));
                    if (bm.getWidth() != w || bm.getHeight() != h) {
                        Bitmap c = Bitmap.createBitmap(bm, 0, 0, Math.min(w, bm.getWidth()), Math.min(h, bm.getHeight()));
                        bm.recycle();
                        bm = c;
                    }
                    ByteArrayOutputStream out = new ByteArrayOutputStream(w * h / 4);
                    bm.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out);
                    bm.recycle();
                    byte[] j = out.toByteArray();
                    Frame f = new Frame(j, j.length, s);
                    jpegCache = f;
                    return f;
                } catch (Exception e) {
                    Log.w(TAG, "rgba encode", e);
                    return null;
                }
            }
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

    /**
     * 이 앱 프로세스의 logcat에서 카메라 관련 경고/오류 줄만 뽑음 (현장 진단용).
     * 네이티브 UVC 라이브러리(libuvc/libusb/UVCPreview) 로그도 여기 같이 찍힘.
     */
    public static String recentLog(int maxLines, boolean errorsOnly) {
        java.util.ArrayDeque<String> out = new java.util.ArrayDeque<>();
        try {
            Process p = Runtime.getRuntime().exec(new String[]{
                    "logcat", "-d", "-v", "brief", "-t", "1500", "--pid=" + android.os.Process.myPid()});
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.length() < 3 || line.charAt(1) != '/') continue;
                char lv = line.charAt(0);
                String low = line.toLowerCase(java.util.Locale.US);
                if (low.contains("chromium") || low.contains("cr_") || low.contains("[js]")) continue;
                boolean cam = low.contains("uvc") || low.contains("usb") || low.contains("booth") || low.contains("preview")
                        || low.contains("stream") || low.contains("frame") || low.contains("camera");
                if (!cam) continue;
                if (errorsOnly && lv != 'E' && lv != 'W' && lv != 'F') continue;
                out.addLast(line.length() > 220 ? line.substring(0, 220) : line);
                if (out.size() > maxLines) out.removeFirst();
            }
            r.close();
            p.destroy();
        } catch (Exception e) {
            out.add("logcat 실패: " + e);
        }
        return String.join("\n", out);
    }

    /** 기기·USB 장치 상세 정보 (원격 진단용) */
    public String diagInfo() {
        StringBuilder b = new StringBuilder();
        b.append("device: ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL)
                .append(" | hw=").append(android.os.Build.HARDWARE).append(" board=").append(android.os.Build.BOARD)
                .append(" | android ").append(android.os.Build.VERSION.RELEASE).append(" sdk ").append(android.os.Build.VERSION.SDK_INT);
        if (android.os.Build.VERSION.SDK_INT >= 31) b.append(" | soc=").append(android.os.Build.SOC_MANUFACTURER).append(' ').append(android.os.Build.SOC_MODEL);
        b.append("\nrecommendedQuirks=").append(UVCCamera.getRecommendedPlatformQuirks());
        b.append("\nattempt=").append(attemptDesc).append(" state=").append(state).append(" err=").append(error);
        try {
            android.hardware.usb.UsbManager um = (android.hardware.usb.UsbManager) ctx.getSystemService(Context.USB_SERVICE);
            for (UsbDevice d : um.getDeviceList().values()) {
                b.append("\n\nUSB ").append(describe(d)).append(" name=").append(d.getDeviceName())
                        .append(" class=").append(d.getDeviceClass()).append('/').append(d.getDeviceSubclass())
                        .append(" ver=").append(d.getVersion()).append(" perm=").append(um.hasPermission(d))
                        .append(" mfr=").append(d.getManufacturerName());
                for (int i = 0; i < d.getInterfaceCount(); i++) {
                    android.hardware.usb.UsbInterface itf = d.getInterface(i);
                    b.append("\n  if").append(itf.getId()).append(" alt").append(itf.getAlternateSetting())
                            .append(" class=").append(itf.getInterfaceClass()).append('/').append(itf.getInterfaceSubclass());
                    for (int e = 0; e < itf.getEndpointCount(); e++) {
                        android.hardware.usb.UsbEndpoint ep = itf.getEndpoint(e);
                        b.append(" [ep").append(Integer.toHexString(ep.getAddress())).append(" t").append(ep.getType())
                                .append(" mps").append(ep.getMaxPacketSize()).append(" iv").append(ep.getInterval()).append(']');
                    }
                }
            }
        } catch (Exception e) {
            b.append("\nusb dump err ").append(e);
        }
        UvcControls uc = controls;
        if (uc != null) b.append("\ncontrols: ").append(uc.summary());
        b.append("\n\nsupported: ").append(supportedJson);
        return b.toString();
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
            long cb; synchronized (lock) { cb = callbacks; }
            o.put("callbacks", cb);
            long sk; synchronized (lock) { sk = sinkFrames; }
            o.put("sink", sk);
            o.put("attempt", attemptDesc);
            BulkUvc bb = bulk;
            if (bb != null) o.put("bulk", bb.describe());
            o.put("log", lastLog);
            o.put("lastFrameMs", last > 0 ? SystemClock.elapsedRealtime() - last : -1);
            o.put("restarts", restarts);
            return o.toString();
        } catch (Exception e) {
            return "{\"state\":\"error\"}";
        }
    }

    public String stateText() {
        switch (state) {
            case "streaming": return "카메라 연결됨 · " + deviceName + " · " + width + "x" + height + " " + mode + " " + attemptDesc;
            case "no-device": return "USB 카메라가 안 보여요 (케이블/허브 확인)";
            case "permission": return "USB 권한 요청 중…";
            case "permission-denied": return "USB 권한이 거부됐어요. '카메라 다시 연결'을 누르고 허용해주세요";
            case "error": return "카메라 오류: " + error;
            default: return state + (deviceName.isEmpty() ? "" : " · " + deviceName) + " · " + attemptDesc + (error.isEmpty() ? "" : " · " + error);
        }
    }
}
