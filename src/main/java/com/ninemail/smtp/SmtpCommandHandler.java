package com.ninemail.smtp;

import com.ninemail.config.ServerProperties;
import com.ninemail.service.AuthService;
import com.ninemail.service.MailDeliveryService;
import com.ninemail.service.MessageService;
import com.ninemail.util.CryptoUtil;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * Netty-based SMTP command handler
 * RFC 5321 compliant ESMTP server
 */
@Slf4j
public class SmtpCommandHandler extends SimpleChannelInboundHandler<String> {

    private final SmtpSession session = new SmtpSession();
    private final ServerProperties properties;
    private final AuthService authService;
    private final MessageService messageService;
    private final MailDeliveryService deliveryService;
    private final SslContext sslContext;
    private final Counter mailReceivedCounter;
    private final Counter authFailureCounter;
    private final boolean implicitSsl;
    private final boolean submissionPort;
    private boolean bannerSent = false;

    public SmtpCommandHandler(ServerProperties properties,
            AuthService authService,
            MessageService messageService,
            MailDeliveryService deliveryService,
            SslContext sslContext,
            MeterRegistry meterRegistry,
            boolean implicitSsl,
            boolean submissionPort) {
        this.properties = properties;
        this.authService = authService;
        this.messageService = messageService;
        this.deliveryService = deliveryService;
        this.sslContext = sslContext;
        this.implicitSsl = implicitSsl;
        this.submissionPort = submissionPort;
        this.mailReceivedCounter = Counter.builder("smtp.mail.received")
                .description("Number of mails received")
                .register(meterRegistry);
        this.authFailureCounter = Counter.builder("smtp.auth.failure")
                .description("Authentication failures")
                .register(meterRegistry);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        InetSocketAddress remoteAddr = (InetSocketAddress) ctx.channel().remoteAddress();
        session.setRemoteIp(remoteAddr.getAddress().getHostAddress());
        log.info("SMTP connection from: {} (implicitSsl={}, submissionPort={})",
                session.getRemoteIp(), implicitSsl, submissionPort);

        if (implicitSsl) {
            // Port 465: Implicit SSL — always TLS first.
            // Defer banner until TLS_ESTABLISHED event from OptionalSslHandler.
            log.debug("SMTP [465] deferring banner for {} until TLS handshake completes", session.getRemoteIp());
        } else if (submissionPort) {
            // Port 587: Could be TLS ClientHello (mobile Outlook SSL) or plain STARTTLS.
            // SMTP is server-speaks-first: STARTTLS clients wait for 220 banner.
            // If client sends TLS ClientHello, it arrives within ~100ms.
            // Schedule banner after 300ms; if TLS completes first, banner is sent
            // encrypted and the scheduled task is a no-op (bannerSent=true).
            log.debug("SMTP [587] scheduling deferred banner for {} (300ms TLS detection window)",
                    session.getRemoteIp());
            ctx.executor().schedule(() -> {
                if (ctx.channel().isActive() && !bannerSent) {
                    log.info("SMTP [587] no TLS ClientHello from {} within 300ms, sending plain banner (STARTTLS mode)",
                            session.getRemoteIp());
                    sendBanner(ctx);
                }
            }, 300, java.util.concurrent.TimeUnit.MILLISECONDS);
        } else {
            // Port 25: always plain text, send banner immediately
            sendBanner(ctx);
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof SmtpTlsEvent tlsEvent) {
            switch (tlsEvent) {
                case TLS_ESTABLISHED -> {
                    session.setTlsActive(true);
                    log.info("SMTP TLS established for {}, sending deferred banner", session.getRemoteIp());
                    sendBanner(ctx);
                }
                case PLAINTEXT_DETECTED -> {
                    log.info("SMTP plain text confirmed for {}, sending deferred banner", session.getRemoteIp());
                    sendBanner(ctx);
                }
            }
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    private void sendBanner(ChannelHandlerContext ctx) {
        if (!bannerSent) {
            bannerSent = true;
            ctx.writeAndFlush(
                    "220 " + properties.getAdvertisedHostname() + " " + properties.getSmtp().getBanner() + "\r\n");
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, String msg) {
        // Detect if TLS was auto-negotiated via OptionalSslHandler
        if (!session.isTlsActive() && ctx.pipeline().get(SslHandler.class) != null) {
            session.setTlsActive(true);
            log.info("SMTP TLS auto-detected for {}", session.getRemoteIp());
        }

        // DATA state: preserve original line content (RFC 5321 - no trimming)
        if (session.getState() == SmtpState.DATA) {
            handleDataLine(ctx, msg);
            return;
        }

        String line = msg.trim();

        // Detect binary/TLS data on plain-text port
        if (isBinaryData(line)) {
            log.warn("SMTP received binary data from {} - likely TLS ClientHello on plain-text port. "
                    + "Client should use port {} for implicit SSL/TLS or STARTTLS on this port.",
                    session.getRemoteIp(), properties.getSmtp().getSslPort());
            respond(ctx, "500 5.5.1 Plain text connection required. Use STARTTLS or connect to port "
                    + properties.getSmtp().getSslPort() + " for SSL/TLS");
            ctx.close();
            return;
        }

        log.debug("SMTP << {}", line);

        // AUTH PLAIN continuation
        if (session.getState() == SmtpState.AUTH_PLAIN_INPUT) {
            handleAuthPlainInput(ctx, line);
            return;
        }
        // AUTH LOGIN in progress
        if (session.getState() == SmtpState.AUTH_LOGIN_USERNAME) {
            handleAuthLoginUsername(ctx, line);
            return;
        }
        if (session.getState() == SmtpState.AUTH_LOGIN_PASSWORD) {
            handleAuthLoginPassword(ctx, line);
            return;
        }

        // Command parsing
        String upperLine = line.toUpperCase();
        if (upperLine.startsWith("EHLO") || upperLine.startsWith("HELO")) {
            handleEhlo(ctx, line);
        } else if (upperLine.startsWith("MAIL FROM:")) {
            handleMailFrom(ctx, line);
        } else if (upperLine.startsWith("RCPT TO:")) {
            handleRcptTo(ctx, line);
        } else if (upperLine.equals("DATA")) {
            handleData(ctx);
        } else if (upperLine.startsWith("AUTH")) {
            handleAuth(ctx, line);
        } else if (upperLine.equals("STARTTLS")) {
            handleStartTls(ctx);
        } else if (upperLine.equals("RSET")) {
            handleRset(ctx);
        } else if (upperLine.equals("NOOP")) {
            respond(ctx, "250 2.0.0 OK");
        } else if (upperLine.startsWith("VRFY")) {
            respond(ctx, "252 2.5.2 Cannot VRFY user, but will accept message and attempt delivery");
        } else if (upperLine.equals("QUIT")) {
            handleQuit(ctx);
        } else {
            respond(ctx, "500 5.5.1 Unrecognized command");
        }
    }

    // ======== EHLO / HELO ========
    private void handleEhlo(ChannelHandlerContext ctx, String line) {
        String[] parts = line.split("\\s+", 2);
        session.setClientHostname(parts.length > 1 ? parts[1] : "unknown");
        session.setState(SmtpState.GREETED);

        boolean isEhlo = line.toUpperCase().startsWith("EHLO");
        if (isEhlo) {
            StringBuilder response = new StringBuilder();
            response.append("250-").append(properties.getAdvertisedHostname()).append(" Hello ")
                    .append(session.getClientHostname()).append("\r\n");
            response.append("250-SIZE ").append(properties.getSmtp().getMaxMessageSize()).append("\r\n");
            response.append("250-8BITMIME\r\n");
            response.append("250-PIPELINING\r\n");
            response.append("250-CHUNKING\r\n");
            // RFC 4954: advertise AUTH only when TLS is active (or on plain port 25).
            // On submission port (587), hiding AUTH before STARTTLS forces clients to
            // upgrade to TLS first, then authenticate — which is what Outlook Android
            // expects.
            if (session.isTlsActive() || !submissionPort) {
                response.append("250-AUTH PLAIN LOGIN\r\n");
            }
            if (sslContext != null && !session.isTlsActive()) {
                response.append("250-STARTTLS\r\n");
            }
            response.append("250 ENHANCEDSTATUSCODES");
            log.debug("SMTP >> EHLO response: {}", response.toString().replace("\r\n", " | "));
            ctx.writeAndFlush(response + "\r\n");
        } else {
            respond(ctx, "250 " + properties.getAdvertisedHostname() + " Hello " + session.getClientHostname());
        }
    }

    // ======== AUTH ========
    private void handleAuth(ChannelHandlerContext ctx, String line) {
        if (session.getState() != SmtpState.GREETED) {
            respond(ctx, "503 5.5.1 Bad sequence of commands");
            return;
        }

        String[] parts = line.split("\\s+");
        if (parts.length < 2) {
            respond(ctx, "501 5.5.4 Syntax: AUTH mechanism [initial-response]");
            return;
        }

        String mechanism = parts[1].toUpperCase();
        log.debug("SMTP AUTH mechanism: {}", mechanism);
        switch (mechanism) {
            case "PLAIN" -> {
                if (parts.length >= 3) {
                    // Inline credentials
                    String[] creds = CryptoUtil.decodeAuthPlain(parts[2]);
                    log.debug("SMTP AUTH PLAIN inline - user: {}", creds != null ? creds[0] : "null");
                    if (authService.authenticatePlain(parts[2])) {
                        session.setAuthenticated(true);
                        session.setAuthenticatedUser(creds != null ? creds[0] : "unknown");
                        respond(ctx, "235 2.7.0 Authentication successful");
                        log.info("SMTP AUTH success: {}", session.getAuthenticatedUser());
                    } else {
                        log.warn("SMTP AUTH PLAIN failed for user: {}", creds != null ? creds[0] : "unknown");
                        handleAuthFailure(ctx);
                    }
                } else {
                    // Client will send credentials in next line
                    respond(ctx, "334 ");
                    session.setState(SmtpState.AUTH_PLAIN_INPUT);
                }
            }
            case "LOGIN" -> {
                respond(ctx, "334 VXNlcm5hbWU6"); // Base64("Username:")
                session.setState(SmtpState.AUTH_LOGIN_USERNAME);
            }
            case "XOAUTH2" -> {
                log.debug("SMTP AUTH XOAUTH2 not supported, rejecting");
                respond(ctx, "504 5.5.4 Unrecognized authentication mechanism");
            }
            default -> {
                log.debug("SMTP AUTH unsupported mechanism: {}", mechanism);
                respond(ctx, "504 5.5.4 Unrecognized authentication mechanism");
            }
        }
    }

    /**
     * Handle AUTH PLAIN continuation (credentials sent after 334 response)
     */
    private void handleAuthPlainInput(ChannelHandlerContext ctx, String line) {
        String[] creds = CryptoUtil.decodeAuthPlain(line);
        log.debug("SMTP AUTH PLAIN continuation - user: {}", creds != null ? creds[0] : "null");
        if (creds != null && authService.authenticate(creds[0], creds[1])) {
            session.setAuthenticated(true);
            session.setAuthenticatedUser(creds[0]);
            session.setState(SmtpState.GREETED);
            respond(ctx, "235 2.7.0 Authentication successful");
            log.info("SMTP AUTH success: {}", creds[0]);
        } else {
            session.setState(SmtpState.GREETED);
            log.warn("SMTP AUTH PLAIN continuation failed for user: {}", creds != null ? creds[0] : "unknown");
            handleAuthFailure(ctx);
        }
    }

    private void handleAuthLoginUsername(ChannelHandlerContext ctx, String line) {
        String username = authService.decodeLoginUsername(line);
        log.debug("SMTP AUTH LOGIN username: {}", username);
        session.setAuthLoginUsername(username);
        respond(ctx, "334 UGFzc3dvcmQ6"); // Base64("Password:")
        session.setState(SmtpState.AUTH_LOGIN_PASSWORD);
    }

    private void handleAuthLoginPassword(ChannelHandlerContext ctx, String line) {
        String password = authService.decodeLoginPassword(line);
        log.debug("SMTP AUTH LOGIN attempt for: {}", session.getAuthLoginUsername());
        if (authService.authenticate(session.getAuthLoginUsername(), password)) {
            session.setAuthenticated(true);
            session.setAuthenticatedUser(session.getAuthLoginUsername());
            session.setState(SmtpState.GREETED);
            respond(ctx, "235 2.7.0 Authentication successful");
            log.info("SMTP AUTH success: {}", session.getAuthLoginUsername());
        } else {
            session.setState(SmtpState.GREETED);
            log.warn("SMTP AUTH LOGIN failed for: {}", session.getAuthLoginUsername());
            handleAuthFailure(ctx);
        }
    }

    private void handleAuthFailure(ChannelHandlerContext ctx) {
        session.setAuthFailureCount(session.getAuthFailureCount() + 1);
        authFailureCounter.increment();

        if (session.getAuthFailureCount() >= properties.getSecurity().getMaxAuthFailures()) {
            respond(ctx, "421 4.7.0 Too many authentication failures, disconnecting");
            ctx.close();
        } else {
            // Tarpitting: delayed response
            ctx.executor().schedule(() -> {
                if (ctx.channel().isActive()) {
                    respond(ctx, "535 5.7.8 Authentication credentials invalid");
                }
            }, properties.getSecurity().getTarpitDelayMs(), java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    // ======== STARTTLS ========
    private void handleStartTls(ChannelHandlerContext ctx) {
        if (sslContext == null) {
            respond(ctx, "454 4.7.0 TLS not available");
            return;
        }
        if (session.isTlsActive()) {
            respond(ctx, "503 5.5.1 TLS already active");
            return;
        }
        // Send 220 in plain text FIRST, then add SslHandler after write completes
        log.info("SMTP STARTTLS initiated by {}", session.getRemoteIp());
        ctx.writeAndFlush("220 2.0.0 Ready to start TLS\r\n").addListener(future -> {
            if (future.isSuccess()) {
                SslHandler sslHandler = sslContext.newHandler(ctx.alloc());
                ctx.pipeline().addFirst("ssl", sslHandler);
                sslHandler.handshakeFuture().addListener(hsFuture -> {
                    if (hsFuture.isSuccess()) {
                        log.info("SMTP STARTTLS handshake completed for {}", session.getRemoteIp());
                    } else {
                        log.error("SMTP STARTTLS handshake failed for {}: {}",
                                session.getRemoteIp(), hsFuture.cause().getMessage());
                        ctx.close();
                    }
                });
                session.resetAfterTls();
            } else {
                log.error("SMTP failed to send STARTTLS response to {}", session.getRemoteIp());
                ctx.close();
            }
        });
    }

    // ======== MAIL FROM ========
    private void handleMailFrom(ChannelHandlerContext ctx, String line) {
        if (session.getState() != SmtpState.GREETED) {
            respond(ctx, "503 5.5.1 Bad sequence of commands");
            return;
        }

        // Enforce authentication when require-auth=true (submission port)
        // [Modified] Removed strict check here to allow "Same Domain" logic in RCPT TO.
        // Original strict check prevented unauthenticated MAIL FROM completely.
        /*
         * if (properties.getSmtp().isRequireAuth() && !session.isAuthenticated()) {
         * log.
         * warn("SMTP MAIL FROM rejected: require-auth=true but not authenticated ({})",
         * session.getRemoteIp());
         * respond(ctx, "530 5.7.0 Authentication required");
         * return;
         * }
         */

        String from = extractAddress(line, "MAIL FROM:");
        if (from == null) {
            respond(ctx, "501 5.1.7 Syntax error in MAIL FROM address");
            return;
        }

        session.setMailFrom(CryptoUtil.stripAngleBrackets(from));
        session.setState(SmtpState.MAIL_FROM);
        respond(ctx, "250 2.1.0 OK");
    }

    // ======== RCPT TO ========
    private void handleRcptTo(ChannelHandlerContext ctx, String line) {
        if (session.getState() != SmtpState.MAIL_FROM && session.getState() != SmtpState.RCPT_TO) {
            respond(ctx, "503 5.5.1 Bad sequence of commands");
            return;
        }

        if (session.getRecipients().size() >= properties.getSmtp().getMaxRecipients()) {
            respond(ctx, "452 4.5.3 Too many recipients");
            return;
        }

        String to = extractAddress(line, "RCPT TO:");
        if (to == null) {
            respond(ctx, "501 5.1.3 Syntax error in RCPT TO address");
            return;
        }

        String rcptEmail = CryptoUtil.stripAngleBrackets(to);
        String rcptDomain = CryptoUtil.extractDomain(rcptEmail);
        String senderDomain = CryptoUtil.extractDomain(session.getMailFrom());

        // Logic: Same Domain AND Local Target -> No Auth Required
        // Different Domain OR External Target -> Auth or Relay Required
        boolean sameDomain = (senderDomain != null && senderDomain.equalsIgnoreCase(rcptDomain));
        boolean rcptIsLocal = authService.isLocalDomain(rcptDomain);

        if (sameDomain && rcptIsLocal) {
            // Case 1: Same Local Domain (sender == rcpt == local)
            // Allow without auth (Enables Telnet/Script usage for internal mail)
            log.debug("SMTP Same Domain ({}) -> ({}) allowed without auth", senderDomain, rcptDomain);
        } else {
            // Case 2: Different Domain OR External->External (External Relay Prevention)
            // Check if Relaying (Auth or Trusted IP) is allowed
            if (!authService.canRelayExternal(session.isAuthenticated(), session.getRemoteIp())) {
                respond(ctx, "550 5.7.1 Relaying denied. Authenticate or use a permitted relay IP.");
                return;
            }
        }

        // Check Local User Existence (if recipient is local)
        if (rcptIsLocal) {
            if (!authService.isLocalUser(rcptEmail)) {
                respond(ctx, "550 5.1.1 Unknown user: " + rcptEmail);
                return;
            }
        }

        session.addRecipient(rcptEmail);
        session.setState(SmtpState.RCPT_TO);
        respond(ctx, "250 2.1.5 OK");
    }

    // ======== DATA ========
    private void handleData(ChannelHandlerContext ctx) {
        if (session.getState() != SmtpState.RCPT_TO) {
            respond(ctx, "503 5.5.1 Bad sequence of commands");
            return;
        }
        session.setState(SmtpState.DATA);
        respond(ctx, "354 Start mail input; end with <CRLF>.<CRLF>");
    }

    private void handleDataLine(ChannelHandlerContext ctx, String rawLine) {
        // Strip trailing CR/LF but preserve leading whitespace (RFC 5321)
        String line = rawLine;
        while (line.endsWith("\r") || line.endsWith("\n")) {
            line = line.substring(0, line.length() - 1);
        }

        if (".".equals(line)) {
            // End of DATA
            processReceivedMail(ctx);
            session.setState(SmtpState.GREETED);
        } else {
            // Remove byte-stuffing
            String dataLine = line.startsWith("..") ? line.substring(1) : line;
            session.appendData(dataLine);

            // Enforce max size
            if (session.getDataBuffer().length() > properties.getSmtp().getMaxMessageSize()) {
                respond(ctx, "552 5.3.4 Message too large");
                session.resetTransaction();
            }
        }
    }

    private void processReceivedMail(ChannelHandlerContext ctx) {
        try {
            byte[] emlData = session.getDataBytes();
            List<String> recipients = session.getRecipients();
            String sender = session.getMailFrom();

            // Deliver to internal recipients
            String messageId = messageService.processIncomingMail(emlData, sender, recipients);

            // Async deliver to external recipients
            for (String rcpt : recipients) {
                String domain = CryptoUtil.extractDomain(rcpt);
                if (!authService.isLocalDomain(domain)) {
                    deliveryService.deliverExternalMail(emlData, sender, rcpt).subscribe();
                }
            }

            mailReceivedCounter.increment();
            respond(ctx, "250 2.0.0 OK: queued as " + messageId);
            session.resetTransaction();

        } catch (Exception e) {
            log.error("Failed to process DATA", e);
            respond(ctx, "451 4.3.0 Mail processing error");
            session.resetTransaction();
        }
    }

    // ======== RSET ========
    private void handleRset(ChannelHandlerContext ctx) {
        session.resetTransaction();
        respond(ctx, "250 2.0.0 OK");
    }

    // ======== QUIT ========
    private void handleQuit(ChannelHandlerContext ctx) {
        respond(ctx, "221 2.0.0 " + properties.getAdvertisedHostname() + " closing connection");
        ctx.close();
    }

    // ======== Utilities ========
    private void respond(ChannelHandlerContext ctx, String response) {
        log.debug("SMTP >> {}", response);
        ctx.writeAndFlush(response + "\r\n");
    }

    private String extractAddress(String line, String prefix) {
        int idx = line.toUpperCase().indexOf(prefix.toUpperCase());
        if (idx < 0)
            return null;
        String addr = line.substring(idx + prefix.length()).trim();
        // Strip optional SIZE= parameter
        int spaceIdx = addr.indexOf(' ');
        if (spaceIdx > 0)
            addr = addr.substring(0, spaceIdx);
        return addr.isEmpty() ? null : addr;
    }

    /**
     * Check if data contains binary/non-printable characters (TLS ClientHello etc.)
     */
    private boolean isBinaryData(String data) {
        if (data == null || data.isEmpty())
            return false;
        int nonPrintable = 0;
        for (int i = 0; i < Math.min(data.length(), 20); i++) {
            char c = data.charAt(i);
            if (c < 0x20 && c != '\t' && c != '\r' && c != '\n') {
                nonPrintable++;
            }
        }
        return nonPrintable > 3;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        String ip = session.getRemoteIp();
        if (ip == null) {
            InetSocketAddress addr = (InetSocketAddress) ctx.channel().remoteAddress();
            ip = addr != null ? addr.getAddress().getHostAddress() : "unknown";
        }
        String msg = cause.getMessage();
        if (cause instanceof javax.net.ssl.SSLHandshakeException
                || (cause.getCause() != null && cause.getCause() instanceof javax.net.ssl.SSLHandshakeException)) {
            log.warn("SMTP TLS handshake failed from {}: {} (client may not trust the server certificate)", ip, msg);
        } else if ("Connection reset".equals(msg) || cause instanceof java.io.IOException) {
            log.debug("SMTP connection reset from {}: {}", ip, msg);
        } else {
            log.error("SMTP error from {}: {}", ip, msg);
        }
        ctx.close();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.info("SMTP connection closed: {}", session.getRemoteIp());
    }
}
