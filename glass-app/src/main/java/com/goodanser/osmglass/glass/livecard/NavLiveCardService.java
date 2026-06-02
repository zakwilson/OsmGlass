package com.goodanser.osmglass.glass.livecard;

import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;

import com.google.android.glass.timeline.LiveCard;

import com.goodanser.osmglass.glass.R;
import com.goodanser.osmglass.glass.transport.TransportFactory;
import com.goodanser.osmglass.protocol.Packet;
import com.goodanser.osmglass.protocol.transport.Transport;

import java.io.IOException;

/**
 * The Glass LiveCard host. On real Glass this publishes to the timeline and the user sees the
 * snippet + turn instruction in the prism. On the API 19 emulator there is no Glass system UI, so
 * {@code LiveCard.publish()} is a no-op (or throws); we wrap it and keep going so packets still
 * flow through the dispatch path and we can verify the byte-level round trip via logcat.
 */
public class NavLiveCardService extends Service {
    private static final String TAG = "NavLiveCardService";
    private static final String LIVECARD_TAG = "com.goodanser.osmglass.glass.nav";

    /** Grace period before surfacing a "DISCONNECTED" alert — covers brief BT hiccups during
     *  which the phone-side reconnect with exponential backoff usually recovers. */
    private static final long DISCONNECT_ALERT_DELAY_MS = 10_000L;
    /** Maximum time to hold the screen on after the disconnect alert fires. The LiveCard still
     *  displays "DISCONNECTED" once the screen dims; this just bounds battery drain. */
    private static final long DISCONNECT_WAKE_MS = 60_000L;

    private LiveCard liveCard;
    private RemoteViews views;
    private Transport transport;
    private TtsSpeaker speaker;
    private PowerManager.WakeLock screenWake;
    private PowerManager.WakeLock disconnectWake;
    /** How long to hold {@link #screenWake} after waking for an approaching turn, in ms; 0 means no
     *  timeout (hold until the turn is passed). Pushed from the phone via DisplayConfig
     *  ({@link PacketDispatcher#setScreenWakeTimeout}); defaults to the protocol's default so the
     *  screen is bounded even before the first DisplayConfig arrives. Read on the reader thread in
     *  {@link #onApproachingTurn}, written from the same thread — volatile for visibility. */
    private volatile long screenWakeMs =
        Packet.DisplayConfig.DEFAULT_SCREEN_WAKE_SEC * 1000L;
    private int approachingTurnIndex = -1;
    private boolean hasNavContent = false;
    private BroadcastReceiver screenOnReceiver;
    private Handler mainHandler;
    private final Runnable disconnectAlertRunnable = this::showDisconnectAlert;

    /**
     * All {@code views}/{@code liveCard.setViews} access is serialized onto this single render
     * thread so the RFCOMM reader thread never blocks on a bitmap decode + RemoteViews IPC. The
     * reader can then drain the socket as fast as packets arrive; nav frames are conflated (see
     * {@link #renderPendingRunnable}) so only the freshest position is ever rendered and a slow
     * render can't build a backlog of stale frames behind the rider's real position (glass-nav-lx5).
     */
    private HandlerThread renderThread;
    private Handler renderHandler;
    /** Latest nav frame awaiting render; superseded frames are simply overwritten before the
     *  render thread gets to them. Volatile: written on the reader thread, read on render thread. */
    private volatile NavFrame pendingFrame;
    private final Runnable renderPendingRunnable = this::renderPendingFrame;
    /** Decode cache: a TurnBundle's PNG is the same byte[] instance across every Progress for that
     *  turn (PacketDispatcher hands us the cached bundle's array), so we decode it once and reuse
     *  the base bitmap, copying it only to stamp the moving marker. Render-thread-only state. */
    private byte[] decodedKey;
    private Bitmap decodedBase;
    /** Single reused canvas-backed buffer holding the composited snippet (base map + marker). Kept
     *  for the service lifetime so the RemoteViews never holds more than one snippet bitmap and the
     *  render path makes no per-frame ~900 KB allocation (glass-nav). Render-thread-only. */
    private Bitmap snippetBuffer;
    /** Retained corner display state, re-applied to a fresh RemoteViews on every push (see
     *  renderViews). NavFrame fields may be null meaning "unchanged", so we carry the last value
     *  forward. */
    private String dispTopLeft = "";
    private String dispTopRight = "";
    private String dispBottomLeft = "";
    private String dispBottomRight = "";
    /** Centered status/alert message ("Done", "Rerouting…", "DISCONNECTED"), shown over the corners
     *  when non-empty. Cleared on the next nav frame. */
    private String dispStatus = "";
    private float dispTextSize = 22f;
    /** When true the snippet shows a flat black fill (disconnect alert / initial) rather than the
     *  composited map buffer. */
    private boolean snippetBlack = true;
    /** Whether the current snippet is a light/day map. Decided from the snippet's average luminance
     *  in {@link #decodeCached} (the phone renders the PNG in its own day/night theme, so the bitmap
     *  brightness is the signal — no extra wire field needed). When true, renderViews shows the
     *  dark-text/light-halo corner variants; when false, the light-text/dark-halo ones. Defaults to
     *  false so the unchanged dark-mode look is used until the first map frame (glass-nav-5um). */
    private boolean dispLightMode = false;

    /** Immutable snapshot of one display update, queued for the render thread. */
    private static final class NavFrame {
        final byte[] png;
        final String topLeft;
        final String topRight;
        final String bottomLeft;
        final String bottomRight;
        final int markerPxX;
        final int markerPxY;
        final int markerBearingDeg100;

        NavFrame(byte[] png, String topLeft, String topRight, String bottomLeft, String bottomRight,
                 int markerPxX, int markerPxY, int markerBearingDeg100) {
            this.png = png;
            this.topLeft = topLeft;
            this.topRight = topRight;
            this.bottomLeft = bottomLeft;
            this.bottomRight = bottomRight;
            this.markerPxX = markerPxX;
            this.markerPxY = markerPxY;
            this.markerBearingDeg100 = markerBearingDeg100;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "onCreate");
        mainHandler = new Handler(Looper.getMainLooper());
        renderThread = new HandlerThread("LiveCardRender");
        renderThread.start();
        renderHandler = new Handler(renderThread.getLooper());
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            // SCREEN_BRIGHT_WAKE_LOCK is deprecated on modern Android but is the standard
            // mechanism on Glass XE (API 19) to bring the display out of dim/off from a
            // non-Activity component. ACQUIRE_CAUSES_WAKEUP forces an immediate wake.
            screenWake = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                TAG + ":turnApproach");
            screenWake.setReferenceCounted(false);
            disconnectWake = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                TAG + ":disconnected");
            disconnectWake.setReferenceCounted(false);
        }
        views = new RemoteViews(getPackageName(), R.layout.livecard_nav);
        try {
            liveCard = new LiveCard(this, LIVECARD_TAG);
            liveCard.setViews(views);
            Intent menu = new Intent(this, MenuActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            // FLAG_IMMUTABLE was added in API 23; we target API 19 so just use FLAG_UPDATE_CURRENT.
            liveCard.setAction(PendingIntent.getActivity(this, 0, menu,
                PendingIntent.FLAG_UPDATE_CURRENT));
            liveCard.publish(LiveCard.PublishMode.REVEAL);
            Log.i(TAG, "LiveCard published");
        } catch (Throwable t) {
            // Expected on the API 19 emulator (no Glass system UI). Log and keep going so the
            // packet pipeline still runs.
            Log.w(TAG, "LiveCard publish failed (likely emulator): " + t.getMessage());
            liveCard = null;
        }
        // Glass's head-wake gesture (and a touchpad tap) turn the screen on, which fires
        // ACTION_SCREEN_ON. When that happens during an active route, surface the map LiveCard
        // so a glance-up brings the user straight to nav instead of the clock/timeline home.
        // ACTION_SCREEN_ON is only delivered to runtime-registered receivers, not manifest ones.
        screenOnReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!hasNavContent || liveCard == null) return;
                try {
                    liveCard.navigate();
                } catch (Throwable t) {
                    Log.w(TAG, "liveCard.navigate on screen-on failed: " + t.getMessage());
                }
            }
        };
        registerReceiver(screenOnReceiver, new IntentFilter(Intent.ACTION_SCREEN_ON));

        speaker = new TtsSpeaker(this);

        // Start the transport (TCP server in debug, RFCOMM in release).
        transport = TransportFactory.create();
        transport.setListener(new PacketDispatcher(this));
        try {
            transport.start();
            Log.i(TAG, "transport started");
        } catch (IOException e) {
            Log.w(TAG, "transport.start failed: " + e.getMessage());
        }
    }

    /** Called by {@code PacketDispatcher} to surface a spoken cue through bone conduction. */
    public void speak(String utterance, String utteranceId) {
        if (speaker != null) speaker.speak(utterance, utteranceId);
    }

    /**
     * Shows a centered status/alert message ("Done", "Rerouting…") over a black card, clearing the
     * corner fields. Used for transport-state updates: pass "" to clear (e.g. on connect, while
     * fresh nav packets are en route).
     */
    public void showStatus(String message) {
        if (views == null || renderHandler == null) return;
        // Drop any not-yet-rendered nav frame so a stale position can't overwrite the status.
        pendingFrame = null;
        renderHandler.removeCallbacks(renderPendingRunnable);
        renderHandler.post(() -> {
            if (views == null) return;
            dispTextSize = 22f;
            dispStatus = message == null ? "" : message;
            dispTopLeft = "";
            dispTopRight = "";
            dispBottomLeft = "";
            dispBottomRight = "";
            snippetBlack = true;
            renderViews();
        });
    }

    /**
     * Called by {@code PacketDispatcher} when a new snippet + corner-field update arrives. When
     * the marker fields are not {@link Packet.Progress#MARKER_NONE} the snippet is composited
     * with a position arrow at the given pixel coordinates rotated by the given bearing — the
     * phone has already done the geo-to-pixel projection, so we just paint at (markerPxX,
     * markerPxY).
     */
    public void updateRemoteViews(byte[] pngBytes, String topLeft, String topRight,
                                  String bottomLeft, String bottomRight,
                                  int markerPxX, int markerPxY, int markerBearingDeg100) {
        if (views == null || renderHandler == null) return;
        // Conflate: replace any not-yet-rendered frame and coalesce to a single queued render so a
        // burst of buffered Progress packets collapses to the latest position. The reader thread
        // returns immediately and keeps draining the socket instead of blocking on decode + IPC.
        pendingFrame = new NavFrame(pngBytes, topLeft, topRight, bottomLeft, bottomRight,
            markerPxX, markerPxY, markerBearingDeg100);
        renderHandler.removeCallbacks(renderPendingRunnable);
        renderHandler.post(renderPendingRunnable);
    }

    /** Render the most recent {@link #pendingFrame} on the render thread. Any frames superseded
     *  while this was queued have already been overwritten, so we only ever paint the freshest. */
    private void renderPendingFrame() {
        NavFrame f = pendingFrame;
        if (f == null || views == null) return;
        // A nav frame clears any centered status ("DISCONNECTED"/"Done") and its oversized text size
        // left by showDisconnectAlert()/showStatus().
        dispTextSize = 22f;
        dispStatus = "";
        if (f.png != null && f.png.length > 0) {
            Bitmap base = decodeCached(f.png);
            if (base != null && compositeSnippet(base, f.markerPxX, f.markerPxY, f.markerBearingDeg100)) {
                snippetBlack = false;
            }
            hasNavContent = true;
        }
        if (f.topLeft != null) {
            dispTopLeft = f.topLeft;
            hasNavContent = true;
        }
        if (f.topRight != null) dispTopRight = f.topRight;
        if (f.bottomLeft != null) dispBottomLeft = f.bottomLeft;
        if (f.bottomRight != null) dispBottomRight = f.bottomRight;
        renderViews();
    }

    /** Decode {@code png} to a base bitmap, reusing the last decode when the byte[] is the same
     *  instance (same cached TurnBundle). Render-thread-only; not synchronized. */
    private Bitmap decodeCached(byte[] png) {
        if (png == decodedKey && decodedBase != null) return decodedBase;
        Bitmap bm = BitmapFactory.decodeByteArray(png, 0, png.length);
        if (bm != null) {
            // Free the previous turn's base before holding the new one. The base is only ever used
            // as a blit source for the snippet buffer — it is never handed to a RemoteViews — so
            // recycling it here is safe and keeps decoded bitmaps from piling up on the small Glass
            // heap across turn changes (glass-nav).
            if (decodedBase != null && decodedBase != bm) decodedBase.recycle();
            decodedKey = png;
            decodedBase = bm;
            // Re-evaluate light/dark only when the underlying map changes (once per turn, not per
            // Progress) since the marker composite doesn't alter the map's overall brightness.
            dispLightMode = isLightSnippet(bm);
        }
        return bm;
    }

    /** Decide whether {@code bm} is a light/day map by averaging the luminance of a sparse pixel
     *  grid (~16×16 samples) and comparing to mid-grey. Cheap enough to run once per turn on the
     *  render thread; {@link Bitmap#getPixel} on a few hundred points avoids a full-bitmap copy. */
    private static boolean isLightSnippet(Bitmap bm) {
        int w = bm.getWidth();
        int h = bm.getHeight();
        if (w <= 0 || h <= 0) return false;
        int stepX = Math.max(1, w / 16);
        int stepY = Math.max(1, h / 16);
        long sum = 0;
        int n = 0;
        for (int y = 0; y < h; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                int c = bm.getPixel(x, y);
                int r = (c >> 16) & 0xff;
                int g = (c >> 8) & 0xff;
                int b = c & 0xff;
                // Rec. 601 luma, integer-weighted (0.299/0.587/0.114 ≈ 77/150/29 over 256).
                sum += (r * 77 + g * 150 + b * 29) >> 8;
                n++;
            }
        }
        return n > 0 && (sum / n) >= 128;
    }

    /** Composite {@code base} (and, when present, the position marker) into the reused
     *  {@link #snippetBuffer}. Returns false if the buffer can't be (re)allocated, in which case the
     *  caller keeps the previous snippet rather than crashing. Render-thread-only. */
    private boolean compositeSnippet(Bitmap base, int px, int py, int bearingDeg100) {
        if (snippetBuffer == null
                || snippetBuffer.getWidth() != base.getWidth()
                || snippetBuffer.getHeight() != base.getHeight()) {
            Bitmap old = snippetBuffer;
            try {
                snippetBuffer = Bitmap.createBitmap(base.getWidth(), base.getHeight(),
                    Bitmap.Config.ARGB_8888);
            } catch (Throwable t) {
                Log.w(TAG, "snippet buffer alloc failed: " + t.getMessage());
                snippetBuffer = old;
                return false;
            }
            if (old != null) old.recycle();
        }
        Canvas canvas = new Canvas(snippetBuffer);
        // base is an opaque, full-size map snippet, so drawing it overwrites the previous frame's
        // pixels (including the old marker) — no separate clear needed.
        canvas.drawBitmap(base, 0f, 0f, null);
        if (px != Packet.Progress.MARKER_NONE && py != Packet.Progress.MARKER_NONE) {
            drawMarker(canvas, px, py, bearingDeg100);
        }
        return true;
    }

    /** Set one corner's text on both its dark- and light-map TextView variants and show whichever
     *  matches {@link #dispLightMode}, hiding the other. RemoteViews can't recolor the blur halo at
     *  runtime, so the contrast flip is a visibility toggle between the two pre-styled views. */
    private void applyCorner(RemoteViews v, int darkId, int lightId, String text) {
        v.setTextViewText(darkId, text);
        v.setTextViewText(lightId, text);
        v.setViewVisibility(darkId, dispLightMode ? View.GONE : View.VISIBLE);
        v.setViewVisibility(lightId, dispLightMode ? View.VISIBLE : View.GONE);
    }

    /** Build a fresh RemoteViews from the retained display state and push it to the LiveCard. A
     *  fresh instance each push is deliberate: a reused RemoteViews accumulates every bitmap and
     *  action in internal caches and re-parcels them on each setViews, which on the small Glass
     *  heap grew unbounded until decode OOM'd (glass-nav). Must be called on the render thread. */
    private void renderViews() {
        RemoteViews v = new RemoteViews(getPackageName(), R.layout.livecard_nav);
        applyCorner(v, R.id.corner_top_left_dark, R.id.corner_top_left_light, dispTopLeft);
        applyCorner(v, R.id.corner_top_right_dark, R.id.corner_top_right_light, dispTopRight);
        applyCorner(v, R.id.corner_bottom_left_dark, R.id.corner_bottom_left_light, dispBottomLeft);
        applyCorner(v, R.id.corner_bottom_right_dark, R.id.corner_bottom_right_light, dispBottomRight);
        v.setFloat(R.id.status, "setTextSize", dispTextSize);
        v.setTextViewText(R.id.status, dispStatus);
        v.setViewVisibility(R.id.status, dispStatus.isEmpty() ? View.GONE : View.VISIBLE);
        if (snippetBlack || snippetBuffer == null) {
            v.setImageViewResource(R.id.snippet, android.R.color.black);
        } else {
            v.setImageViewBitmap(R.id.snippet, snippetBuffer);
        }
        views = v;
        if (liveCard != null) {
            try {
                liveCard.setViews(v);
            } catch (Throwable t) {
                Log.w(TAG, "setViews failed: " + t.getMessage());
            }
        }
    }

    /**
     * Draw a position-arrow marker onto {@code canvas} at (px, py). The marker is a filled chevron
     * pointing up the bearing, with a thin white border so it reads against any underlying
     * map color.
     *
     * Visible for testing.
     */
    static void drawMarker(Canvas canvas, int px, int py, int bearingDeg100) {
        // Arrow geometry: an isoceles chevron 48 px tall, 44 px wide. Origin (0,0) sits at the
        // rider's current map position; the tip points "up" in the local frame, which we then
        // rotate by the heading so it points in the actual direction of travel.
        Path path = new Path();
        path.moveTo(0f, -28f);   // tip
        path.lineTo(22f, 20f);   // right shoulder
        path.lineTo(0f, 8f);     // inner notch (makes the chevron read as an arrow, not a triangle)
        path.lineTo(-22f, 20f);  // left shoulder
        path.close();

        canvas.save();
        canvas.translate(px, py);
        if (bearingDeg100 != Packet.Progress.MARKER_NONE) {
            canvas.rotate(bearingDeg100 / 100f);
        }
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(Color.rgb(30, 144, 255)); // dodger-blue, high contrast against road greys
        canvas.drawPath(path, fill);
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2f);
        stroke.setColor(Color.WHITE);
        canvas.drawPath(path, stroke);
        canvas.restore();
    }

    /**
     * Called by {@code PacketDispatcher} when the transport reports a disconnect. We don't fire
     * the alert immediately — the phone reconnects with exponential backoff and a brief BT hiccup
     * is the common case. After a {@value #DISCONNECT_ALERT_DELAY_MS}ms grace period we wake the
     * screen and surface a fullscreen "DISCONNECTED" message until {@link #onTransportConnected}
     * is invoked.
     */
    public void onTransportDisconnected() {
        if (mainHandler == null) return;
        mainHandler.removeCallbacks(disconnectAlertRunnable);
        mainHandler.postDelayed(disconnectAlertRunnable, DISCONNECT_ALERT_DELAY_MS);
    }

    /** Cancel any pending disconnect alert, release the disconnect wake lock, and clear any
     *  "DISCONNECTED" text left over from a previous alert so the user sees an empty card while
     *  fresh nav packets are en route. */
    public void onTransportConnected() {
        if (mainHandler != null) mainHandler.removeCallbacks(disconnectAlertRunnable);
        if (disconnectWake != null && disconnectWake.isHeld()) {
            try { disconnectWake.release(); } catch (Throwable t) {
                Log.w(TAG, "disconnectWake.release failed: " + t.getMessage());
            }
        }
        // Serialize the view reset onto the render thread alongside nav frames.
        if (renderHandler != null) renderHandler.post(() -> {
            if (views == null) return;
            dispTextSize = 22f;
            dispStatus = "";
            renderViews();
        });
    }

    private void showDisconnectAlert() {
        Log.w(TAG, "phone still disconnected after grace period — alerting user");
        // Drop any pending nav frame and run the alert paint on the render thread so it can't be
        // overwritten by a stale frame that was queued before the disconnect.
        pendingFrame = null;
        if (renderHandler != null) renderHandler.post(() -> {
            if (views == null) return;
            renderHandler.removeCallbacks(renderPendingRunnable);
            // Oversize the status TextView so DISCONNECTED reads at a glance from the riding
            // position; renderPendingFrame resets it back to 22sp on the next nav update.
            dispTextSize = 48f;
            dispStatus = "DISCONNECTED";
            dispTopLeft = "";
            dispTopRight = "";
            dispBottomLeft = "";
            dispBottomRight = "";
            snippetBlack = true;
            renderViews();
            if (liveCard != null) {
                try { liveCard.navigate(); } catch (Throwable t) {
                    Log.w(TAG, "liveCard.navigate failed: " + t.getMessage());
                }
            }
        });
        if (disconnectWake != null && !disconnectWake.isHeld()) {
            try { disconnectWake.acquire(DISCONNECT_WAKE_MS); } catch (Throwable t) {
                Log.w(TAG, "disconnectWake.acquire failed: " + t.getMessage());
            }
        }
    }

    /**
     * Called by {@code PacketDispatcher} when the rider has entered the approach radius for a
     * turn. Wakes the display and brings the LiveCard to the front of the timeline. Idempotent
     * for the same {@code turnIndex} so it can be invoked on every PROGRESS packet.
     */
    public void onApproachingTurn(int turnIndex) {
        if (approachingTurnIndex == turnIndex) return;
        approachingTurnIndex = turnIndex;
        Log.i(TAG, "approaching turn #" + turnIndex + " — waking display");
        if (liveCard != null) {
            try {
                liveCard.navigate();
            } catch (Throwable t) {
                Log.w(TAG, "liveCard.navigate failed: " + t.getMessage());
            }
        }
        if (screenWake != null && !screenWake.isHeld()) {
            try {
                // A configurable timeout (screenWakeMs) bounds how long the display stays bright per
                // turn: once it elapses the lock auto-releases and the screen dims on its normal
                // timeout, even if the turn isn't passed yet (e.g. the rider stopped within the
                // approach radius). 0 means hold indefinitely until onTurnPassed. The per-turn
                // idempotence guard above means this fires at most once per turn approach; a glance-up
                // re-surfaces the card via the ACTION_SCREEN_ON receiver.
                long wakeMs = screenWakeMs;
                if (wakeMs > 0) {
                    screenWake.acquire(wakeMs);
                } else {
                    screenWake.acquire();
                }
            } catch (Throwable t) {
                Log.w(TAG, "wakeLock.acquire failed: " + t.getMessage());
            }
        }
    }

    /**
     * Called by {@code PacketDispatcher} when a DisplayConfig arrives. Sets how long the display is
     * held bright after waking for an approaching turn. {@code seconds <= 0}
     * ({@link Packet.DisplayConfig#SCREEN_WAKE_NO_TIMEOUT}) restores the unbounded hold-until-passed
     * behavior. Takes effect on the next {@link #onApproachingTurn}.
     */
    public void setScreenWakeTimeout(int seconds) {
        screenWakeMs = seconds > 0 ? seconds * 1000L : 0L;
    }

    /**
     * Called by {@code PacketDispatcher} when the active turn has been passed (turn index
     * advanced) or the route has ended. Releases the wake lock so the screen can dim on its
     * normal timeout. Idempotent.
     */
    public void onTurnPassed() {
        if (approachingTurnIndex == -1) return;
        Log.i(TAG, "turn #" + approachingTurnIndex + " passed — releasing display");
        approachingTurnIndex = -1;
        if (screenWake != null && screenWake.isHeld()) {
            try {
                screenWake.release();
            } catch (Throwable t) {
                Log.w(TAG, "wakeLock.release failed: " + t.getMessage());
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy");
        if (screenOnReceiver != null) {
            try { unregisterReceiver(screenOnReceiver); } catch (Throwable ignored) {}
            screenOnReceiver = null;
        }
        if (screenWake != null) {
            try { if (screenWake.isHeld()) screenWake.release(); } catch (Throwable ignored) {}
            screenWake = null;
        }
        if (disconnectWake != null) {
            try { if (disconnectWake.isHeld()) disconnectWake.release(); } catch (Throwable ignored) {}
            disconnectWake = null;
        }
        if (mainHandler != null) {
            mainHandler.removeCallbacks(disconnectAlertRunnable);
            mainHandler = null;
        }
        if (renderHandler != null) {
            renderHandler.removeCallbacks(renderPendingRunnable);
            renderHandler = null;
        }
        if (renderThread != null) {
            renderThread.quit();
            renderThread = null;
        }
        pendingFrame = null;
        decodedKey = null;
        if (decodedBase != null) { decodedBase.recycle(); decodedBase = null; }
        if (snippetBuffer != null) { snippetBuffer.recycle(); snippetBuffer = null; }
        approachingTurnIndex = -1;
        hasNavContent = false;
        if (transport != null) {
            try { transport.stop(); } catch (Throwable ignored) {}
            transport = null;
        }
        if (speaker != null) {
            try { speaker.shutdown(); } catch (Throwable ignored) {}
            speaker = null;
        }
        if (liveCard != null) {
            try {
                if (liveCard.isPublished()) liveCard.unpublish();
            } catch (Throwable ignored) {}
            liveCard = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
