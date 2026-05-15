package dev.glass.protocol.transport;

import dev.glass.protocol.Packet;

import java.io.IOException;

/**
 * Bidirectional packet transport. Implementations (TCP for emulator, RFCOMM for hardware) provide
 * the byte-pipe; the {@code Transport} surfaces packets via a push-style listener.
 *
 * Lifecycle: {@code start()} → callbacks fire → {@code stop()}.
 * {@code start()} is non-blocking; the listener is invoked on the transport's own thread.
 */
public interface Transport {
    void setListener(Listener listener);

    void start() throws IOException;

    void stop();

    void send(Packet p) throws IOException;

    interface Listener {
        void onConnected();
        void onPacket(Packet p);
        void onDisconnected(Throwable cause);
    }
}
