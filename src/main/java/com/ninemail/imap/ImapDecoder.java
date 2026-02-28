package com.ninemail.imap;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.TooLongFrameException;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Combined IMAP decoder that supports both line-delimited mode and
 * byte-counted literal mode.
 *
 * <p>In <b>line mode</b> (default), splits input on CRLF or LF and
 * delivers each line as a {@link String} (delimiter stripped).</p>
 *
 * <p>In <b>literal mode</b>, reads exactly N bytes and delivers them
 * as a {@code byte[]}. This is used for IMAP APPEND literal data
 * to ensure exact byte counting regardless of line endings or encoding.</p>
 *
 * <p>Replaces {@code DelimiterBasedFrameDecoder + StringDecoder} in
 * the IMAP pipeline.</p>
 */
@Slf4j
public class ImapDecoder extends ByteToMessageDecoder {

    private final int maxLineLength;

    // Literal mode state
    private int literalBytesRemaining = 0;
    private ByteBuf literalBuffer;
    private boolean consumePostLiteralCRLF = false;

    public ImapDecoder(int maxLineLength) {
        this.maxLineLength = maxLineLength;
    }

    /**
     * Enter literal mode to read exactly {@code size} bytes.
     * Called by {@link ImapCommandHandler} when processing APPEND.
     * After the bytes are accumulated they are delivered as a single
     * {@code byte[]} message.
     */
    public void startLiteral(int size) {
        this.literalBytesRemaining = size;
    }

    /**
     * Cancel an in-progress literal accumulation.
     * Used when an error occurs during APPEND processing.
     */
    public void cancelLiteral() {
        this.literalBytesRemaining = 0;
        this.consumePostLiteralCRLF = false;
        if (literalBuffer != null) {
            literalBuffer.release();
            literalBuffer = null;
        }
    }

    /**
     * Decode one message at a time. Producing at most one output per call
     * is critical: Netty's {@code callDecode} fires each decoded message
     * through the pipeline before calling {@code decode()} again, giving the
     * handler a chance to switch this decoder to literal mode via
     * {@link #startLiteral(int)} before the next bytes are read.
     */
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (!in.isReadable()) {
            return;
        }

        // After a literal completes, consume the command-terminating CRLF
        // (RFC 3501 §4.3: the literal is followed by the rest of the command
        // line which, for APPEND, is just a CRLF).
        if (consumePostLiteralCRLF) {
            if (!skipTrailingCRLF(in)) {
                return; // need more data
            }
            consumePostLiteralCRLF = false;
            return; // yield back so callDecode can re-enter
        }

        if (literalBytesRemaining > 0) {
            decodeLiteral(ctx, in, out);
        } else {
            decodeLine(in, out);
        }
    }

    /**
     * Literal mode: accumulate exactly N bytes.
     */
    private void decodeLiteral(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        int toRead = Math.min(literalBytesRemaining, in.readableBytes());

        if (literalBuffer == null) {
            literalBuffer = ctx.alloc().buffer(literalBytesRemaining);
        }

        in.readBytes(literalBuffer, toRead);
        literalBytesRemaining -= toRead;

        if (literalBytesRemaining == 0) {
            byte[] data = new byte[literalBuffer.readableBytes()];
            literalBuffer.readBytes(data);
            literalBuffer.release();
            literalBuffer = null;
            out.add(data);
            consumePostLiteralCRLF = true;
        }
    }

    /**
     * Line mode: split on CRLF or LF, deliver as String.
     *
     * @return true if a complete line was found and emitted
     */
    private boolean decodeLine(ByteBuf in, List<Object> out) {
        int startIndex = in.readerIndex();
        int readableBytes = in.readableBytes();

        for (int i = 0; i < readableBytes; i++) {
            byte b = in.getByte(startIndex + i);
            if (b == '\n') {
                int lineLength = i;
                boolean hasCR = lineLength > 0
                        && in.getByte(startIndex + lineLength - 1) == '\r';
                int textLength = hasCR ? lineLength - 1 : lineLength;

                if (textLength > maxLineLength) {
                    // Advance past the bad frame so we can recover
                    in.readerIndex(startIndex + i + 1);
                    throw new TooLongFrameException(
                            "IMAP frame length (" + textLength + ") exceeds " + maxLineLength);
                }

                // Read line text (without CR/LF)
                String line;
                if (textLength > 0) {
                    byte[] lineBytes = new byte[textLength];
                    in.readBytes(lineBytes);
                    line = new String(lineBytes, StandardCharsets.UTF_8);
                } else {
                    line = "";
                }

                // Skip delimiter bytes (\r\n or \n)
                if (hasCR) {
                    in.readByte(); // skip \r
                }
                in.readByte(); // skip \n

                out.add(line);
                return true;
            }
        }

        // No complete line yet – check if buffered data already exceeds max
        if (readableBytes > maxLineLength) {
            throw new TooLongFrameException(
                    "IMAP frame length (" + readableBytes + ") exceeds " + maxLineLength);
        }

        return false;
    }

    /**
     * Skip one trailing CRLF (or bare LF) after an IMAP literal.
     * If the next byte is not a line-ending character, the flag is
     * cleared without consuming anything (some clients may omit it).
     *
     * @return true if done (CRLF consumed or not present),
     *         false if more data is needed (only \r seen so far)
     */
    private boolean skipTrailingCRLF(ByteBuf in) {
        if (!in.isReadable()) {
            return false;
        }

        int idx = in.readerIndex();
        byte b = in.getByte(idx);

        if (b == '\r') {
            if (in.readableBytes() >= 2) {
                if (in.getByte(idx + 1) == '\n') {
                    in.skipBytes(2);
                } else {
                    in.skipBytes(1);
                }
                return true;
            }
            return false; // need one more byte to decide
        } else if (b == '\n') {
            in.skipBytes(1);
            return true;
        }

        // Next byte is not a line ending — no trailing CRLF to skip
        return true;
    }

    @Override
    protected void handlerRemoved0(ChannelHandlerContext ctx) {
        if (literalBuffer != null) {
            literalBuffer.release();
            literalBuffer = null;
        }
    }
}
