package com.ninemail.imap;

import com.ninemail.config.ServerProperties;
import com.ninemail.service.AuthService;
import com.ninemail.service.MailboxService;
import com.ninemail.service.MessageService;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * IMAP Netty channel initializer
 */
@Slf4j
@RequiredArgsConstructor
public class ImapServerInitializer extends ChannelInitializer<SocketChannel> {

    private final ServerProperties properties;
    private final AuthService authService;
    private final MailboxService mailboxService;
    private final MessageService messageService;
    private final SslContext sslContext;
    private final MeterRegistry meterRegistry;
    private final boolean implicitSsl;

    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline pipeline = ch.pipeline();

        // Implicit SSL (port 993)
        if (implicitSsl && sslContext != null) {
            SslHandler sslHandler = sslContext.newHandler(ch.alloc());
            pipeline.addLast("ssl", sslHandler);
            // Log TLS handshake result for implicit SSL connections
            InetSocketAddress remoteAddr = (InetSocketAddress) ch.remoteAddress();
            String remoteIp = remoteAddr != null ? remoteAddr.getAddress().getHostAddress() : "unknown";
            sslHandler.handshakeFuture().addListener(future -> {
                if (future.isSuccess()) {
                    log.info("IMAPS TLS handshake completed for {}", remoteIp);
                } else {
                    log.warn("IMAPS TLS handshake failed for {}: {} (client may not trust the certificate)",
                            remoteIp, future.cause().getMessage());
                }
            });
        }

        // Timeout
        pipeline.addLast("idleState", new IdleStateHandler(
                0, 0, properties.getImap().getTimeout(), TimeUnit.MILLISECONDS));

        // IMAP is also line-oriented
        pipeline.addLast("framer", new DelimiterBasedFrameDecoder(
                properties.getImap().getMaxLineLength(), Delimiters.lineDelimiter()));
        pipeline.addLast("decoder", new StringDecoder(StandardCharsets.UTF_8));
        pipeline.addLast("encoder", new StringEncoder(StandardCharsets.UTF_8));

        // IMAP command handler
        pipeline.addLast("handler", new ImapCommandHandler(
                properties, authService, mailboxService, messageService, sslContext, meterRegistry));
    }
}
