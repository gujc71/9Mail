package com.ninemail.smtp;

import com.ninemail.config.ServerProperties;
import com.ninemail.service.AuthService;
import com.ninemail.service.MailDeliveryService;
import com.ninemail.service.MessageService;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.handler.ssl.OptionalSslHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * SMTP Netty channel initializer
 *
 * Port behavior:
 * - Port 25: Plain text only, STARTTLS upgrade via SMTP command
 * - Port 587: Auto-detect TLS or plain text (OptionalSslHandler + STARTTLS
 * fallback)
 * Mobile Outlook sends TLS ClientHello directly on port 587
 * - Port 465: Implicit SSL/TLS (SslHandler added immediately)
 */
@Slf4j
@RequiredArgsConstructor
public class SmtpServerInitializer extends ChannelInitializer<SocketChannel> {

    private final ServerProperties properties;
    private final AuthService authService;
    private final MessageService messageService;
    private final MailDeliveryService deliveryService;
    private final SslContext sslContext;
    private final MeterRegistry meterRegistry;
    private final boolean implicitSsl;
    private final boolean submissionPort;

    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline pipeline = ch.pipeline();

        // TCP-level connection logging (fires BEFORE TLS handshake)
        // This helps distinguish "firewall blocked" vs "TLS handshake failed"
        InetSocketAddress remoteAddr = (InetSocketAddress) ch.remoteAddress();
        String remoteIp = remoteAddr != null ? remoteAddr.getAddress().getHostAddress() : "unknown";
        int localPort = ch.localAddress().getPort();
        pipeline.addLast("tcpLog", new ChannelInboundHandlerAdapter() {
            @Override
            public void channelActive(ChannelHandlerContext ctx) throws Exception {
                log.info("SMTP TCP connection accepted on port {} from {} (implicitSsl={}, submissionPort={})",
                        localPort, remoteIp, implicitSsl, submissionPort);
                ctx.fireChannelActive();
            }

            @Override
            public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                log.info("SMTP TCP connection closed on port {} from {}", localPort, remoteIp);
                ctx.fireChannelInactive();
            }

            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                log.warn("SMTP TCP error on port {} from {}: {}", localPort, remoteIp, cause.getMessage());
                ctx.fireExceptionCaught(cause);
            }
        });

        // SSL/TLS handling for ports 465 and 587
        // Use OptionalSslHandler to auto-detect TLS or plain text on ALL SSL-capable
        // ports
        // This supports:
        // - Clients sending TLS ClientHello directly (PC Outlook with SSL)
        // - Clients sending plain text first (mobile Outlook, telnet, STARTTLS clients)
        if ((implicitSsl || submissionPort) && sslContext != null) {
            pipeline.addLast("optionalSsl", new OptionalSslHandler(sslContext) {
                @Override
                protected SslHandler newSslHandler(ChannelHandlerContext ctx, SslContext sslContext) {
                    log.info("SMTP TLS ClientHello detected from {} on port {} - auto-negotiating TLS",
                            remoteIp, localPort);
                    SslHandler handler = sslContext.newHandler(ctx.alloc());
                    handler.handshakeFuture().addListener(future -> {
                        if (future.isSuccess()) {
                            log.info("SMTP TLS handshake completed for {} on port {} (protocol={}, cipher={})",
                                    remoteIp, localPort,
                                    handler.engine().getSession().getProtocol(),
                                    handler.engine().getSession().getCipherSuite());
                            // Fire event so SmtpCommandHandler sends 220 banner AFTER TLS
                            ctx.fireUserEventTriggered(SmtpTlsEvent.TLS_ESTABLISHED);
                        } else {
                            log.warn("SMTP TLS handshake FAILED for {} on port {}: {}",
                                    remoteIp, localPort,
                                    future.cause() != null ? future.cause().getMessage() : "unknown");
                        }
                    });
                    return handler;
                }

                @Override
                protected void decode(ChannelHandlerContext ctx, io.netty.buffer.ByteBuf in, java.util.List<Object> out)
                        throws Exception {
                    // Let parent detect TLS vs plain text
                    super.decode(ctx, in, out);
                    // If OptionalSslHandler removed itself and NO SslHandler was added → plain text
                    if (ctx.pipeline().get("optionalSsl") == null && ctx.pipeline().get(SslHandler.class) == null) {
                        log.info("SMTP plain text detected from {} on port {} - STARTTLS available",
                                remoteIp, localPort);
                        ctx.fireUserEventTriggered(SmtpTlsEvent.PLAINTEXT_DETECTED);
                    }
                }
            });
        }
        // Port 25: Plain text only, STARTTLS upgrade handled in SmtpCommandHandler

        // Timeout handler
        pipeline.addLast("idleState", new IdleStateHandler(
                0, 0, properties.getSmtp().getTimeout(), TimeUnit.MILLISECONDS));

        // Line-based frame decoder (SMTP is line-oriented)
        pipeline.addLast("framer", new DelimiterBasedFrameDecoder(
                8192, Delimiters.lineDelimiter()));
        pipeline.addLast("decoder", new StringDecoder(StandardCharsets.UTF_8));
        pipeline.addLast("encoder", new StringEncoder(StandardCharsets.UTF_8));

        // SMTP command handler
        pipeline.addLast("handler", new SmtpCommandHandler(
                properties, authService, messageService, deliveryService, sslContext, meterRegistry,
                implicitSsl, submissionPort));
    }
}
