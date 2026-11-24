package com.marbleflow.camlink;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraExtensionCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.Face;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Range;
import android.util.Rational;
import android.util.Size;
import android.view.Surface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Camera2 pipeline tuned for a dark projection room:
 *  - widest back lens (covers the most of the projected area)
 *  - low-light: AE fps range that lets exposure stretch (e.g. 7-30 fps), +1 EV,
 *    high-quality noise reduction, sharpening off
 *  - video stabilisation OFF (stabiliser cropping/shifting looks like motion)
 *  - exposure + white balance LOCK after settling, so a brightening/darkening projection
 *    doesn't make the whole image "move"; the page can ask to re-meter
 *  - hardware face detection, when the phone has it, as an extra presence signal
 * Each frame: Y plane -> MotionAnalyzer (on the phone) -> JSON; NV21 -> JPEG for the page.
 */
final class CameraEngine {
    interface StatusSink { void status(String s); }

    private final Context ctx;
    private final WsServer ws;
    private final StatusSink sink;
    final MotionAnalyzer ma = new MotionAnalyzer();

    private CameraManager cm;
    private HandlerThread thread;
    private Handler h;
    private CameraDevice dev;
    private CameraCaptureSession session;
    private ImageReader reader;
    private SurfaceTexture previewTex;
    private Surface previewSurface;
    private CaptureRequest.Builder rb;

    private String camId;
    private Size size;
    private CameraCharacteristics cc;
    volatile boolean lowLight = true;
    volatile boolean autoLock = true;
    private boolean locked = false;
    private long lockAtMs;

    private long expNs; private int iso; private int faces; private int faceMode;
    private float fps = 0; private long lastFrameNs;
    private byte[] nv21, yBytes;
    private long lastStatusMs;
    private Range<Integer> fpsRange;
    private String tuningSummary = "";

    CameraEngine(Context ctx, WsServer ws, StatusSink sink) {
        this.ctx = ctx; this.ws = ws; this.sink = sink;
        cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
    }

    void start(SurfaceTexture tex) {
        previewTex = tex;
        thread = new HandlerThread("camera");
        thread.start();
        h = new Handler(thread.getLooper());
        h.post(this::open);
    }

    void stop() {
        if (h == null) return;
        h.post(this::closeCamera);
        thread.quitSafely();
        try { thread.join(1500); } catch (InterruptedException ignored) {}
        h = null;
    }

    void post(Runnable r) { if (h != null) h.post(r); }

    // ---------------------------------------------------------------- camera choice
    private String pickCamera() throws CameraAccessException {
        String best = null; float bestFocal = Float.MAX_VALUE;
        for (String id : cm.getCameraIdList()) {
            CameraCharacteristics c = cm.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) continue;
            float[] f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            float focal = f != null && f.length > 0 ? f[0] : 99;
            if (focal < bestFocal) { bestFocal = focal; best = id; }
        }
        return best != null ? best : cm.getCameraIdList()[0];
    }

    private void nextCamera() {
        try {
            String[] ids = cm.getCameraIdList();
            int i = Arrays.asList(ids).indexOf(camId);
            camId = ids[(i + 1) % ids.length];
            closeCamera();
            open();
        } catch (CameraAccessException e) { sink.status("Camera switch failed: " + e.getMessage()); }
    }

    // ---------------------------------------------------------------- open / session
    @SuppressLint("MissingPermission")
    private void open() {
        try {
            if (camId == null) camId = pickCamera();
            cc = cm.getCameraCharacteristics(camId);
            StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            size = chooseSize(map.getOutputSizes(ImageFormat.YUV_420_888), 640, 480);
            reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 3);
            reader.setOnImageAvailableListener(this::onImage, h);
            if (previewTex != null) {
                previewTex.setDefaultBufferSize(size.getWidth(), size.getHeight());
                previewSurface = new Surface(previewTex);
            }
            ma.reset();
            cm.openCamera(camId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) { dev = d; createSession(); }
                @Override public void onDisconnected(CameraDevice d) { d.close(); dev = null; }
                @Override public void onError(CameraDevice d, int err) { d.close(); dev = null; sink.status("Camera error " + err); }
            }, h);
        } catch (Exception e) {
            sink.status("Could not open camera: " + e.getMessage());
        }
    }

    private static Size chooseSize(Size[] sizes, int w, int hgt) {
        Size best = sizes[0]; long bestScore = Long.MAX_VALUE;
        for (Size s : sizes) {
            long score = Math.abs((long) s.getWidth() * s.getHeight() - (long) w * hgt)
                    + (Math.abs(s.getWidth() * 3 - s.getHeight() * 4) < 8 ? 0 : 50000);   // prefer 4:3
            if (score < bestScore) { bestScore = score; best = s; }
        }
        return best;
    }

    @SuppressWarnings("deprecation")
    private void createSession() {
        try {
            List<Surface> outs = new ArrayList<>();
            outs.add(reader.getSurface());
            if (previewSurface != null) outs.add(previewSurface);
            dev.createCaptureSession(outs, new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) { session = s; buildRequest(); repeat(); sendInfo(); }
                @Override public void onConfigureFailed(CameraCaptureSession s) { sink.status("Camera session failed"); }
            }, h);
        } catch (CameraAccessException e) { sink.status("Session error: " + e.getMessage()); }
    }

    private static boolean has(int[] arr, int v) { if (arr == null) return false; for (int x : arr) if (x == v) return true; return false; }

    /** All the tuning lives here. */
    private void buildRequest() {
        try {
            rb = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            rb.addTarget(reader.getSurface());
            if (previewSurface != null) rb.addTarget(previewSurface);
            List<String> t = new ArrayList<>();

            rb.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            rb.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);

            // frame-rate range: in low light let the frame time stretch so exposure can grow
            Range<Integer>[] ranges = cc.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            fpsRange = pickFps(ranges, lowLight);
            if (fpsRange != null) { rb.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange); t.add("fps " + fpsRange); }

            // exposure compensation: +1 EV in low light
            Range<Integer> evr = cc.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            Rational evStep = cc.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
            if (evr != null && evStep != null && evStep.floatValue() > 0) {
                int steps = lowLight && !evBoostOff ? Math.round(1f / evStep.floatValue()) : 0;
                steps = Math.max(evr.getLower(), Math.min(evr.getUpper(), steps));
                rb.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, steps);
                if (steps != 0) t.add(String.format(Locale.US, "EV %+.1f", steps * evStep.floatValue()));
            }

            // noise reduction: strongest available in low light
            int[] nr = cc.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES);
            int nrMode = lowLight && has(nr, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY) ? CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY
                    : has(nr, CameraMetadata.NOISE_REDUCTION_MODE_FAST) ? CameraMetadata.NOISE_REDUCTION_MODE_FAST : -1;
            if (nrMode >= 0) { rb.set(CaptureRequest.NOISE_REDUCTION_MODE, nrMode); t.add(nrMode == 2 ? "NR high" : "NR fast"); }

            // sharpening amplifies grain -> off in low light
            int[] edge = cc.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES);
            if (lowLight && has(edge, CameraMetadata.EDGE_MODE_OFF)) { rb.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF); t.add("sharpen off"); }

            // stabilisation shifts the picture -> reads as motion. Turn it all off.
            int[] vs = cc.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
            if (has(vs, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)) rb.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
            int[] ois = cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
            if (has(ois, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)) rb.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF);
            t.add("stabilisation off");

            // focus: continuous video (people are a few metres away)
            int[] af = cc.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            if (has(af, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) rb.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO);

            // hardware face detector (a real on-chip detector on many phones)
            int[] fd = cc.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES);
            faceMode = has(fd, CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL) ? CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL
                    : has(fd, CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE) ? CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE : 0;
            if (faceMode != 0) { rb.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, faceMode); t.add("HW face detect"); }

            rb.set(CaptureRequest.CONTROL_AE_LOCK, false);
            rb.set(CaptureRequest.CONTROL_AWB_LOCK, false);
            locked = false;
            lockAtMs = SystemClock.uptimeMillis() + 3000;
            tuningSummary = String.join(", ", t);
        } catch (CameraAccessException e) { sink.status("Request error: " + e.getMessage()); }
    }

    private static Range<Integer> pickFps(Range<Integer>[] ranges, boolean low) {
        if (ranges == null || ranges.length == 0) return null;
        Range<Integer> best = null;
        for (Range<Integer> r : ranges) {
            if (r.getUpper() > 30) continue;
            if (best == null) { best = r; continue; }
            if (low) {   // highest top speed, then the lowest floor (longest allowed exposure)
                if (r.getUpper() > best.getUpper() || (r.getUpper().equals(best.getUpper()) && r.getLower() < best.getLower())) best = r;
            } else {     // steady: highest floor
                if (r.getLower() > best.getLower() || (r.getLower().equals(best.getLower()) && r.getUpper() > best.getUpper())) best = r;
            }
        }
        return best != null ? best : ranges[0];
    }

    private void repeat() {
        try { if (session != null) session.setRepeatingRequest(rb.build(), captureCb, h); }
        catch (Exception e) { sink.status("Repeat error: " + e.getMessage()); }
    }

    private final CameraCaptureSession.CaptureCallback captureCb = new CameraCaptureSession.CaptureCallback() {
        @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest req, TotalCaptureResult r) {
            Long e = r.get(CaptureResult.SENSOR_EXPOSURE_TIME); if (e != null) expNs = e;
            Integer i = r.get(CaptureResult.SENSOR_SENSITIVITY); if (i != null) iso = i;
            Face[] f = faceMode != 0 ? r.get(CaptureResult.STATISTICS_FACES) : null;
            faces = f != null ? f.length : 0;
            // lock exposure + white balance once auto-exposure has settled
            if (autoLock && !locked && SystemClock.uptimeMillis() > lockAtMs) {
                Integer ae = r.get(CaptureResult.CONTROL_AE_STATE);
                if (ae == null || ae == CaptureResult.CONTROL_AE_STATE_CONVERGED || ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
                        || SystemClock.uptimeMillis() > lockAtMs + 4000) {
                    rb.set(CaptureRequest.CONTROL_AE_LOCK, true);
                    rb.set(CaptureRequest.CONTROL_AWB_LOCK, true);
                    locked = true;
                    ma.reset();               // re-learn the background under the locked exposure
                    repeat();
                }
            }
        }
    };

    private void relock() {
        if (rb == null) return;
        rb.set(CaptureRequest.CONTROL_AE_LOCK, false);
        rb.set(CaptureRequest.CONTROL_AWB_LOCK, false);
        locked = false;
        lockAtMs = SystemClock.uptimeMillis() + 2500;
        repeat();
    }

    // ---------------------------------------------------------------- frames
    private void onImage(ImageReader r) {
        Image img = r.acquireLatestImage();
        if (img == null) return;
        try {
            int w = img.getWidth(), hh = img.getHeight();
            toNv21(img, w, hh);
            long now = SystemClock.elapsedRealtimeNanos();
            if (lastFrameNs != 0) { float inst = 1e9f / (now - lastFrameNs); fps = fps == 0 ? inst : fps + (inst - fps) * .1f; }
            lastFrameNs = now;

            checkExposure(w, hh);
            ma.process(yBytes, w, hh);

            if (ws.hasClient()) {
                ws.sendText(frameJson());
                YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, w, hh, null);
                ByteArrayOutputStream bos = new ByteArrayOutputStream(48 * 1024);
                yuv.compressToJpeg(new Rect(0, 0, w, hh), 60, bos);
                ws.sendJpeg(bos.toByteArray());
            }
            long ms = SystemClock.uptimeMillis();
            if (ms - lastStatusMs > 500) { lastStatusMs = ms; sink.status(statusLine()); }
        } catch (Exception e) {
            sink.status("Frame error: " + e.getMessage());
        } finally {
            img.close();
        }
    }

    // Exposure watchdog. A lock taken in the wrong light (phone face-down, lights changed, the
    // projector switched on) would otherwise stay wrong forever. While locked: if the picture is
    // clipped/too bright or too dark for ~3 s, re-meter. If it's clipping even at +1 EV,
    // the room isn't actually dark, so drop the low-light exposure boost.
    private float rawMean, clipFrac;
    private int badFrames;
    private boolean evBoostOff = false;
    private void checkExposure(int w, int hh) {
        long sum = 0; int clip = 0, n = 0;
        for (int y = 0; y < hh; y += 8) for (int x = 0; x < w; x += 8) {
            int v = yBytes[y * w + x] & 0xFF; sum += v; if (v > 245) clip++; n++;
        }
        rawMean = sum / (float) n; clipFrac = clip / (float) n;
        boolean over = rawMean > 200 || clipFrac > 0.25f, under = rawMean < 18;
        if (locked && (over || under)) {
            if (++badFrames > fps * 3 + 5) {
                badFrames = 0;
                if (over && lowLight && !evBoostOff) { evBoostOff = true; buildRequest(); repeat(); }
                else relock();
            }
        } else badFrames = 0;
    }

    private void toNv21(Image img, int w, int hh) {
        int n = w * hh;
        if (nv21 == null || nv21.length != n * 3 / 2) { nv21 = new byte[n * 3 / 2]; yBytes = new byte[n]; }
        Image.Plane[] p = img.getPlanes();
        ByteBuffer yb = p[0].getBuffer(); int yRow = p[0].getRowStride(), yPix = p[0].getPixelStride();
        for (int row = 0; row < hh; row++) {
            if (yPix == 1) { yb.position(row * yRow); yb.get(yBytes, row * w, w); }
            else for (int c = 0; c < w; c++) yBytes[row * w + c] = yb.get(row * yRow + c * yPix);
        }
        System.arraycopy(yBytes, 0, nv21, 0, n);
        ByteBuffer ub = p[1].getBuffer(), vb = p[2].getBuffer();
        int uRow = p[1].getRowStride(), uPix = p[1].getPixelStride(), vRow = p[2].getRowStride(), vPix = p[2].getPixelStride();
        int o = n;
        for (int row = 0; row < hh / 2; row++)
            for (int c = 0; c < w / 2; c++) {
                nv21[o++] = vb.get(row * vRow + c * vPix);
                nv21[o++] = ub.get(row * uRow + c * uPix);
            }
    }

    private String frameJson() {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("{\"type\":\"frame\",\"fps\":").append(fmt(fps))
          .append(",\"motion\":").append(fmt(ma.motionFrac)).append(",\"presence\":").append(fmt(ma.fgFrac))
          .append(",\"noise\":").append(fmt(ma.noise)).append(",\"gain\":").append(fmt(ma.gain))
          .append(",\"expMs\":").append(fmt(expNs / 1e6f)).append(",\"iso\":").append(iso)
          .append(",\"faces\":").append(faces).append(",\"locked\":").append(locked).append(",\"low\":").append(lowLight)
          .append(",\"brightness\":").append(fmt(rawMean)).append(",\"clipped\":").append(fmt(clipFrac))
          .append(",\"flow\":[");
        float[] f = ma.flow;
        for (int i = 0; i < f.length; i++) { if (i > 0) sb.append(','); sb.append(Math.round(f[i] * 100) / 100f); }
        sb.append("],\"mask\":\"").append(Base64.encodeToString(ma.packedMask(), Base64.NO_WRAP)).append("\"}");
        return sb.toString();
    }

    private static String fmt(float v) { return String.format(Locale.US, "%.3f", v); }

    private String statusLine() {
        return String.format(Locale.US,
                "Camera %s  %dx%d  %.0f fps\nExposure %.1f ms  ISO %d  %s\nMotion %.1f%%  presence %.0f%%  gain %.1fx  faces %d\n%s\nPC link: %s",
                camId, size.getWidth(), size.getHeight(), fps, expNs / 1e6f, iso, locked ? "LOCKED" : "metering…",
                ma.motionFrac * 100, ma.fgFrac * 100, ma.gain, faces, tuningSummary,
                ws.hasClient() ? "connected" : "waiting (run start-phone-link.ps1 on the PC)");
    }

    // ---------------------------------------------------------------- info + commands
    void sendInfo() {
        try {
            JSONObject o = new JSONObject();
            o.put("type", "info");
            o.put("model", Build.MANUFACTURER + " " + Build.MODEL);
            o.put("camera", camId);
            o.put("size", size.getWidth() + "x" + size.getHeight());
            o.put("tuning", tuningSummary);
            o.put("sensorOrientation", cc.get(CameraCharacteristics.SENSOR_ORIENTATION));
            JSONArray cams = new JSONArray();
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics c = cm.getCameraCharacteristics(id);
                JSONObject j = new JSONObject();
                j.put("id", id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                j.put("facing", facing == null ? "?" : facing == 0 ? "front" : facing == 1 ? "back" : "external");
                float[] fl = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                if (fl != null && fl.length > 0) j.put("focalMm", fl[0]);
                Integer lvl = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
                j.put("hwLevel", lvl == null ? "?" : new String[]{"LIMITED", "FULL", "LEGACY", "LEVEL_3", "EXTERNAL"}[Math.min(4, lvl)]);
                int[] caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                j.put("manualSensor", has(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR));
                Range<Long> er = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
                if (er != null) j.put("maxExposureMs", er.getUpper() / 1e6);
                Range<Integer> sr = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
                if (sr != null) j.put("maxIso", sr.getUpper());
                int[] fd = c.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES);
                j.put("hwFaceDetect", has(fd, 1) || has(fd, 2));
                if (Build.VERSION.SDK_INT >= 31) {
                    try {
                        CameraExtensionCharacteristics ec = cm.getCameraExtensionCharacteristics(id);
                        j.put("nightExtension", ec.getSupportedExtensions().contains(CameraExtensionCharacteristics.EXTENSION_NIGHT));
                    } catch (Exception ignored) {}
                }
                cams.put(j);
            }
            o.put("cameras", cams);
            ws.sendText(o.toString());
        } catch (Exception e) { sink.status("Info error: " + e.getMessage()); }
    }

    /** Commands from the page (JSON). Runs on the camera thread. */
    void handleCommand(String msg) {
        try {
            JSONObject o = new JSONObject(msg);
            switch (o.optString("cmd")) {
                case "lowlight":
                    lowLight = o.optBoolean("on", true); ma.lowLight = lowLight; ma.reset(); evBoostOff = false;
                    buildRequest(); repeat(); sendInfo(); break;
                case "sens": ma.sens = (float) o.optDouble("v", .5); break;
                case "crop": ma.setCrop((float) o.optDouble("l", 0), (float) o.optDouble("t", 0), (float) o.optDouble("r", 1), (float) o.optDouble("b", 1)); break;
                case "relock": relock(); break;
                case "autolock": autoLock = o.optBoolean("on", true); if (!autoLock) relock(); break;
                case "nextCamera": nextCamera(); break;
                case "info": sendInfo(); break;
            }
        } catch (Exception e) { sink.status("Bad command: " + e.getMessage()); }
    }

    private void closeCamera() {
        try { if (session != null) session.close(); } catch (Exception ignored) {}
        try { if (dev != null) dev.close(); } catch (Exception ignored) {}
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        session = null; dev = null; reader = null;
        if (previewSurface != null) { previewSurface.release(); previewSurface = null; }
    }
}
