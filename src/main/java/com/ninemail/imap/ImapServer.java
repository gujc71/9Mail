package com.ninemail.imap;

import com.ninemail.config.ServerProperties;
import com.ninemail.service.AuthService;
import com.ninemail.service.MailboxService;
import com.ninemail.service.MessageService;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
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
 * Netty-based IMAP4 server
 * - RFC 3501 (IMAP4rev1)
 * - Supports key RFC 9051 (IMAP4rev2) features
 * - IDLE, MOVE, UNSELECT extensions
 * - UID commands
 */
@Slf4j
@Component
public class ImapServer {

    private final ServerProperties properties;
    private final AuthService authService;
    private final MailboxService mailboxService;
    private final MessageService messageService;
    private final SslContext sslContext;
    private final MeterRegistry meterRegistry;

    public ImapServer(ServerProperties properties,
                      AuthService authService,
                      MailboxService mailboxService,
                      MessageService messageService,
                      @Autowired(required = false) @Nullable SslContext sslContext,
                      MeterRegistry meterRegistry) {
        this.properties = properties;
        this.authService = authService;
        this.mailboxService = mailboxService;
        this.messageService = messageService;
        this.sslContext = sslContext;
        this.meterRegistry = meterRegistry;
    }

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
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
            // Plain-text IMAP (STARTTLS supported)
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ImapServerInitializer(
                            properties, authService, mailboxService, messageService, sslContext, meterRegistry, false))
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.TCP_NODELAY, true);

            serverChannel = bootstrap.bind(properties.getImap().getPort()).sync().channel();
            log.info("=== IMAP Server started on port {} ===", properties.getImap().getPort());

            serverChannel.closeFuture().addListener(future -> {
                log.info("IMAP Server channel closed");
            });

            // Implicit-SSL IMAP (IMAPS)
            if (sslContext != null) {
                ServerBootstrap sslBootstrap = new ServerBootstrap();
                sslBootstrap.group(bossGroup, workerGroup)
                        .channel(NioServerSocketChannel.class)
                        .childHandler(new ImapServerInitializer(
                                properties, authService, mailboxService, messageService, sslContext, meterRegistry, true))
                        .option(ChannelOption.SO_BACKLOG, 128)
                        .childOption(ChannelOption.SO_KEEPALIVE, true)
                        .childOption(ChannelOption.TCP_NODELAY, true);

                sslServerChannel = sslBootstrap.bind(properties.getImap().getSslPort()).sync().channel();
                log.info("=== IMAPS Server started on port {} (implicit SSL) ===", properties.getImap().getSslPort());

                sslServerChannel.closeFuture().addListener(future -> {
                    log.info("IMAPS Server channel closed");
                });
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("IMAP Server start interrupted", e);
        }
    }

    @PreDestroy
    public void stop() {
        log.info("Shutting down IMAP Server...");
        if (sslServerChannel != null) {
            sslServerChannel.close();
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
