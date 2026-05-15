package dev.glass.protocol;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Writes {@link Packet}s as length-prefixed frames to an {@link OutputStream}. Thread-safe — each
 * {@link #write(Packet)} acquires the writer lock before flushing.
 */
public final class FrameWriter {
    private final OutputStream out;
    private final Object lock = new Object();

    public FrameWriter(OutputStream out) {
        this.out = out;
    }

    public void write(Packet p) throws IOException {
        byte[] frame = Codec.encode(p);
        synchronized (lock) {
            out.write(frame);
            out.flush();
        }
    }
}
