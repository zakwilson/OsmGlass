package com.goodanser.osmglass.protocol;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Stateful frame reassembler. Reads one full frame at a time from an {@link InputStream},
 * blocking until the frame is complete. Reuses the underlying stream's blocking read semantics.
 */
public final class FrameReader {
    private final DataInputStream in;

    public FrameReader(InputStream in) {
        this.in = new DataInputStream(in);
    }

    /**
     * Read the next packet, blocking until one is available.
     *
     * @return the decoded packet, or null on clean end-of-stream.
     * @throws ProtocolException for invalid frames.
     * @throws IOException for transport errors.
     */
    public Packet readNext() throws IOException {
        byte magic;
        try {
            magic = in.readByte();
        } catch (EOFException eof) {
            return null;
        }
        if (magic != Codec.MAGIC) {
            throw new ProtocolException(String.format("bad magic 0x%02x", magic & 0xff));
        }
        byte ver = in.readByte();
        if (ver != Codec.PROTO_VERSION) {
            throw new ProtocolException("unsupported protoVersion " + (ver & 0xff));
        }
        byte typeCode = in.readByte();
        int payloadLen = in.readInt();
        if (payloadLen < 0 || payloadLen > Codec.MAX_PAYLOAD_LEN) {
            throw new ProtocolException("invalid payloadLen " + payloadLen);
        }
        byte[] payload = new byte[payloadLen];
        in.readFully(payload);
        PacketType type = PacketType.fromCode(typeCode);
        return Codec.decodePayload(type, payload);
    }
}
