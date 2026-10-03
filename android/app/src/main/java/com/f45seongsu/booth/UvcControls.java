package com.f45seongsu.booth;

import android.content.SharedPreferences;
import android.hardware.usb.UsbDeviceConnection;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * UVC 화질 조정(밝기·대비·채도·선명도·노출·화이트밸런스 등)을 USB 제어 요청으로 직접 설정.
 * 값은 SharedPreferences에 저장해서 카메라를 다시 열 때마다 자동 적용.
 */
class UvcControls {
    private static final String TAG = "BoothCtl";

    static final class Ctl {
        final String key, label;
        final boolean camTerminal;   // true=Camera Terminal, false=Processing Unit
        final int selector, size;
        final boolean signed, toggle;
        int min, max, def, cur;
        boolean ok;
        Ctl(String key, String label, boolean ct, int sel, int size, boolean signed, boolean toggle) {
            this.key = key; this.label = label; camTerminal = ct; selector = sel; this.size = size; this.signed = signed; this.toggle = toggle;
        }
    }

    private final UsbDeviceConnection conn;
    private final int vcIf;
    private int ctId = -1, puId = -1;
    final List<Ctl> list = new ArrayList<>();

    private UvcControls(UsbDeviceConnection c, int vcIf) { conn = c; this.vcIf = vcIf; }

    /** 디스크립터에서 Camera Terminal / Processing Unit 찾고 지원 범위 읽기 */
    static UvcControls probe(UsbDeviceConnection c) {
        byte[] raw = c.getRawDescriptors();
        if (raw == null) return null;
        int curClass = -1, curSub = -1, curIf = -1, vcIf = -1, ct = -1, pu = -1;
        for (int i = 0; i + 2 < raw.length; ) {
            int len = raw[i] & 0xFF, type = raw[i + 1] & 0xFF;
            if (len < 2 || i + len > raw.length) break;
            if (type == 0x04 && len >= 9) {
                curIf = raw[i + 2] & 0xFF; curClass = raw[i + 5] & 0xFF; curSub = raw[i + 6] & 0xFF;
                if (curClass == 14 && curSub == 1 && vcIf < 0) vcIf = curIf;
            } else if (type == 0x24 && curClass == 14 && curSub == 1 && len >= 4) {
                int sub = raw[i + 2] & 0xFF;
                if (sub == 0x02 && len >= 8) {             // INPUT_TERMINAL
                    int tt = (raw[i + 4] & 0xFF) | ((raw[i + 5] & 0xFF) << 8);
                    if (tt == 0x0201 && ct < 0) ct = raw[i + 3] & 0xFF;
                } else if (sub == 0x05 && pu < 0) {        // PROCESSING_UNIT
                    pu = raw[i + 3] & 0xFF;
                }
            }
            i += len;
        }
        if (vcIf < 0) return null;
        UvcControls u = new UvcControls(c, vcIf);
        u.ctId = ct; u.puId = pu;
        u.list.add(new Ctl("ae", "자동 노출", true, 0x02, 1, false, true));
        u.list.add(new Ctl("exposure", "노출 시간 (자동 노출 끌 때)", true, 0x04, 4, false, false));
        u.list.add(new Ctl("brightness", "밝기", false, 0x02, 2, true, false));
        u.list.add(new Ctl("contrast", "대비", false, 0x03, 2, false, false));
        u.list.add(new Ctl("saturation", "채도", false, 0x07, 2, false, false));
        u.list.add(new Ctl("sharpness", "선명도", false, 0x08, 2, false, false));
        u.list.add(new Ctl("gamma", "감마", false, 0x09, 2, false, false));
        u.list.add(new Ctl("backlight", "역광 보정", false, 0x01, 2, false, false));
        u.list.add(new Ctl("gain", "감도(게인)", false, 0x04, 2, false, false));
        u.list.add(new Ctl("wbauto", "자동 화이트밸런스", false, 0x0B, 1, false, true));
        u.list.add(new Ctl("wbtemp", "색온도 (자동 WB 끌 때)", false, 0x0A, 2, false, false));
        for (Ctl k : u.list) u.readRange(k);
        return u;
    }

    private int unit(Ctl k) { return k.camTerminal ? ctId : puId; }

    private Integer get(Ctl k, int req) {
        int id = unit(k);
        if (id < 0) return null;
        byte[] b = new byte[k.size];
        int r = conn.controlTransfer(0xA1, req, k.selector << 8, (id << 8) | vcIf, b, k.size, 300);
        if (r < k.size) return null;
        int v = 0;
        for (int i = 0; i < k.size; i++) v |= (b[i] & 0xFF) << (8 * i);
        if (k.signed && k.size == 2) v = (short) v;
        return v;
    }

    private void readRange(Ctl k) {
        Integer cur = get(k, 0x81);
        if (cur == null) { k.ok = false; return; }
        k.cur = cur;
        if (k.toggle) {
            k.ok = true; k.min = 0; k.max = 1;
            Integer d = get(k, 0x87);
            k.def = d != null ? d : cur;
            return;
        }
        Integer mn = get(k, 0x82), mx = get(k, 0x83), d = get(k, 0x87);
        if (mn == null || mx == null || mx <= mn) { k.ok = false; return; }
        k.min = mn; k.max = mx; k.def = d != null ? d : cur;
        k.ok = true;
    }

    /** 노출 모드는 UVC 비트값(1=수동, 2=자동, 8=조리개 우선) → 토글 on/off 로 변환 */
    boolean isOn(Ctl k) {
        if (k.key.equals("ae")) return k.cur != 1;
        return k.cur != 0;
    }

    boolean set(Ctl k, int value) {
        int id = unit(k);
        if (id < 0 || !k.ok) return false;
        int v = value;
        if (k.key.equals("ae")) v = value != 0 ? (k.def != 1 ? k.def : 8) : 1;   // 켜기=기본 자동모드, 끄기=수동
        byte[] b = new byte[k.size];
        for (int i = 0; i < k.size; i++) b[i] = (byte) (v >> (8 * i));
        int r = conn.controlTransfer(0x21, 0x01, k.selector << 8, (id << 8) | vcIf, b, k.size, 300);
        if (r < 0) { Log.w(TAG, "set " + k.key + "=" + v + " failed"); return false; }
        k.cur = v;
        return true;
    }

    Ctl find(String key) { for (Ctl k : list) if (k.key.equals(key)) return k; return null; }

    /** 저장된 값 적용 (토글 먼저 → 수동값) */
    void applySaved(SharedPreferences p) {
        for (int pass = 0; pass < 2; pass++) {
            for (Ctl k : list) {
                if (!k.ok || k.toggle != (pass == 0)) continue;
                String pk = "uvc_" + k.key;
                if (!p.contains(pk)) continue;
                int v = p.getInt(pk, 0);
                if (k.toggle) set(k, v); else set(k, Math.max(k.min, Math.min(k.max, v)));
            }
        }
        // 직접 저장한 값이 없으면 자동 화이트밸런스는 항상 켜기 (카메라에 수동 WB가 남아 있으면 사진이 빨갛게 나옴)
        Ctl wb = find("wbauto");
        if (wb != null && wb.ok && !p.contains("uvc_wbauto") && !isOn(wb)) set(wb, 1);
    }

    void resetDefaults(SharedPreferences p) {
        SharedPreferences.Editor e = p.edit();
        for (Ctl k : list) {
            e.remove("uvc_" + k.key);
            if (k.ok && k.toggle) set(k, 1);
        }
        e.apply();
        for (Ctl k : list) if (k.ok && !k.toggle) set(k, k.def);
    }

    String summary() {
        StringBuilder b = new StringBuilder("ct=" + ctId + " pu=" + puId + " if=" + vcIf);
        for (Ctl k : list) if (k.ok) b.append(' ').append(k.key).append('=').append(k.cur).append('[').append(k.min).append("..").append(k.max).append(" d").append(k.def).append(']');
        return b.toString();
    }
}
