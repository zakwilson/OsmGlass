package dev.glass.glass.transport;

import android.os.Build;
import android.util.Log;

import dev.glass.protocol.transport.TcpTransport;
import dev.glass.protocol.transport.Transport;

import java.lang.reflect.Method;

/**
 * Glass-side transport selector mirroring the phone's {@code TransportFactory}.
 *
 * Resolution: setprop > BuildConfig > auto (emulator → TCP, real device → RFCOMM).
 */
public final class TransportFactory {
    private static final String TAG = "TransportFactory";
    private TransportFactory() {}

    public static Transport create() {
        String kind = resolveKind();
        Log.i(TAG, "creating transport: kind=" + kind);
        switch (kind) {
            case "rfcomm":
                return new RfcommServerTransport();
            case "tcp":
                return new TcpTransport(TcpTransport.Role.SERVER, "0.0.0.0", 8765);
            default:
                throw new IllegalStateException("unknown transport kind: " + kind);
        }
    }

    private static String resolveKind() {
        // Resolution order: setprop override > auto-detect (emulator → TCP, real device → RFCOMM).
        // BuildConfig.TRANSPORT_KIND is ignored: the debug build sets it to "tcp" for emulator
        // pair tests, but on a real Glass we always want RFCOMM unless explicitly overridden.
        String prop = systemProp("dev.glass.transport");
        if (prop != null && !prop.isEmpty()) return prop.toLowerCase();
        return isEmulator() ? "tcp" : "rfcomm";
    }

    private static boolean isEmulator() {
        String product = String.valueOf(Build.PRODUCT).toLowerCase();
        String brand = String.valueOf(Build.BRAND).toLowerCase();
        return product.contains("sdk") || product.contains("emulator")
            || brand.startsWith("generic")
            || String.valueOf(Build.FINGERPRINT).startsWith("generic");
    }

    private static String systemProp(String key) {
        try {
            Class<?> cls = Class.forName("android.os.SystemProperties");
            Method get = cls.getMethod("get", String.class);
            Object v = get.invoke(null, key);
            return v instanceof String ? (String) v : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
