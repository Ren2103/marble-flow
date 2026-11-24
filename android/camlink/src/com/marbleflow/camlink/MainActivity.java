package com.marbleflow.camlink;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Full-screen camera preview (for aiming) + status overlay.
 * Tap: switch camera. Long-press: re-meter exposure.
 * The screen dims itself after 20 s to keep the phone cool; tap to wake it.
 */
public class MainActivity extends Activity implements TextureView.SurfaceTextureListener {
    private static final int PORT = 47865;
    private TextureView tv;
    private TextView status;
    private WsServer ws;
    private CameraEngine engine;
    private SurfaceTexture tex;
    private boolean resumed, started;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable dim = () -> setBrightness(0.02f);

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        tv = new TextureView(this);
        tv.setSurfaceTextureListener(this);
        root.addView(tv, new FrameLayout.LayoutParams(-1, -1));
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(13);
        status.setBackgroundColor(0x99000000);
        status.setPadding(24, 16, 24, 16);
        status.setText("Marble CamLink\nStarting…");
        root.addView(status, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));
        setContentView(root);

        root.setOnClickListener(v -> { wake(); if (engine != null) engine.post(() -> engine.handleCommand("{\"cmd\":\"nextCamera\"}")); });
        root.setOnLongClickListener(v -> { wake(); if (engine != null) engine.post(() -> engine.handleCommand("{\"cmd\":\"relock\"}")); return true; });
        root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        ws = new WsServer(PORT, new WsServer.Listener() {
            @Override public void onText(String msg) { if (engine != null) engine.post(() -> engine.handleCommand(msg)); }
            @Override public void onClient(boolean connected) { if (connected && engine != null) engine.post(engine::sendInfo); }
        });
        ws.start();
        engine = new CameraEngine(this, ws, s -> ui.post(() -> status.setText(s)));

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
        wake();
    }

    private void wake() {
        setBrightness(-1f);   // system default
        ui.removeCallbacks(dim);
        ui.postDelayed(dim, 20000);
    }

    private void setBrightness(float v) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = v;
        getWindow().setAttributes(lp);
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) maybeStart();
        else status.setText("Camera permission is needed. Reopen the app and allow it.");
    }

    @Override protected void onResume() { super.onResume(); resumed = true; maybeStart(); }
    @Override protected void onPause() { super.onPause(); resumed = false; stopCamera(); }
    @Override protected void onDestroy() { super.onDestroy(); stopCamera(); ws.shutdown(); }

    private void maybeStart() {
        if (started || !resumed || tex == null) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        started = true;
        engine.start(tex);
    }

    private void stopCamera() { if (started) { engine.stop(); started = false; } }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) { tex = s; maybeStart(); }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) {}
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { stopCamera(); tex = null; return true; }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture s) {}
}
