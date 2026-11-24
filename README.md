# Marble Flow

A procedural, seamlessly looping liquid-marble animation you can stir with your mouse, or with
people walking past a projection.

**▶ Open it: https://ren2103.github.io/marble-flow/**

Everything runs in the browser (WebGL2). No install, no server, no tracking.

## What it does
- **Marble shader**: domain-warped 4D simplex noise. The loop travels around a circle in noise space, so the first and last frames match exactly.
- **28 dials**: warp, turbulence, octaves, band count and angle, edge softness, veins, colours, depth, sheen, grain and more. Includes presets, randomize, copy/paste settings, PNG export and recording one full loop to video.
- **Stirring**: click-drag to push the colours around. A real-time fluid simulation carries the marble's colours and curls them into eddies.
- **Projection mode**: point a camera at the projection.
  - Someone **walking past** stirs the liquid along their path.
  - Someone who **stops in front** switches it to **hand mode**: each tracked hand stirs like a mouse (MediaPipe hand tracking).
  - Includes camera choice, crop calibration, mirror/flip, sensitivity and a low-light mode.
- **Phones and tablets**: multi-touch stirring (every finger stirs), a bottom-sheet panel with bigger controls, double-tap a dial to reset it, and automatic resolution scaling to keep it smooth.
- **Fullscreen**: press `F`. `H` hides the panel and `Space` pauses.

## Projection setup
Mount the camera right under the projector, looking at the same wall, so the camera view lines up with the
projected image. In the page's **Camera** section:
1. Pick the camera and click **Start camera**.
2. Turn on **Preview**. Adjust **Crop left / right / top / bottom** until the purple outline matches the projected area.
3. Walk through the projection. If the colours move the wrong way, tick **Mirror** and/or **Flip vertical**.
4. Press `F` for fullscreen and `H` to hide the panel.

**Tip:** an ordinary camera also sees the projected image, so strong stirring can re-trigger itself.
Lower **Sensitivity**, or use an infrared camera with an 850 nm IR illuminator: it sees people but not the projection.

## Android phone as the camera (Marble CamLink)
An Android phone can be the camera. A small app does the low-light camera tuning and the motion
tracking **on the phone**, and sends the results to the page over the USB cable.

- Uses the widest back lens. Low light: frame rate down to 5 fps for longer exposure, +1 EV, strongest noise reduction, sharpening off.
- Stabilisation off: it shifts the picture, which looks like motion.
- Exposure and white balance lock once settled, with an automatic re-meter if the light changes a lot. **Re-meter exposure** in the page does it on demand.
- Uses the phone's hardware face detector (when present) as a "someone is here" signal.

**Setup (Windows):**
1. On the phone: Settings → About phone → tap *Build number* 7× → Developer options → turn on **USB debugging**.
2. Install adb once: `winget install Google.PlatformTools`
3. Plug the phone in, tap **Allow** on the phone, then run [`phone/start-phone-link.ps1`](phone/start-phone-link.ps1).
   It installs the app ([`phone/marble-camlink.apk`](phone/marble-camlink.apk)), connects it over USB, and opens it.
4. In the page's Camera menu choose **📱 Phone app over USB (Marble CamLink)**.

When the page asks to **access devices on your local network**, allow it. That's how it reaches the phone link on `127.0.0.1`.
Mount the phone in **landscape**. Tap the phone screen to switch lens, long-press to re-meter.

Tested on a Nothing CMF Phone 1 (Android 15). The app needs Android 8.0 or newer.

### Building the app yourself
The source is in [`android/camlink`](android/camlink). It's plain Java with no dependencies and no Gradle.
With the Android SDK (platform 34 + build-tools 34) and JDK 11+ installed:
```powershell
powershell -ExecutionPolicy Bypass -File android\camlink\build.ps1
```
It writes `android/camlink/build/marble-camlink.apk`, signed with a local key it creates on first build.

## Credits
4D simplex noise by Ashima Arts / Stefan Gustavson (MIT). Hand tracking by Google MediaPipe Tasks Vision (Apache 2.0), loaded from jsDelivr when hand tracking is on.
