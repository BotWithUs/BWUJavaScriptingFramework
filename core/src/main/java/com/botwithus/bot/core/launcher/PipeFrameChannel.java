package com.botwithus.bot.core.launcher;

import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.pipe.PipeException;

/**
 * A {@link FrameChannel} over the service's automation pipe: a
 * {@link PipeClient} with the protocol's 1 MiB frame cap. The framing
 * ({@code [u32 LE length][body]}) is the agent pipe's, so the same client
 * serves both (ADR 2.3).
 */
public final class PipeFrameChannel implements FrameChannel {

    private final PipeClient pipe;

    private PipeFrameChannel(PipeClient pipe) {
        this.pipe = pipe;
    }

    /**
     * @param pipeName the pipe's name, without the pipe namespace prefix
     * @return an opener for that pipe
     */
    public static FrameChannel.Opener opener(String pipeName) {
        return () -> {
            try {
                return new PipeFrameChannel(new PipeClient(pipeName, LauncherProtocol.MAX_BODY_BYTES));
            } catch (PipeException e) {
                throw new ChannelClosedException("cannot open " + pipeName, e);
            }
        };
    }

    @Override
    public void send(byte[] body) {
        if (body.length > LauncherProtocol.MAX_BODY_BYTES) {
            throw new IllegalArgumentException("frame body of " + body.length + " bytes is over the 1 MiB cap");
        }
        try {
            pipe.send(body);
        } catch (PipeException e) {
            throw new ChannelClosedException("send failed", e);
        }
    }

    @Override
    public int available() {
        return pipe.available();
    }

    @Override
    public byte[] read() {
        try {
            return pipe.readMessage();
        } catch (PipeException e) {
            throw new ChannelClosedException("read failed", e);
        }
    }

    @Override
    public boolean isOpen() {
        return pipe.isOpen();
    }

    @Override
    public void close() {
        pipe.close();
    }
}
