package com.f45seongsu.booth;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.util.Log;

import java.io.ByteArrayOutputStream;

/**
 * Bulk 전송 UVC 카메라(Insta360 Link 2C 등)를 안드로이드 USB API만으로 직접 읽는다.
 *
 * - 디스크립터에서 MJPEG 포맷/해상도 찾기 → PROBE/COMMIT 으로 스트리밍 시작
 * - bulk 엔드포인트에서 payload(헤더+데이터)를 읽어 한 장의 JPEG로 조립
 * - 디코딩/재압축 없이 카메라 JPEG 를 그대로 넘김 (빠르고 화질 손실 없음)
 *
 * 라이브러리(UVCAndroid)는 이 카메라에서 USB 데이터는 받는데 디코딩 단계에서 멈춰서 따로 만듦.
 */
class BulkUvc {
    private static final String TAG = "BoothBulk";

    interface Sink { void onJpeg(byte[] buf, int len, int w, int h); }

    static final class Mode {
        int formatIndex, frameIndex, width, height, interval;
        @Override public String toString() { return width + "x" + height + " f" + formatIndex + "/" + frameIndex + " iv" + interval; }
    }

    private final UsbDeviceConnection conn;
    private final UsbInterface vsIf;
    private final UsbEndpoint ep;
    private final Sink sink;
    private volatile boolean running;
    private Thread thread;
    Mode mode;
    int maxPayload, maxFrame;
    volatile long payloads, frames, errors;
    volatile String lastError = "";

    private BulkUvc(UsbDeviceConnection c, UsbInterface itf, UsbEndpoint e, Sink s) {
        conn = c; vsIf = itf; ep = e; sink = s;
    }

    /** 이 장치의 영상 인터페이스가 bulk 엔드포인트를 쓰면 BulkUvc 생성, 아니면 null */
    static BulkUvc create(UsbDevice d, UsbDeviceConnection c, Sink s) {
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface itf = d.getInterface(i);
            if (itf.getInterfaceClass() != UsbConstants.USB_CLASS_VIDEO || itf.getInterfaceSubclass() != 2) continue;
            for (int e = 0; e < itf.getEndpointCount(); e++) {
                UsbEndpoint ep = itf.getEndpoint(e);
                if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.getDirection() == UsbConstants.USB_DIR_IN) {
                    return new BulkUvc(c, itf, ep, s);
                }
            }
        }
        return null;
    }

    private static int u16(byte[] b, int i) { return (b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8); }
    private static int u32(byte[] b, int i) { return u16(b, i) | (u16(b, i + 2) << 16); }

    /** 원하는 해상도에 가장 가까운 MJPEG 모드 (가로 maxW 이하 중 가장 큰 16:9 우선) */
    Mode pickMode(int wantW, int wantH) {
        byte[] raw = conn.getRawDescriptors();
        if (raw == null) return null;
        int curIf = -1, curClass = -1, curSub = -1, mjpegFormat = -1;
        Mode best = null;
        int bestScore = Integer.MAX_VALUE;
        for (int i = 0; i + 2 < raw.length; ) {
            int len = raw[i] & 0xFF, type = raw[i + 1] & 0xFF;
            if (len < 2 || i + len > raw.length) break;
            if (type == 0x04 && len >= 9) {            // INTERFACE
                curIf = raw[i + 2] & 0xFF; curClass = raw[i + 5] & 0xFF; curSub = raw[i + 6] & 0xFF;
            } else if (type == 0x24 && curClass == 14 && curSub == 2 && curIf == vsIf.getId()) { // CS_INTERFACE (VS)
                int sub = raw[i + 2] & 0xFF;
                if (sub == 0x06) {                      // VS_FORMAT_MJPEG
                    mjpegFormat = raw[i + 3] & 0xFF;
                } else if (sub == 0x07 && mjpegFormat > 0 && len >= 26) { // VS_FRAME_MJPEG
                    Mode m = new Mode();
                    m.formatIndex = mjpegFormat;
                    m.frameIndex = raw[i + 3] & 0xFF;
                    m.width = u16(raw, i + 5);
                    m.height = u16(raw, i + 7);
                    int def = u32(raw, i + 21);
                    int nIv = raw[i + 25] & 0xFF;
                    m.interval = def;
                    for (int k = 0; k < nIv && i + 26 + k * 4 + 4 <= i + len; k++) {
                        if (u32(raw, i + 26 + k * 4) == 333333) m.interval = 333333;   // 30fps 있으면 30fps
                    }
                    // 가로 영상만, 원하는 크기와의 차이가 작은 것
                    if (m.width >= m.height) {
                        int score = Math.abs(m.width - wantW) * 4 + Math.abs(m.height - wantH) * 4 + (m.width > wantW ? 10000 : 0);
                        if (score < bestScore) { bestScore = score; best = m; }
                    }
                } else if (sub != 0x07 && sub != 0x06 && sub != 0x0D && sub != 0x01 && sub != 0x02) {
                    // 다른 포맷(H.264 등)이 시작되면 MJPEG 프레임 수집 끝
                    if (sub == 0x04 || sub == 0x10 || sub == 0x13) mjpegFormat = -1;
                }
            }
            i += len;
        }
        return best;
    }

    /** PROBE → COMMIT, 성공하면 읽기 스레드 시작 */
    boolean start(Mode m) {
        mode = m;
        if (!conn.claimInterface(vsIf, true)) { lastError = "claimInterface 실패"; return false; }
        conn.setInterface(vsIf);   // alt 0
        byte[] probe = new byte[48];
        int ifn = vsIf.getId();
        // 현재값 읽어서 길이 확인 (UVC 1.0=26, 1.1=34, 1.5=48)
        int plen = conn.controlTransfer(0xA1, 0x81, 0x01 << 8, ifn, probe, probe.length, 1000);
        if (plen < 26) plen = 34;
        probe[0] = 1; probe[1] = 0;                       // bmHint: dwFrameInterval 고정
        probe[2] = (byte) m.formatIndex;
        probe[3] = (byte) m.frameIndex;
        probe[4] = (byte) m.interval; probe[5] = (byte) (m.interval >> 8);
        probe[6] = (byte) (m.interval >> 16); probe[7] = (byte) (m.interval >> 24);
        int r = conn.controlTransfer(0x21, 0x01, 0x01 << 8, ifn, probe, plen, 1000);   // SET_CUR PROBE
        if (r < 0) { lastError = "PROBE SET 실패 " + r; return false; }
        r = conn.controlTransfer(0xA1, 0x81, 0x01 << 8, ifn, probe, plen, 1000);       // GET_CUR PROBE
        if (r < 0) { lastError = "PROBE GET 실패 " + r; return false; }
        maxFrame = u32(probe, 18);
        maxPayload = u32(probe, 22);
        r = conn.controlTransfer(0x21, 0x01, 0x02 << 8, ifn, probe, plen, 1000);       // SET_CUR COMMIT
        if (r < 0) { lastError = "COMMIT 실패 " + r; return false; }
        Log.i(TAG, "commit " + m + " maxFrame=" + maxFrame + " maxPayload=" + maxPayload + " plen=" + plen);
        running = true;
        thread = new Thread(this::readLoop, "booth-bulk-uvc");
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
        return true;
    }

    void stop() {
        running = false;
        Thread t = thread;
        if (t != null) { try { t.join(1500); } catch (InterruptedException ignored) {} }
        thread = null;
        try { conn.setInterface(vsIf); } catch (Exception ignored) {}
        try { conn.releaseInterface(vsIf); } catch (Exception ignored) {}
    }

    private void readLoop() {
        int chunk = maxPayload > 0 ? maxPayload : 32768;
        if (chunk > 512 * 1024) chunk = 512 * 1024;
        byte[] buf = new byte[chunk];
        ByteArrayOutputStream frame = new ByteArrayOutputStream(Math.max(maxFrame, 256 * 1024));
        int lastFid = -1;
        boolean bad = false;
        int timeouts = 0;
        while (running) {
            int n = conn.bulkTransfer(ep, buf, buf.length, 1000);
            if (n < 0) {
                if (++timeouts % 5 == 0) Log.w(TAG, "bulk read timeout x" + timeouts);
                errors++;
                continue;
            }
            timeouts = 0;
            if (n < 2) continue;
            payloads++;
            int hl = buf[0] & 0xFF;
            int info = buf[1] & 0xFF;
            if (hl < 2 || hl > n) { bad = true; continue; }   // 헤더 이상 → 이번 프레임 버림
            int fid = info & 0x01;
            boolean eof = (info & 0x02) != 0;
            boolean err = (info & 0x40) != 0;
            if (lastFid != -1 && fid != lastFid && frame.size() > 0) {
                emit(frame, bad);   // FID 바뀜 = 이전 프레임 끝 (EOF 안 보내는 카메라 대비)
                frame.reset(); bad = false;
            }
            lastFid = fid;
            if (err) bad = true;
            if (n > hl) frame.write(buf, hl, n - hl);
            if (eof) {
                emit(frame, bad);
                frame.reset(); bad = false;
            }
        }
    }

    private void emit(ByteArrayOutputStream f, boolean bad) {
        int len = f.size();
        if (bad || len < 4) return;
        byte[] b = f.toByteArray();
        if ((b[0] & 0xFF) != 0xFF || (b[1] & 0xFF) != 0xD8) return;   // JPEG 시작 아님
        frames++;
        sink.onJpeg(b, len, mode.width, mode.height);
    }

    String describe() {
        return "bulk " + mode + " payload=" + maxPayload + " pkts=" + payloads + " frames=" + frames + " err=" + errors
                + (lastError.isEmpty() ? "" : " " + lastError);
    }
}
