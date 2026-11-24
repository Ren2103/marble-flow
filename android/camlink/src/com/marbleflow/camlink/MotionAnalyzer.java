package com.marbleflow.camlink;

/**
 * On-phone motion analysis (same algorithm as the page's built-in one, moved onto the phone):
 *  - crop + downsample the camera's luminance (Y) plane to 80x60
 *  - low light: automatic gain, 5x5 spatial + temporal denoise
 *  - motion mask (frame difference) and presence (slow background model)
 *  - Lucas-Kanade optical flow on a 20x15 grid (pixels/frame at 80x60)
 */
final class MotionAnalyzer {
    static final int CW = 80, CH = 60, GW = 20, GH = 15;

    final float[] L = new float[CW * CH];
    final float[] cur = new float[CW * CH];
    float[] prev, bg;
    final float[] flow = new float[GW * GH * 2];
    final byte[] mask = new byte[CW * CH];

    float gain = 1, gainMean = 128, noise = 2, motionFrac, fgFrac;
    volatile boolean lowLight = true;
    volatile float sens = 0.5f;
    volatile float cropL = 0, cropT = 0, cropR = 1, cropB = 1;

    void reset() { prev = null; bg = null; }

    void setCrop(float l, float t, float r, float b) {
        cropL = clamp(l, 0, .9f); cropT = clamp(t, 0, .9f);
        cropR = clamp(r, cropL + .05f, 1); cropB = clamp(b, cropT + .05f, 1);
        reset();
    }

    private static float clamp(float v, float a, float b) { return Math.max(a, Math.min(b, v)); }

    /** y = luminance bytes, tightly packed w x h. */
    void process(byte[] y, int w, int h) {
        // 1) crop + box-downsample (2x2 samples per output pixel)
        float x0 = cropL * w, y0 = cropT * h, sx = (cropR - cropL) * w / CW, sy = (cropB - cropT) * h / CH;
        for (int j = 0; j < CH; j++) {
            for (int i = 0; i < CW; i++) {
                int s = 0;
                for (int dy = 0; dy < 2; dy++) {
                    int yy = Math.min(h - 1, (int) (y0 + (j + .25f + dy * .5f) * sy));
                    for (int dx = 0; dx < 2; dx++) {
                        int xx = Math.min(w - 1, (int) (x0 + (i + .25f + dx * .5f) * sx));
                        s += y[yy * w + xx] & 0xFF;
                    }
                }
                L[j * CW + i] = s * .25f;
            }
        }
        final int N = CW * CH;
        final boolean low = lowLight;

        // 2) low light: automatic gain
        if (low) {
            float mean = 0; for (int i = 0; i < N; i++) mean += L[i]; mean /= N;
            float vr = 0; for (int i = 0; i < N; i++) { float d = L[i] - mean; vr += d * d; }
            float sd = (float) Math.sqrt(vr / N);
            float target = Math.min(8, Math.max(1, 42 / Math.max(sd, 3)));
            if (prev == null) { gain = target; gainMean = mean; }
            else { gain += (target - gain) * .015f; gainMean += (mean - gainMean) * .015f; }
            for (int i = 0; i < N; i++) L[i] = Math.min(255, Math.max(0, (L[i] - gainMean) * gain + 128));
        } else gain = 1;

        // 3) spatial (+ temporal in low light) denoise
        int R = low ? 2 : 1;
        for (int j = 0; j < CH; j++) for (int i = 0; i < CW; i++) {
            float s = 0; int n = 0;
            for (int dy = -R; dy <= R; dy++) { int yy = j + dy; if (yy < 0 || yy >= CH) continue;
                for (int dx = -R; dx <= R; dx++) { int xx = i + dx; if (xx < 0 || xx >= CW) continue; s += L[yy * CW + xx]; n++; } }
            float b = s / n;
            int k = j * CW + i;
            cur[k] = (low && prev != null) ? prev[k] + (b - prev[k]) * .65f : b;
        }
        if (prev == null) { prev = cur.clone(); bg = cur.clone(); return; }

        // 4) motion + presence, threshold never below ~3x sensor noise
        float nz = 0; for (int i = 0; i < N; i++) nz += Math.abs(cur[i] - prev[i]); nz /= N;
        if (motionFrac < .02f) noise += (nz - noise) * .05f;
        float thr = Math.max(42 - 34 * sens, noise * 6);
        int moving = 0, fg = 0;
        for (int i = 0; i < N; i++) {
            boolean m = Math.abs(cur[i] - prev[i]) > thr * .5f;
            mask[i] = (byte) (m ? 1 : 0); if (m) moving++;
            boolean isFg = Math.abs(cur[i] - bg[i]) > thr;
            if (isFg) fg++;
            bg[i] += (cur[i] - bg[i]) * (isFg ? .0015f : .03f);
        }
        motionFrac = moving / (float) N; fgFrac = fg / (float) N;

        // 5) Lucas-Kanade flow per grid cell
        int cs = CW / GW, win = 3;
        float[] P = prev, C = cur;
        for (int gy = 0; gy < GH; gy++) for (int gx = 0; gx < GW; gx++) {
            int cx = (int) ((gx + .5f) * cs), cy = (int) ((gy + .5f) * cs);
            float Ixx = 0, Iyy = 0, Ixy = 0, Ixt = 0, Iyt = 0; int mot = 0;
            for (int yy = Math.max(1, cy - win); yy <= Math.min(CH - 2, cy + win); yy++)
                for (int xx = Math.max(1, cx - win); xx <= Math.min(CW - 2, cx + win); xx++) {
                    int i = yy * CW + xx;
                    float Ix = ((C[i + 1] - C[i - 1]) + (P[i + 1] - P[i - 1])) * .25f;
                    float Iy = ((C[i + CW] - C[i - CW]) + (P[i + CW] - P[i - CW])) * .25f;
                    float It = C[i] - P[i];
                    Ixx += Ix * Ix; Iyy += Iy * Iy; Ixy += Ix * Iy; Ixt += Ix * It; Iyt += Iy * It; mot += mask[i];
                }
            float fx = 0, fy = 0, det = Ixx * Iyy - Ixy * Ixy;
            if (mot >= 3 && det > 1e-2f) {
                fx = (-Iyy * Ixt + Ixy * Iyt) / det; fy = (Ixy * Ixt - Ixx * Iyt) / det;
                float m = (float) Math.hypot(fx, fy); if (m > 4) { fx *= 4 / m; fy *= 4 / m; }
            }
            int k = (gy * GW + gx) * 2;
            flow[k] += (fx - flow[k]) * .6f;
            flow[k + 1] += (fy - flow[k + 1]) * .6f;
        }
        System.arraycopy(cur, 0, prev, 0, N);
    }

    /** Motion mask packed 8 pixels per byte (for the page's preview overlay). */
    byte[] packedMask() {
        byte[] out = new byte[(CW * CH + 7) / 8];
        for (int i = 0; i < CW * CH; i++) if (mask[i] != 0) out[i >> 3] |= (byte) (1 << (i & 7));
        return out;
    }
}
