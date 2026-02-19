package com.ninemail.smtp;

import com.ninemail.config.ServerProperties;
import com.ninemail.service.AuthService;
import com.ninemail.service.MailDeliveryService;
import com.ninemail.service.MessageService;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Netty-based SMTP server
 * - RFC 5321 ESMTP
 * - AUTH PLAIN/LOGIN
 * - STARTTLS
 * - Pipelining
 */
@Slf4j
@Component
public class SmtpServer {

    private final ServerProperties properties;
    private final AuthService authService;
    private final MessageService messageService;
    private final MailDeliveryService deliveryService;
    private final SslContext sslContext;
    private final MeterRegistry meterRegistry;

    public SmtpServer(ServerProperties properties,
            AuthService authService,
            MessageService messageService,
            MailDeliveryService deliveryService,
            @Autowired(required = false) @Nullable SslContext sslContext,
            MeterRegistry meterRegistry) {
        this.properties = properties;
        this.authService = authService;
        this.messageService = messageService;
        this.deliveryService = deliveryService;
        this.sslContext = sslContext;
        this.meterRegistry = meterRegistry;
    }

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private Channel submissionChannel;
    private Channel sslServerChannel;

    @PostConstruct
    public void start() {
        Mono.fromRunnable(this::startServer)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    private void startServer() {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        try {
            // Plain-text SMTP (STARTTLS supported)
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .handler(new LoggingHandler(LogLevel.INFO))
                    .childHandler(new SmtpServerInitializer(
                            properties, authService, messageService, deliveryService, sslContext, meterRegistry, false,
                            false))
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.TCP_NODELAY, true);

            serverChannel = bootstrap.bind(properties.getSmtp().getPort()).sync().channel();
            log.info("=== SMTP Server started on port {} ===", properties.getSmtp().getPort());

            serverChannel.closeFuture().addListener(future -> {
                log.info("SMTP Server channel closed");
            });

            // Submission port (587) - plain text with STARTTLS (RFC 6409)
            if (sslContext != null) {
                ServerBootstrap submissionBootstrap = new ServerBootstrap();
                submissionBootstrap.group(bossGroup, workerGroup)
                        .channel(NioServerSocketChannel.class)
                        .handler(new LoggingHandler(LogLevel.INFO))
                        .childHandler(new SmtpServerInitializer(
                                properties, authService, messageService, deliveryService, sslContext, meterRegistry,
                                false, true))
                        .option(ChannelOption.SO_BACKLOG, 128)
                        .childOption(ChannelOption.SO_KEEPALIVE, true)
                        .childOption(ChannelOption.TCP_NODELAY, true);

                submissionChannel = submissionBootstrap.bind(properties.getSmtp().getSubmissionPort()).sync().channel();
                log.info("=== SMTP Submission started on port {} (STARTTLS + Auto-TLS) ===",
                        properties.getSmtp().getSubmissionPort());

                submissionChannel.closeFuture().addListener(future -> {
                    log.info("SMTP Submission channel closed");
                });
            }

            // Implicit-SSL SMTP (SMTPS, port 465)
            if (sslContext != null) {
                ServerBootstrap sslBootstrap = new ServerBootstrap();
                sslBootstrap.group(bossGroup, workerGroup)
                        .channel(NioServerSocketChannel.class)
                        .handler(new LoggingHandler(LogLevel.INFO))
                        .childHandler(new SmtpServerInitializer(
                                properties, authService, messageService, deliveryService, sslContext, meterRegistry,
                                true, false))
                        .option(ChannelOption.SO_BACKLOG, 128)
                        .childOption(ChannelOption.SO_KEEPALIVE, true)
                        .childOption(ChannelOption.TCP_NODELAY, true);

                sslServerChannel = sslBootstrap.bind(properties.getSmtp().getSslPort()).sync().channel();
                log.info("=== SMTPS Server started on port {} (implicit SSL) ===", properties.getSmtp().getSslPort());

                sslServerChannel.closeFuture().addListener(future -> {
                    log.info("SMTPS Server channel closed");
                });
            }

            // Startup diagnostic summary
            log.info("========== SMTP Server Diagnostic Summary ==========");
            log.info("Domain: {}", properties.getDomain());
            log.info("Advertised hostname: {}", properties.getAdvertisedHostname());
            log.info("SMTP port: {} | Submission port: {} | SMTPS port: {}",
                    properties.getSmtp().getPort(),
                    properties.getSmtp().getSubmissionPort(),
                    properties.getSmtp().getSslPort());
            log.info("TLS enabled: {}", sslContext != null);
            log.info("TLS keystore: {}", properties.getTls().getKeystorePath());
            log.info("Auth mechanisms: PLAIN LOGIN");
            log.info("IMPORTANT: Ensure firewall allows inbound TCP on ports {}, {}, {}",
                    properties.getSmtp().getPort(),
                    properties.getSmtp().getSubmissionPort(),
                    properties.getSmtp().getSslPort());
            log.info("====================================================");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("SMTP Server start interrupted", e);
        }
    }

    @PreDestroy
    public void stop() {
        log.info("Shutting down SMTP Server...");
        if (sslServerChannel != null) {
            sslServerChannel.close();
        }
        if (submissionChannel != null) {
            submissionChannel.close();
        }
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
    }
}
