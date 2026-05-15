package dev.glass.glass.livecard;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;
import android.widget.RemoteViews;

import com.google.android.glass.timeline.LiveCard;

import dev.glass.glass.R;
import dev.glass.glass.transport.TransportFactory;
import dev.glass.protocol.transport.Transport;

import java.io.IOException;

/**
 * The Glass LiveCard host. On real Glass this publishes to the timeline and the user sees the
 * snippet + turn instruction in the prism. On the API 19 emulator there is no Glass system UI, so
 * {@code LiveCard.publish()} is a no-op (or throws); we wrap it and keep going so packets still
 * flow through the dispatch path and we can verify the byte-level round trip via logcat.
 */
public class NavLiveCardService extends Service {
    private static final String TAG = "NavLiveCardService";
    private static final String LIVECARD_TAG = "dev.glass.glass.nav";

    private LiveCard liveCard;
    private RemoteViews views;
    private Transport transport;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "onCreate");
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

    /** Called by {@code PacketDispatcher} when a new snippet/text/distance update arrives. */
    public void updateRemoteViews(byte[] pngBytes, String instruction, String distance) {
        if (views == null) return;
        if (pngBytes != null && pngBytes.length > 0) {
            android.graphics.Bitmap bm = android.graphics.BitmapFactory
                .decodeByteArray(pngBytes, 0, pngBytes.length);
            if (bm != null) views.setImageViewBitmap(R.id.snippet, bm);
        }
        if (instruction != null) views.setTextViewText(R.id.instruction, instruction);
        if (distance != null) views.setTextViewText(R.id.distance, distance);
        if (liveCard != null) {
            try {
                liveCard.setViews(views);
            } catch (Throwable t) {
                Log.w(TAG, "setViews failed: " + t.getMessage());
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
        if (transport != null) {
            try { transport.stop(); } catch (Throwable ignored) {}
            transport = null;
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
