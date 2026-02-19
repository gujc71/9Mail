package com.ninemail.imap;

import com.ninemail.config.ServerProperties;
import com.ninemail.domain.MailMail;
import com.ninemail.domain.MailMailbox;
import com.ninemail.service.AuthService;
import com.ninemail.service.MailboxService;
import com.ninemail.service.MessageService;
import com.ninemail.util.CryptoUtil;
import com.ninemail.util.EmlParser;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;

import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMultipart;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.net.InetSocketAddress;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Netty-based IMAP4rev1 command handler
 * RFC 3501 / RFC 9051 compliant
 *
 * Supported commands:
 * - Global: CAPABILITY, NOOP, LOGOUT, STARTTLS
 * - Not authenticated: LOGIN, AUTHENTICATE
 * - Authenticated: SELECT, EXAMINE, CREATE, DELETE, RENAME, SUBSCRIBE,
 * UNSUBSCRIBE,
 * LIST, LSUB, STATUS, APPEND
 * - Selected: FETCH, STORE, SEARCH, COPY, MOVE, EXPUNGE, CLOSE, UNSELECT, IDLE
 * - UID prefix: UID FETCH, UID STORE, UID COPY, UID MOVE, UID SEARCH, UID
 * EXPUNGE
 */
@Slf4j
public class ImapCommandHandler extends SimpleChannelInboundHandler<String> {

    private final ImapSession session = new ImapSession();
    private final ServerProperties properties;
    private final AuthService authService;
    private final MailboxService mailboxService;
    private final MessageService messageService;
    private final SslContext sslContext;

    @SuppressWarnings("unused")
    private final MeterRegistry meterRegistry;

    public ImapCommandHandler(ServerProperties properties,
            AuthService authService,
            MailboxService mailboxService,
            MessageService messageService,
            SslContext sslContext,
            MeterRegistry meterRegistry) {
        this.properties = properties;
        this.authService = authService;
        this.mailboxService = mailboxService;
        this.messageService = messageService;
        this.sslContext = sslContext;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        InetSocketAddress remoteAddr = (InetSocketAddress) ctx.channel().remoteAddress();
        session.setRemoteIp(remoteAddr.getAddress().getHostAddress());

        // Detect if TLS is already active (implicit SSL on port 993)
        boolean tlsActive = ctx.pipeline().get(SslHandler.class) != null;
        session.setTlsActive(tlsActive);

        String caps = buildCapabilityString();
        String greeting = "* OK [CAPABILITY " + caps + "] " +
                properties.getAdvertisedHostname() + " 9Mail IMAP Server Ready";
        log.info("IMAP connection from: {} (TLS={})", session.getRemoteIp(), tlsActive);
        log.debug("IMAP >> {}", greeting);
        ctx.writeAndFlush(greeting + "\r\n");
    }

    /**
     * Build CAPABILITY string based on current session state
     */
    private String buildCapabilityString() {
        String starttls = session.isTlsActive() ? "" : "STARTTLS ";
        return "IMAP4rev1 " + starttls +
                "AUTH=PLAIN AUTH=LOGIN IDLE MOVE UNSELECT UIDPLUS " +
                "SPECIAL-USE NAMESPACE CHILDREN ID ENABLE LITERAL+";
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, String msg) {
        String line = msg;
        log.debug("IMAP << {}", line);

        // Exit IDLE mode
        if (session.isIdleMode()) {
            if (line.trim().equalsIgnoreCase("DONE")) {
                session.setIdleMode(false);
                respond(ctx, session.getIdleTag() + " OK IDLE terminated");
            }
            return;
        }

        // APPEND literal receiving mode
        if (session.isAppendMode()) {
            handleAppendData(ctx, line);
            return;
        }

        // AUTHENTICATE continuation mode (client sends base64 response without tag)
        if (session.isAuthMode()) {
            handleAuthenticateContinuation(ctx, line);
            return;
        }

        // Parse tag + command
        int firstSpace = line.indexOf(' ');
        if (firstSpace < 0) {
            ctx.writeAndFlush("* BAD Invalid command format\r\n");
            return;
        }

        String tag = line.substring(0, firstSpace);
        String rest = line.substring(firstSpace + 1).trim();

        // UID prefix handling
        boolean uidMode = false;
        if (rest.toUpperCase().startsWith("UID ")) {
            uidMode = true;
            rest = rest.substring(4).trim();
        }

        int cmdSpace = rest.indexOf(' ');
        String command = (cmdSpace > 0 ? rest.substring(0, cmdSpace) : rest).toUpperCase();
        String args = cmdSpace > 0 ? rest.substring(cmdSpace + 1).trim() : "";

        switch (command) {
            // === Global commands ===
            case "CAPABILITY" -> handleCapability(ctx, tag);
            case "NAMESPACE" -> handleNamespace(ctx, tag);
            case "NOOP" -> handleNoop(ctx, tag);
            case "LOGOUT" -> handleLogout(ctx, tag);
            case "STARTTLS" -> handleStartTls(ctx, tag);
            case "ID" -> handleId(ctx, tag, args);
            case "ENABLE" -> handleEnable(ctx, tag, args);

            // === Not authenticated commands ===
            case "LOGIN" -> handleLogin(ctx, tag, args);
            case "AUTHENTICATE" -> handleAuthenticate(ctx, tag, args);

            // === Authenticated commands ===
            case "SELECT" -> handleSelect(ctx, tag, args, false);
            case "EXAMINE" -> handleSelect(ctx, tag, args, true);
            case "CREATE" -> handleCreate(ctx, tag, args);
            case "DELETE" -> handleDelete(ctx, tag, args);
            case "RENAME" -> handleRename(ctx, tag, args);
            case "SUBSCRIBE" -> handleSubscribe(ctx, tag, args, true);
            case "UNSUBSCRIBE" -> handleSubscribe(ctx, tag, args, false);
            case "LIST" -> handleList(ctx, tag, args);
            case "LSUB" -> handleLsub(ctx, tag, args);
            case "STATUS" -> handleStatus(ctx, tag, args);
            case "APPEND" -> handleAppend(ctx, tag, args);

            // === Selected state commands ===
            case "FETCH" -> handleFetch(ctx, tag, args, uidMode);
            case "STORE" -> handleStore(ctx, tag, args, uidMode);
            case "SEARCH" -> handleSearch(ctx, tag, args, uidMode);
            case "COPY" -> handleCopy(ctx, tag, args, uidMode);
            case "MOVE" -> handleMove(ctx, tag, args, uidMode);
            case "EXPUNGE" -> handleExpunge(ctx, tag, uidMode ? args : null);
            case "CLOSE" -> handleClose(ctx, tag);
            case "UNSELECT" -> handleUnselect(ctx, tag);
            case "IDLE" -> handleIdle(ctx, tag);

            default -> respond(ctx, tag + " BAD Unknown command: " + command);
        }
    }

    // ================================================================
    // Global commands
    // ================================================================

    private void handleCapability(ChannelHandlerContext ctx, String tag) {
        String caps = buildCapabilityString();
        ctx.writeAndFlush("* CAPABILITY " + caps + "\r\n");
        respond(ctx, tag + " OK CAPABILITY completed");
    }

    /**
     * RFC 2971 - IMAP ID Extension
     * Outlook sends ID immediately after greeting to identify itself.
     */
    private void handleId(ChannelHandlerContext ctx, String tag, String args) {
        log.debug("IMAP ID from {}: {}", session.getRemoteIp(), args);
        ctx.writeAndFlush("* ID (\"name\" \"9Mail\" \"vendor\" \"NineMail\" \"version\" \"1.0\")\r\n");
        respond(ctx, tag + " OK ID completed");
    }

    /**
     * RFC 5161 - IMAP ENABLE Extension
     * Outlook sends ENABLE CONDSTORE QRESYNC after login.
     */
    private void handleEnable(ChannelHandlerContext ctx, String tag, String args) {
        if (!requireAuth(ctx, tag))
            return;
        // Accept the command but don't actually enable anything for now
        log.debug("IMAP ENABLE requested: {}", args);
        ctx.writeAndFlush("* ENABLED\r\n");
        respond(ctx, tag + " OK ENABLE completed");
    }

    private void handleNamespace(ChannelHandlerContext ctx, String tag) {
        // RFC 2342: NAMESPACE response
        // We use a single personal namespace with empty prefix and '.' delimiter.
        // Other (shared/public) namespaces are not implemented.
        ctx.writeAndFlush("* NAMESPACE ((\"\" \".\")) NIL NIL\r\n");
        respond(ctx, tag + " OK NAMESPACE completed");
    }

    private void handleNoop(ChannelHandlerContext ctx, String tag) {
        // If a mailbox is selected, send status updates
        if (session.getState() == ImapState.SELECTED && session.getSelectedMailbox() != null) {
            refreshMailboxCache(ctx);
        }
        respond(ctx, tag + " OK NOOP completed");
    }

    private void handleLogout(ChannelHandlerContext ctx, String tag) {
        session.setState(ImapState.LOGOUT);
        ctx.writeAndFlush("* BYE " + properties.getAdvertisedHostname() + " IMAP server shutting down connection\r\n");
        respond(ctx, tag + " OK LOGOUT completed");
        ctx.close();
    }

    private void handleStartTls(ChannelHandlerContext ctx, String tag) {
        if (sslContext == null) {
            respond(ctx, tag + " NO TLS not available");
            return;
        }
        if (session.isTlsActive()) {
            respond(ctx, tag + " BAD TLS already active");
            return;
        }
        ctx.writeAndFlush(tag + " OK Begin TLS negotiation now\r\n").addListener(future -> {
            if (future.isSuccess()) {
                SslHandler sslHandler = sslContext.newHandler(ctx.alloc());
                ctx.pipeline().addFirst("ssl", sslHandler);
                sslHandler.handshakeFuture().addListener(hsFuture -> {
                    if (hsFuture.isSuccess()) {
                        session.setTlsActive(true);
                        log.info("IMAP STARTTLS handshake completed for {}", session.getRemoteIp());
                    } else {
                        log.error("IMAP STARTTLS handshake failed for {}: {}",
                                session.getRemoteIp(), hsFuture.cause().getMessage());
                        ctx.close();
                    }
                });
                session.setState(ImapState.NOT_AUTHENTICATED);
            } else {
                log.error("IMAP failed to send STARTTLS response to {}", session.getRemoteIp());
                ctx.close();
            }
        });
    }

    // ================================================================
    // Not authenticated commands
    // ================================================================

    private void handleLogin(ChannelHandlerContext ctx, String tag, String args) {
        if (session.getState() != ImapState.NOT_AUTHENTICATED) {
            respond(ctx, tag + " BAD Already authenticated");
            return;
        }

        // LOGIN username password
        String[] parts = parseLoginArgs(args);
        if (parts == null || parts.length < 2) {
            respond(ctx, tag + " BAD Syntax error in LOGIN arguments");
            return;
        }

        String username = CryptoUtil.stripAngleBrackets(parts[0]);
        String password = parts[1];

        if (authService.authenticate(username, password)) {
            session.setAuthenticated(true);
            session.setAuthenticatedUser(username);
            session.setState(ImapState.AUTHENTICATED);

            // Ensure default mailboxes exist
            if (mailboxService.listMailboxes(username).isEmpty()) {
                mailboxService.createDefaultMailboxes(username);
            }

            // Include CAPABILITY in LOGIN response (RFC 3501 §6.2.3)
            String caps = buildCapabilityString();
            respond(ctx, tag + " OK [CAPABILITY " + caps + "] LOGIN completed");
            log.info("IMAP login success: {}", username);
        } else {
            respond(ctx, tag + " NO [AUTHENTICATIONFAILED] Invalid credentials");
        }
    }

    private void handleAuthenticate(ChannelHandlerContext ctx, String tag, String args) {
        if (session.getState() != ImapState.NOT_AUTHENTICATED) {
            respond(ctx, tag + " BAD Already authenticated");
            return;
        }

        String trimmed = args == null ? "" : args.trim();
        if (trimmed.isEmpty()) {
            respond(ctx, tag + " BAD Syntax error in AUTHENTICATE arguments");
            return;
        }

        // AUTHENTICATE <mechanism> [initial-response]
        String[] parts = trimmed.split("\\s+", 2);
        String mechanism = parts[0].toUpperCase(Locale.ROOT);
        String initialResponse = parts.length > 1 ? parts[1].trim() : null;

        switch (mechanism) {
            case "PLAIN" -> {
                if (initialResponse != null && !initialResponse.isEmpty()) {
                    finishAuthenticatePlain(ctx, tag, initialResponse);
                } else {
                    session.setAuthMode(true);
                    session.setAuthTag(tag);
                    session.setAuthMechanism("PLAIN");
                    ctx.writeAndFlush("+ \r\n");
                }
            }
            case "LOGIN" -> {
                session.setAuthMode(true);
                session.setAuthTag(tag);
                session.setAuthMechanism("LOGIN");
                session.setAuthLoginUsername(null);
                session.setAuthLoginStep(1);
                // "Username:" base64
                ctx.writeAndFlush("+ VXNlcm5hbWU6\r\n");
            }
            default -> respond(ctx, tag + " NO Unsupported authentication mechanism");
        }
    }

    private void handleAuthenticateContinuation(ChannelHandlerContext ctx, String line) {
        String tag = session.getAuthTag();
        String mechanism = session.getAuthMechanism();

        if (tag == null || mechanism == null) {
            session.setAuthMode(false);
            respond(ctx, "* BAD AUTHENTICATE state error");
            return;
        }

        String trimmed = line == null ? "" : line.trim();

        // Client can cancel with '*'
        if ("*".equals(trimmed)) {
            session.setAuthMode(false);
            session.setAuthTag(null);
            session.setAuthMechanism(null);
            session.setAuthLoginUsername(null);
            session.setAuthLoginStep(0);
            respond(ctx, tag + " NO AUTHENTICATE cancelled");
            return;
        }

        if ("PLAIN".equalsIgnoreCase(mechanism)) {
            session.setAuthMode(false);
            session.setAuthTag(null);
            session.setAuthMechanism(null);
            finishAuthenticatePlain(ctx, tag, trimmed);
            return;
        }

        if ("LOGIN".equalsIgnoreCase(mechanism)) {
            int step = session.getAuthLoginStep();
            if (step == 1) {
                session.setAuthLoginUsername(authService.decodeLoginUsername(trimmed));
                session.setAuthLoginStep(2);
                // "Password:" base64
                ctx.writeAndFlush("+ UGFzc3dvcmQ6\r\n");
                return;
            }
            if (step == 2) {
                String username = session.getAuthLoginUsername();
                String password = authService.decodeLoginPassword(trimmed);

                session.setAuthMode(false);
                session.setAuthTag(null);
                session.setAuthMechanism(null);
                session.setAuthLoginUsername(null);
                session.setAuthLoginStep(0);

                if (username != null && authService.authenticate(username, password)) {
                    session.setAuthenticated(true);
                    session.setAuthenticatedUser(CryptoUtil.stripAngleBrackets(username));
                    session.setState(ImapState.AUTHENTICATED);

                    if (mailboxService.listMailboxes(session.getAuthenticatedUser()).isEmpty()) {
                        mailboxService.createDefaultMailboxes(session.getAuthenticatedUser());
                    }

                    respond(ctx, tag + " OK AUTHENTICATE completed");
                    log.info("IMAP authenticate (LOGIN) success: {}", session.getAuthenticatedUser());
                } else {
                    respond(ctx, tag + " NO [AUTHENTICATIONFAILED] Invalid credentials");
                }
                return;
            }

            // Unknown step
            session.setAuthMode(false);
            session.setAuthTag(null);
            session.setAuthMechanism(null);
            session.setAuthLoginUsername(null);
            session.setAuthLoginStep(0);
            respond(ctx, tag + " BAD AUTHENTICATE state error");
            return;
        }

        // Fallback
        session.setAuthMode(false);
        session.setAuthTag(null);
        session.setAuthMechanism(null);
        respond(ctx, tag + " NO Unsupported authentication mechanism");
    }

    private void finishAuthenticatePlain(ChannelHandlerContext ctx, String tag, String base64Credentials) {
        if (authService.authenticatePlain(base64Credentials)) {
            String[] creds = CryptoUtil.decodeAuthPlain(base64Credentials);
            String username = creds != null && creds.length > 0 ? creds[0] : null;

            session.setAuthenticated(true);
            session.setAuthenticatedUser(username != null ? CryptoUtil.stripAngleBrackets(username) : null);
            session.setState(ImapState.AUTHENTICATED);

            if (session.getAuthenticatedUser() != null
                    && mailboxService.listMailboxes(session.getAuthenticatedUser()).isEmpty()) {
                mailboxService.createDefaultMailboxes(session.getAuthenticatedUser());
            }

            respond(ctx, tag + " OK AUTHENTICATE completed");
            log.info("IMAP authenticate (PLAIN) success: {}", session.getAuthenticatedUser());
        } else {
            respond(ctx, tag + " NO [AUTHENTICATIONFAILED] Invalid credentials");
        }
    }

    // ================================================================
    // Authenticated commands
    // ================================================================

    private void handleSelect(ChannelHandlerContext ctx, String tag, String args, boolean readOnly) {
        if (!requireAuth(ctx, tag))
            return;

        String mailboxName = unquote(args.trim());
        // RFC: INBOX is case-insensitive special name
        if (mailboxName != null && mailboxName.equalsIgnoreCase("INBOX")) {
            mailboxName = "INBOX";
        }
        MailMailbox mailbox = mailboxService.getMailbox(session.getAuthenticatedUser(), mailboxName);

        if (mailbox == null) {
            respond(ctx, tag + " NO [NONEXISTENT] Mailbox does not exist: " + mailboxName);
            return;
        }

        session.selectMailbox(mailbox, readOnly);
        // 직접 메일 캐시 로드 (refreshMailboxCache를 쓰면 EXISTS가 중복 전송됨)
        List<MailMail> mails = messageService.getMailsByMailbox(mailbox.getMailboxId());
        session.setMailCache(mails);

        int exists = mails.size();
        int recent = 0; // Simplified
        int unseen = messageService.getUnreadCount(mailbox.getMailboxId());

        // System flags
        ctx.writeAndFlush("* FLAGS (\\Answered \\Flagged \\Deleted \\Seen \\Draft)\r\n");
        ctx.writeAndFlush(
                "* OK [PERMANENTFLAGS (\\Answered \\Flagged \\Deleted \\Seen \\Draft \\*)] Permanent flags\r\n");
        ctx.writeAndFlush("* " + exists + " EXISTS\r\n");
        ctx.writeAndFlush("* " + recent + " RECENT\r\n");
        if (unseen > 0) {
            // Sequence number of the first unseen message
            for (int i = 0; i < mails.size(); i++) {
                if (mails.get(i).getIsRead() == 0) {
                    ctx.writeAndFlush("* OK [UNSEEN " + (i + 1) + "] First unseen\r\n");
                    break;
                }
            }
        }
        ctx.writeAndFlush("* OK [UIDVALIDITY " + mailbox.getUidValidity() + "] UIDs valid\r\n");
        ctx.writeAndFlush("* OK [UIDNEXT " + mailbox.getNextUid() + "] Predicted next UID\r\n");

        respond(ctx, tag + " OK [" + (readOnly ? "READ-ONLY" : "READ-WRITE") + "] " +
                (readOnly ? "EXAMINE" : "SELECT") + " completed");
    }

    private void handleCreate(ChannelHandlerContext ctx, String tag, String args) {
        if (!requireAuth(ctx, tag))
            return;
        String mailboxName = unquote(args.trim());
        String path = mailboxName.replace('/', '.');
        mailboxService.createMailbox(session.getAuthenticatedUser(), mailboxName, path);
        respond(ctx, tag + " OK CREATE completed");
    }

    private void handleDelete(ChannelHandlerContext ctx, String tag, String args) {
        if (!requireAuth(ctx, tag))
            return;
        String mailboxName = unquote(args.trim());
        if (mailboxService.deleteMailbox(session.getAuthenticatedUser(), mailboxName)) {
            respond(ctx, tag + " OK DELETE completed");
        } else {
            respond(ctx, tag + " NO Cannot delete mailbox");
        }
    }

    private void handleRename(ChannelHandlerContext ctx, String tag, String args) {
        if (!requireAuth(ctx, tag))
            return;
        String[] parts = splitQuotedArgs(args, 2);
        if (parts.length < 2) {
            respond(ctx, tag + " BAD Syntax error");
            return;
        }
        if (mailboxService.renameMailbox(session.getAuthenticatedUser(), unquote(parts[0]), unquote(parts[1]))) {
            respond(ctx, tag + " OK RENAME completed");
        } else {
            respond(ctx, tag + " NO Cannot rename mailbox");
        }
    }

    private void handleSubscribe(ChannelHandlerContext ctx, String tag, String args, boolean subscribe) {
        if (!requireAuth(ctx, tag))
            return;
        // Subscriptions are not persisted; respond OK (simplified)
        respond(ctx, tag + " OK " + (subscribe ? "SUBSCRIBE" : "UNSUBSCRIBE") + " completed");
    }

    private void handleList(ChannelHandlerContext ctx, String tag, String args) {
        if (!requireAuth(ctx, tag))
            return;

        // Handle extended LIST syntax: LIST (SPECIAL-USE) "" "*" or LIST "" "*" RETURN
        // (SPECIAL-USE)
        String cleanArgs = args;
        boolean specialUseOnly = false;

        // RFC 6154: LIST (SPECIAL-USE) "" "*"
        if (cleanArgs.trim().startsWith("(")) {
            int closeParen = cleanArgs.indexOf(')');
            if (closeParen > 0) {
                String selectionOpts = cleanArgs.substring(1, closeParen).trim().toUpperCase();
                specialUseOnly = selectionOpts.contains("SPECIAL-USE");
                cleanArgs = cleanArgs.substring(closeParen + 1).trim();
            }
        }

        // Check for RETURN (SPECIAL-USE) at the end
        String upperArgs = cleanArgs.toUpperCase();
        int returnIdx = upperArgs.lastIndexOf("RETURN");
        if (returnIdx > 0) {
            String returnPart = cleanArgs.substring(returnIdx).toUpperCase();
            if (returnPart.contains("SPECIAL-USE")) {
                specialUseOnly = true;
            }
            cleanArgs = cleanArgs.substring(0, returnIdx).trim();
        }

        String[] parts = splitQuotedArgs(cleanArgs, 2);
        String reference = parts.length > 0 ? unquote(parts[0]) : "";
        String pattern = parts.length > 1 ? unquote(parts[1]) : "*";

        // Delimiter query (empty pattern)
        if (pattern.isEmpty()) {
            ctx.writeAndFlush("* LIST (\\Noselect) \".\" \"\"\r\n");
            respond(ctx, tag + " OK LIST completed");
            return;
        }

        List<MailMailbox> mailboxes = mailboxService.listMailboxes(
                session.getAuthenticatedUser(), reference, pattern);

        for (MailMailbox mb : mailboxes) {
            String specialUse = getSpecialUseAttr(mb.getMailboxPath());

            // If SPECIAL-USE only mode, skip mailboxes without special-use attribute
            if (specialUseOnly && specialUse.isEmpty())
                continue;

            String attrs = "\\HasNoChildren";
            if (!specialUse.isEmpty()) {
                attrs += " " + specialUse;
            }

            ctx.writeAndFlush("* LIST (" + attrs + ") \".\" \"" + mb.getMailboxPath() + "\"\r\n");
        }
        respond(ctx, tag + " OK LIST completed");
    }

    /**
     * Get SPECIAL-USE attribute for a mailbox path
     */
    private String getSpecialUseAttr(String mailboxPath) {
        if (mailboxPath == null)
            return "";
        return switch (mailboxPath.toUpperCase()) {
            case "SENT" -> "\\Sent";
            case "DRAFTS" -> "\\Drafts";
            case "TRASH" -> "\\Trash";
            case "JUNK" -> "\\Junk";
            default -> "";
        };
    }

    private void handleLsub(ChannelHandlerContext ctx, String tag, String args) {
        // LSUB is handled the same as LIST (subscription filtering not implemented)
        handleList(ctx, tag, args);
    }

    private void handleStatus(ChannelHandlerContext ctx, String tag, String args) {
        if (!requireAuth(ctx, tag))
            return;

        // STATUS "mailbox" (MESSAGES UNSEEN UIDNEXT UIDVALIDITY RECENT)
        int parenStart = args.indexOf('(');
        if (parenStart < 0) {
            respond(ctx, tag + " BAD Syntax error in STATUS arguments");
            return;
        }

        String mailboxName = unquote(args.substring(0, parenStart).trim());
        String statusItems = args.substring(parenStart + 1, args.lastIndexOf(')')).trim().toUpperCase();

        MailMailbox mailbox = mailboxService.getMailbox(session.getAuthenticatedUser(), mailboxName);
        if (mailbox == null) {
            respond(ctx, tag + " NO [NONEXISTENT] Mailbox does not exist");
            return;
        }

        StringBuilder response = new StringBuilder("* STATUS \"" + mailboxName + "\" (");
        List<String> items = new ArrayList<>();

        if (statusItems.contains("MESSAGES")) {
            items.add("MESSAGES " + messageService.getMailCount(mailbox.getMailboxId()));
        }
        if (statusItems.contains("UNSEEN")) {
            items.add("UNSEEN " + messageService.getUnreadCount(mailbox.getMailboxId()));
        }
        if (statusItems.contains("UIDNEXT")) {
            items.add("UIDNEXT " + mailbox.getNextUid());
        }
        if (statusItems.contains("UIDVALIDITY")) {
            items.add("UIDVALIDITY " + mailbox.getUidValidity());
        }
        if (statusItems.contains("RECENT")) {
            items.add("RECENT 0");
        }

        response.append(String.join(" ", items)).append(")");
        ctx.writeAndFlush(response + "\r\n");
        respond(ctx, tag + " OK STATUS completed");
    }

    private void handleAppend(ChannelHandlerContext ctx, String tag, String args) {
        if (!requireAuth(ctx, tag))
            return;

        // APPEND "mailbox" (\Flags) "date" {size}
        // Simplified: literal size parsing
        int braceStart = args.lastIndexOf('{');
        int braceEnd = args.lastIndexOf('}');
        if (braceStart < 0 || braceEnd < 0) {
            respond(ctx, tag + " BAD Missing literal size");
            return;
        }

        String literalSpec = args.substring(braceStart + 1, braceEnd);
        boolean nonSync = literalSpec.endsWith("+"); // LITERAL+ (RFC 7888)
        String sizeStr = literalSpec.replace("+", "");
        int size;
        try {
            size = Integer.parseInt(sizeStr);
        } catch (NumberFormatException e) {
            respond(ctx, tag + " BAD Invalid literal size");
            return;
        }

        String beforeBrace = args.substring(0, braceStart).trim();
        // Extract mailbox name
        int firstQuote = beforeBrace.indexOf('"');
        int secondQuote = beforeBrace.indexOf('"', firstQuote + 1);
        String mailboxName = firstQuote >= 0 && secondQuote > firstQuote
                ? beforeBrace.substring(firstQuote + 1, secondQuote)
                : beforeBrace.split("\\s+")[0];

        // RFC: INBOX is case-insensitive special name
        if (mailboxName != null && mailboxName.equalsIgnoreCase("INBOX")) {
            mailboxName = "INBOX";
        }

        // Extract optional flags: (\Seen \Flagged ...)
        String flags = null;
        int flagsStart = beforeBrace.indexOf('(');
        int flagsEnd = beforeBrace.indexOf(')', flagsStart + 1);
        if (flagsStart >= 0 && flagsEnd > flagsStart) {
            flags = beforeBrace.substring(flagsStart + 1, flagsEnd).trim();
        }

        session.setAppendMode(true);
        session.setAppendMailbox(mailboxName);
        session.setAppendFlags(flags);
        session.setAppendSize(size);
        session.setAppendTag(tag);
        session.setAppendBuffer(new StringBuilder());
        session.setAppendBytesReceived(0);

        // LITERAL+: client already started sending data, no continuation needed
        if (!nonSync) {
            ctx.writeAndFlush("+ Ready for literal data\r\n");
        }
    }

    private void handleAppendData(ChannelHandlerContext ctx, String line) {
        // Restore the CRLF that DelimiterBasedFrameDecoder stripped
        byte[] lineBytes = (line + "\r\n").getBytes(StandardCharsets.UTF_8);
        session.getAppendBuffer().append(line).append("\r\n");
        session.setAppendBytesReceived(session.getAppendBytesReceived() + lineBytes.length);

        log.debug("APPEND data: received={} / declared={} bytes",
                session.getAppendBytesReceived(), session.getAppendSize());

        if (session.getAppendBytesReceived() >= session.getAppendSize()) {
            session.setAppendMode(false);
            String tag = session.getAppendTag();

            try {
                // Convert to UTF-8 bytes and trim exactly to declared size
                byte[] fullBytes = session.getAppendBuffer().toString().getBytes(StandardCharsets.UTF_8);
                byte[] emlData = Arrays.copyOf(fullBytes, Math.min(session.getAppendSize(), fullBytes.length));
                String email = session.getAuthenticatedUser();
                String mailboxName = session.getAppendMailbox();

                log.debug("APPEND complete: mailbox={}, user={}, size={}",
                        mailboxName, email, emlData.length);

                // Store into the requested mailbox (Sent/Drafts/etc). Do NOT re-deliver to
                // INBOX.
                String flags = session.getAppendFlags();
                MessageService.AppendResult appendResult = messageService.appendToMailbox(email, mailboxName, emlData,
                        flags);
                respond(ctx, tag + " OK [APPENDUID " + appendResult.uidValidity() + " " + appendResult.uid()
                        + "] APPEND completed");
            } catch (Exception e) {
                log.error("APPEND failed: mailbox={}, user={}", session.getAppendMailbox(),
                        session.getAuthenticatedUser(), e);
                respond(ctx, tag + " NO APPEND failed: " + e.getMessage());
            }
        }
    }

    // ================================================================
    // Selected state commands
    // ================================================================

    private void handleFetch(ChannelHandlerContext ctx, String tag, String args, boolean uidMode) {
        if (!requireSelected(ctx, tag))
            return;

        // FETCH <sequence set> <data items>
        int firstSpace = args.indexOf(' ');
        if (firstSpace < 0) {
            respond(ctx, tag + " BAD Missing fetch data items");
            return;
        }

        String sequenceSet = args.substring(0, firstSpace);
        String dataItemsRaw = args.substring(firstSpace + 1).trim();
        String dataItems = dataItemsRaw.toUpperCase(Locale.ROOT);

        List<MailMail> targetMails = resolveSequenceSet(sequenceSet, uidMode);

        for (MailMail mail : targetMails) {
            int seqNum = session.getSequenceByUid(mail.getUid());
            if (seqNum < 0)
                continue;

            StringBuilder fetchResponse = new StringBuilder("* " + seqNum + " FETCH (");
            List<String> items = new ArrayList<>();

            if (uidMode || dataItems.contains("UID")) {
                items.add("UID " + mail.getUid());
            }

            if (dataItems.contains("FLAGS")) {
                items.add("FLAGS (" + buildFlags(mail) + ")");
            }

            if (dataItems.contains("INTERNALDATE")) {
                items.add("INTERNALDATE \"" + formatInternalDate(mail) + "\"");
            }

            if (dataItems.contains("RFC822.SIZE") || dataItems.contains("SIZE")) {
                items.add("RFC822.SIZE " + mail.getSize());
            }

            if (dataItems.contains("ENVELOPE") || dataItems.contains("ALL") || dataItems.contains("FULL")) {
                String envelope = buildEnvelope(mail);
                items.add("ENVELOPE " + envelope);
            }

            if (dataItems.contains("BODYSTRUCTURE")) {
                String bodyStructure = buildBodyStructure(mail);
                items.add("BODYSTRUCTURE " + bodyStructure);
            }

            if (dataItems.contains("BODY[]") || dataItems.contains("RFC822") || dataItems.contains("BODY.PEEK[]")) {
                byte[] emlData = messageService.getEmlDataByMessageId(mail.getMessageId());
                if (emlData != null) {
                    // Use UTF-8 consistently so that {size} matches actual transmitted bytes
                    String emlStr = new String(emlData, StandardCharsets.UTF_8);
                    byte[] emlUtf8 = emlStr.getBytes(StandardCharsets.UTF_8);
                    items.add("BODY[] {" + emlUtf8.length + "}\r\n" + emlStr);

                    // When fetching BODY[] (not peek), set the \Seen flag
                    if (dataItems.contains("BODY[]") && !dataItems.contains("PEEK")) {
                        messageService.markRead(mail.getId(), true);
                        mail.setIsRead(1);
                    }
                }
            }

            // BODY[TEXT] / BODY.PEEK[TEXT] - message body without headers
            if (dataItems.contains("BODY[TEXT]") || dataItems.contains("BODY.PEEK[TEXT]")) {
                byte[] emlData = messageService.getEmlDataByMessageId(mail.getMessageId());
                if (emlData != null) {
                    String emlStr = new String(emlData, StandardCharsets.UTF_8);
                    int headerEnd = emlStr.indexOf("\r\n\r\n");
                    if (headerEnd < 0)
                        headerEnd = emlStr.indexOf("\n\n");
                    String bodyText = headerEnd > 0 ? emlStr.substring(headerEnd + 4) : "";
                    byte[] bodyBytes = bodyText.getBytes(StandardCharsets.UTF_8);
                    items.add("BODY[TEXT] {" + bodyBytes.length + "}\r\n" + bodyText);

                    if (dataItems.contains("BODY[TEXT]") && !dataItems.contains("PEEK")) {
                        messageService.markRead(mail.getId(), true);
                        mail.setIsRead(1);
                    }
                }
            }

            // BODY[n] / BODY.PEEK[n] - MIME part fetch (Outlook uses this)
            handleMimePartFetch(dataItemsRaw, mail, items);

            // Thunderbird commonly requests: BODY.PEEK[HEADER.FIELDS (...)]
            if (dataItems.contains("HEADER.FIELDS")) {
                byte[] emlData = messageService.getEmlDataByMessageId(mail.getMessageId());
                if (emlData != null) {
                    String emlStr = new String(emlData, StandardCharsets.UTF_8);
                    String headers = extractHeaderSection(emlStr);
                    List<String> requested = extractRequestedHeaderFields(dataItemsRaw);
                    String filtered = requested.isEmpty() ? headers : filterHeaders(headers, requested);
                    // Server response uses BODY[...] even if request was BODY.PEEK[...]
                    String sectionName = requested.isEmpty()
                            ? "BODY[HEADER]"
                            : "BODY[HEADER.FIELDS (" + String.join(" ", requested) + ")]";
                    byte[] filteredBytes = filtered.getBytes(StandardCharsets.UTF_8);
                    items.add(sectionName + " {" + filteredBytes.length + "}\r\n" + filtered);
                }
            }

            if (dataItems.contains("BODY[HEADER]") || dataItems.contains("BODY.PEEK[HEADER]")) {
                byte[] emlData = messageService.getEmlDataByMessageId(mail.getMessageId());
                if (emlData != null) {
                    String emlStr = new String(emlData, StandardCharsets.UTF_8);
                    int headerEnd = emlStr.indexOf("\r\n\r\n");
                    if (headerEnd < 0)
                        headerEnd = emlStr.indexOf("\n\n");
                    String headers = headerEnd > 0 ? emlStr.substring(0, headerEnd + 2) : emlStr;
                    byte[] headersBytes = headers.getBytes(StandardCharsets.UTF_8);
                    items.add("BODY[HEADER] {" + headersBytes.length + "}\r\n" + headers);
                }
            }

            fetchResponse.append(String.join(" ", items));
            fetchResponse.append(")");
            String fetchLine = fetchResponse + "\r\n";
            log.debug("IMAP >> * {} FETCH (...) uid={}", seqNum, mail.getUid());
            ctx.writeAndFlush(fetchLine);
        }

        respond(ctx, tag + " OK FETCH completed");
    }

    private void handleStore(ChannelHandlerContext ctx, String tag, String args, boolean uidMode) {
        if (!requireSelected(ctx, tag))
            return;
        if (session.isReadOnly()) {
            respond(ctx, tag + " NO Mailbox is read-only");
            return;
        }

        // STORE <sequence set> +FLAGS (\Seen)
        String[] parts = args.split("\\s+", 3);
        if (parts.length < 3) {
            respond(ctx, tag + " BAD Syntax error in STORE arguments");
            return;
        }

        String sequenceSet = parts[0];
        String action = parts[1].toUpperCase();
        String flagStr = parts[2].replaceAll("[()]", "").trim().toUpperCase();
        boolean silent = action.contains(".SILENT");

        List<MailMail> targetMails = resolveSequenceSet(sequenceSet, uidMode);

        for (MailMail mail : targetMails) {
            int isRead = mail.getIsRead();
            int isFlagged = mail.getIsFlagged();
            int isAnswered = mail.getIsAnswered();
            int isDeleted = mail.getIsDeleted();
            int isDraft = mail.getIsDraft();

            if (action.startsWith("+FLAGS")) {
                if (flagStr.contains("\\SEEN"))
                    isRead = 1;
                if (flagStr.contains("\\FLAGGED"))
                    isFlagged = 1;
                if (flagStr.contains("\\ANSWERED"))
                    isAnswered = 1;
                if (flagStr.contains("\\DELETED"))
                    isDeleted = 1;
                if (flagStr.contains("\\DRAFT"))
                    isDraft = 1;
            } else if (action.startsWith("-FLAGS")) {
                if (flagStr.contains("\\SEEN"))
                    isRead = 0;
                if (flagStr.contains("\\FLAGGED"))
                    isFlagged = 0;
                if (flagStr.contains("\\ANSWERED"))
                    isAnswered = 0;
                if (flagStr.contains("\\DELETED"))
                    isDeleted = 0;
                if (flagStr.contains("\\DRAFT"))
                    isDraft = 0;
            } else if (action.startsWith("FLAGS")) {
                isRead = flagStr.contains("\\SEEN") ? 1 : 0;
                isFlagged = flagStr.contains("\\FLAGGED") ? 1 : 0;
                isAnswered = flagStr.contains("\\ANSWERED") ? 1 : 0;
                isDeleted = flagStr.contains("\\DELETED") ? 1 : 0;
                isDraft = flagStr.contains("\\DRAFT") ? 1 : 0;
            }

            messageService.updateFlags(mail.getId(), isRead, isFlagged, isAnswered, isDeleted, isDraft);
            mail.setIsRead(isRead);
            mail.setIsFlagged(isFlagged);
            mail.setIsAnswered(isAnswered);
            mail.setIsDeleted(isDeleted);
            mail.setIsDraft(isDraft);

            if (!silent) {
                int seqNum = session.getSequenceByUid(mail.getUid());
                ctx.writeAndFlush("* " + seqNum + " FETCH (FLAGS (" + buildFlags(mail) + "))\r\n");
            }
        }

        respond(ctx, tag + " OK STORE completed");
    }

    private void handleSearch(ChannelHandlerContext ctx, String tag, String args, boolean uidMode) {
        if (!requireSelected(ctx, tag))
            return;

        List<MailMail> results;

        // Start with all mails in the selected mailbox
        results = new ArrayList<>(session.getMailCache());

        // Parse and strip leading sequence/UID set (e.g., "1:3" from "1:3 NOT DELETED")
        String searchCriteria = args.trim();
        String sequenceRange = null;
        if (!searchCriteria.isEmpty()) {
            // Check if the first token is a sequence set (digits, *, :, , )
            String firstToken = searchCriteria.split("\\s+", 2)[0];
            if (firstToken.matches("[0-9*:,]+")) {
                sequenceRange = firstToken;
                searchCriteria = searchCriteria.substring(firstToken.length()).trim();
            }
        }
        String upperCriteria = searchCriteria.toUpperCase();

        // Apply sequence/UID range filter
        if (sequenceRange != null) {
            List<MailMail> rangeFiltered = resolveSequenceSet(sequenceRange, uidMode);
            Set<Integer> allowedUids = rangeFiltered.stream()
                    .map(MailMail::getUid).collect(Collectors.toSet());
            results = results.stream()
                    .filter(m -> allowedUids.contains(m.getUid()))
                    .collect(Collectors.toList());
        }

        // Apply search criteria filters
        // Handle NOT prefix: "NOT DELETED" means NOT deleted, "NOT SEEN" means unseen
        if (upperCriteria.contains("NOT DELETED")) {
            results = results.stream().filter(m -> m.getIsDeleted() == 0).collect(Collectors.toList());
        } else if (upperCriteria.contains("DELETED")) {
            results = results.stream().filter(m -> m.getIsDeleted() == 1).collect(Collectors.toList());
        }

        if (upperCriteria.contains("NOT SEEN") || upperCriteria.contains("UNSEEN")) {
            results = results.stream().filter(m -> m.getIsRead() == 0).collect(Collectors.toList());
        } else if (upperCriteria.contains("SEEN")) {
            results = results.stream().filter(m -> m.getIsRead() == 1).collect(Collectors.toList());
        }

        if (upperCriteria.contains("NOT FLAGGED")) {
            results = results.stream().filter(m -> m.getIsFlagged() == 0).collect(Collectors.toList());
        } else if (upperCriteria.contains("FLAGGED")) {
            results = results.stream().filter(m -> m.getIsFlagged() == 1).collect(Collectors.toList());
        }

        if (upperCriteria.contains("NOT ANSWERED")) {
            results = results.stream().filter(m -> m.getIsAnswered() == 0).collect(Collectors.toList());
        } else if (upperCriteria.contains("ANSWERED")) {
            results = results.stream().filter(m -> m.getIsAnswered() == 1).collect(Collectors.toList());
        }

        if (upperCriteria.contains("NOT DRAFT")) {
            results = results.stream().filter(m -> m.getIsDraft() == 0).collect(Collectors.toList());
        } else if (upperCriteria.contains("DRAFT")) {
            results = results.stream().filter(m -> m.getIsDraft() == 1).collect(Collectors.toList());
        }

        if (upperCriteria.contains("SUBJECT")) {
            String keyword = extractSearchKeyword(searchCriteria, "SUBJECT");
            if (keyword != null && !keyword.isBlank()) {
                results = messageService.searchBySubject(session.getSelectedMailbox().getMailboxId(), keyword);
            }
        }

        if (upperCriteria.contains("FROM")) {
            String keyword = extractSearchKeyword(searchCriteria, "FROM");
            if (keyword != null && !keyword.isBlank()) {
                results = messageService.searchByFrom(session.getSelectedMailbox().getMailboxId(), keyword);
            }
        }

        // ALL keyword means no additional filter (already have all)
        // Empty criteria also means return all

        StringBuilder searchResult = new StringBuilder("* SEARCH");
        for (MailMail mail : results) {
            if (uidMode) {
                searchResult.append(" ").append(mail.getUid());
            } else {
                int seq = session.getSequenceByUid(mail.getUid());
                if (seq > 0)
                    searchResult.append(" ").append(seq);
            }
        }
        ctx.writeAndFlush(searchResult + "\r\n");
        respond(ctx, tag + " OK SEARCH completed");
    }

    private void handleCopy(ChannelHandlerContext ctx, String tag, String args, boolean uidMode) {
        if (!requireSelected(ctx, tag))
            return;

        String[] parts = args.split("\\s+", 2);
        if (parts.length < 2) {
            respond(ctx, tag + " BAD Syntax error");
            return;
        }

        String sequenceSet = parts[0];
        String targetMailbox = unquote(parts[1]);

        MailMailbox target = mailboxService.getMailbox(session.getAuthenticatedUser(), targetMailbox);
        if (target == null) {
            respond(ctx, tag + " NO [TRYCREATE] Target mailbox does not exist");
            return;
        }

        List<MailMail> mails = resolveSequenceSet(sequenceSet, uidMode);
        for (MailMail mail : mails) {
            messageService.copyMail(
                    session.getSelectedMailbox().getMailboxId(),
                    mail.getUid(),
                    target.getMailboxId());
        }

        respond(ctx, tag + " OK COPY completed");
    }

    private void handleMove(ChannelHandlerContext ctx, String tag, String args, boolean uidMode) {
        if (!requireSelected(ctx, tag))
            return;

        String[] parts = args.split("\\s+", 2);
        if (parts.length < 2) {
            respond(ctx, tag + " BAD Syntax error");
            return;
        }

        String sequenceSet = parts[0];
        String targetMailbox = unquote(parts[1]);

        MailMailbox target = mailboxService.getMailbox(session.getAuthenticatedUser(), targetMailbox);
        if (target == null) {
            respond(ctx, tag + " NO [TRYCREATE] Target mailbox does not exist");
            return;
        }

        List<MailMail> mails = resolveSequenceSet(sequenceSet, uidMode);
        List<Integer> movedUids = new ArrayList<>();
        for (MailMail mail : mails) {
            movedUids.add(mail.getUid());
            messageService.moveMail(
                    session.getSelectedMailbox().getMailboxId(),
                    mail.getUid(),
                    target.getMailboxId());
        }

        // MOVE semantics: source messages are removed from selected mailbox
        // immediately.
        List<MailMail> oldCache = new ArrayList<>(session.getMailCache());
        List<Integer> expunged = messageService.expungeByUids(session.getSelectedMailbox().getMailboxId(), movedUids);

        int offset = 0;
        for (int uid : expunged) {
            for (int i = 0; i < oldCache.size(); i++) {
                if (oldCache.get(i).getUid() == uid) {
                    ctx.writeAndFlush("* " + (i + 1 - offset) + " EXPUNGE\r\n");
                    offset++;
                    break;
                }
            }
        }

        refreshMailboxCache(ctx);
        respond(ctx, tag + " OK MOVE completed");
    }

    private void handleExpunge(ChannelHandlerContext ctx, String tag, String uidSet) {
        if (!requireSelected(ctx, tag))
            return;

        List<Integer> expunged;
        if (uidSet != null && !uidSet.isBlank()) {
            List<Integer> targetUids = resolveSequenceSet(uidSet, true).stream()
                    .map(MailMail::getUid)
                    .distinct()
                    .collect(Collectors.toList());
            expunged = messageService.expungeByUids(session.getSelectedMailbox().getMailboxId(), targetUids);
        } else {
            expunged = messageService.expunge(session.getSelectedMailbox().getMailboxId());
        }

        // EXPUNGE response by sequence number (reverse order not required)
        List<MailMail> oldCache = new ArrayList<>(session.getMailCache());
        int offset = 0;
        for (int uid : expunged) {
            for (int i = 0; i < oldCache.size(); i++) {
                if (oldCache.get(i).getUid() == uid) {
                    ctx.writeAndFlush("* " + (i + 1 - offset) + " EXPUNGE\r\n");
                    offset++;
                    break;
                }
            }
        }

        refreshMailboxCache(ctx);
        respond(ctx, tag + " OK EXPUNGE completed");
    }

    private void handleClose(ChannelHandlerContext ctx, String tag) {
        if (!requireSelected(ctx, tag))
            return;

        // CLOSE: expunge then close mailbox (do not send EXPUNGE responses)
        messageService.expunge(session.getSelectedMailbox().getMailboxId());
        session.closeMailbox();
        respond(ctx, tag + " OK CLOSE completed");
    }

    private void handleUnselect(ChannelHandlerContext ctx, String tag) {
        if (!requireSelected(ctx, tag))
            return;
        // UNSELECT: close without expunge
        session.closeMailbox();
        respond(ctx, tag + " OK UNSELECT completed");
    }

    private void handleIdle(ChannelHandlerContext ctx, String tag) {
        if (!requireSelected(ctx, tag))
            return;
        session.setIdleMode(true);
        session.setIdleTag(tag); // Store tag separately for DONE response
        ctx.writeAndFlush("+ idling\r\n");
    }

    // ================================================================
    // Helper methods
    // ================================================================

    private boolean requireAuth(ChannelHandlerContext ctx, String tag) {
        if (!session.isAuthenticated()) {
            respond(ctx, tag + " NO Not authenticated");
            return false;
        }
        return true;
    }

    private boolean requireSelected(ChannelHandlerContext ctx, String tag) {
        if (!requireAuth(ctx, tag))
            return false;
        if (session.getState() != ImapState.SELECTED || session.getSelectedMailbox() == null) {
            respond(ctx, tag + " NO No mailbox selected");
            return false;
        }
        return true;
    }

    private void refreshMailboxCache(ChannelHandlerContext ctx) {
        if (session.getSelectedMailbox() == null)
            return;
        List<MailMail> mails = messageService.getMailsByMailbox(session.getSelectedMailbox().getMailboxId());
        int oldCount = session.getMailCache().size();
        session.setMailCache(mails);

        if (mails.size() != oldCount) {
            ctx.writeAndFlush("* " + mails.size() + " EXISTS\r\n");
        }
    }

    private List<MailMail> resolveSequenceSet(String sequenceSet, boolean uidMode) {
        List<MailMail> result = new ArrayList<>();
        List<MailMail> cache = session.getMailCache();

        for (String part : sequenceSet.split(",")) {
            part = part.trim();
            if (part.contains(":")) {
                String[] range = part.split(":");
                int start = parseNumber(range[0], cache, uidMode);
                int end = parseNumber(range[1], cache, uidMode);
                if (start > end) {
                    int tmp = start;
                    start = end;
                    end = tmp;
                }

                for (MailMail mail : cache) {
                    int val = uidMode ? mail.getUid() : session.getSequenceByUid(mail.getUid());
                    if (val >= start && val <= end) {
                        result.add(mail);
                    }
                }
            } else {
                int num = parseNumber(part, cache, uidMode);
                if (uidMode) {
                    MailMail mail = session.getMailByUid(num);
                    if (mail != null)
                        result.add(mail);
                } else {
                    MailMail mail = session.getMailBySequence(num);
                    if (mail != null)
                        result.add(mail);
                }
            }
        }
        return result;
    }

    private int parseNumber(String s, List<MailMail> cache, boolean uidMode) {
        String trimmed = s == null ? "" : s.trim();
        if ("*".equals(trimmed)) {
            if (uidMode) {
                return cache.stream().mapToInt(MailMail::getUid).max().orElse(0);
            }
            return cache.size();
        }
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private String extractHeaderSection(String eml) {
        if (eml == null)
            return "";
        int headerEnd = eml.indexOf("\r\n\r\n");
        if (headerEnd < 0)
            headerEnd = eml.indexOf("\n\n");
        if (headerEnd < 0) {
            return eml;
        }
        // Include trailing CRLF
        return eml.substring(0, Math.min(eml.length(), headerEnd + 2));
    }

    private List<String> extractRequestedHeaderFields(String dataItemsRaw) {
        if (dataItemsRaw == null)
            return List.of();
        String upper = dataItemsRaw.toUpperCase(Locale.ROOT);
        int idx = upper.indexOf("HEADER.FIELDS");
        if (idx < 0)
            return List.of();

        int open = dataItemsRaw.indexOf('(', idx);
        int close = dataItemsRaw.indexOf(')', open + 1);
        if (open < 0 || close < 0 || close <= open)
            return List.of();

        String inside = dataItemsRaw.substring(open + 1, close).trim();
        if (inside.isBlank())
            return List.of();

        String[] parts = inside.split("\\s+");
        List<String> fields = new ArrayList<>();
        for (String p : parts) {
            String f = p.replaceAll("[\\\\\"\\[\\]]", "").trim();
            if (!f.isBlank())
                fields.add(f);
        }
        return fields;
    }

    private String filterHeaders(String headers, List<String> requestedFields) {
        if (headers == null || headers.isBlank())
            return "";
        if (requestedFields == null || requestedFields.isEmpty())
            return headers;

        Set<String> wanted = requestedFields.stream()
                .filter(Objects::nonNull)
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        String[] lines = headers.split("\\r?\\n", -1);
        StringBuilder out = new StringBuilder();

        StringBuilder currentHeader = new StringBuilder();
        String currentName = null;

        for (String line : lines) {
            if (line.isEmpty()) {
                flushHeaderIfWanted(out, currentName, currentHeader, wanted);
                currentHeader.setLength(0);
                currentName = null;
                break;
            }

            boolean continuation = line.startsWith(" ") || line.startsWith("\t");
            if (continuation && currentName != null) {
                currentHeader.append(line).append("\r\n");
                continue;
            }

            flushHeaderIfWanted(out, currentName, currentHeader, wanted);
            currentHeader.setLength(0);
            currentName = null;

            int colon = line.indexOf(':');
            if (colon > 0) {
                currentName = line.substring(0, colon).trim();
                currentHeader.append(line).append("\r\n");
            }
        }

        // Ensure header section terminator
        out.append("\r\n");
        return out.toString();
    }

    private void flushHeaderIfWanted(StringBuilder out,
            String headerName,
            StringBuilder headerBlock,
            Set<String> wantedLower) {
        if (headerName == null || headerBlock == null || headerBlock.length() == 0)
            return;
        if (wantedLower.contains(headerName.toLowerCase(Locale.ROOT))) {
            out.append(headerBlock);
        }
    }

    private String buildFlags(MailMail mail) {
        List<String> flags = new ArrayList<>();
        if (mail.getIsRead() == 1)
            flags.add("\\Seen");
        if (mail.getIsFlagged() == 1)
            flags.add("\\Flagged");
        if (mail.getIsAnswered() == 1)
            flags.add("\\Answered");
        if (mail.getIsDeleted() == 1)
            flags.add("\\Deleted");
        if (mail.getIsDraft() == 1)
            flags.add("\\Draft");
        return String.join(" ", flags);
    }

    private String buildEnvelope(MailMail mail) {
        // RFC 3501 ENVELOPE: (date subject from sender reply-to to cc bcc in-reply-to
        // message-id)
        try {
            byte[] emlData = messageService.getEmlDataByMessageId(mail.getMessageId());
            if (emlData != null) {
                MimeMessage msg = EmlParser.parse(emlData);
                String date = msg.getSentDate() != null
                        ? new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).format(msg.getSentDate())
                        : "";
                String subject = msg.getSubject() != null ? escapeQuotes(msg.getSubject()) : "";
                String fromAddrs = formatAddressList(msg.getFrom());
                String senderAddrs = fromAddrs; // sender = from if not specified
                String replyTo = msg.getReplyTo() != null ? formatAddressList(msg.getReplyTo()) : fromAddrs;
                String toAddrs = formatAddressList(msg.getRecipients(jakarta.mail.Message.RecipientType.TO));
                String ccAddrs = formatAddressList(msg.getRecipients(jakarta.mail.Message.RecipientType.CC));
                String bccAddrs = formatAddressList(msg.getRecipients(jakarta.mail.Message.RecipientType.BCC));
                String inReplyTo = msg.getHeader("In-Reply-To") != null
                        ? "\"" + escapeQuotes(msg.getHeader("In-Reply-To")[0]) + "\""
                        : "NIL";
                String msgId = mail.getMessageId() != null ? "\"" + escapeQuotes(mail.getMessageId()) + "\"" : "NIL";

                return "(\"" + date + "\" \"" + subject + "\" " +
                        fromAddrs + " " + senderAddrs + " " + replyTo + " " +
                        toAddrs + " " + ccAddrs + " " + bccAddrs + " " +
                        inReplyTo + " " + msgId + ")";
            }
        } catch (Exception e) {
            log.debug("Failed to build envelope for uid {}", mail.getUid());
        }
        return "(NIL NIL NIL NIL NIL NIL NIL NIL NIL NIL)";
    }

    /**
     * Format RFC 3501 address list: ((personal NIL mailbox host) ...)
     */
    private String formatAddressList(Address[] addresses) {
        if (addresses == null || addresses.length == 0)
            return "NIL";
        StringBuilder sb = new StringBuilder("(");
        for (Address addr : addresses) {
            if (addr instanceof InternetAddress ia) {
                String personal = ia.getPersonal() != null ? "\"" + escapeQuotes(ia.getPersonal()) + "\"" : "NIL";
                String mailbox = ia.getAddress() != null && ia.getAddress().contains("@")
                        ? ia.getAddress().substring(0, ia.getAddress().indexOf('@'))
                        : (ia.getAddress() != null ? ia.getAddress() : "");
                String host = ia.getAddress() != null && ia.getAddress().contains("@")
                        ? ia.getAddress().substring(ia.getAddress().indexOf('@') + 1)
                        : "";
                sb.append("(").append(personal).append(" NIL \"")
                        .append(escapeQuotes(mailbox)).append("\" \"")
                        .append(escapeQuotes(host)).append("\")");
            } else {
                String raw = addr.toString();
                sb.append("(NIL NIL \"").append(escapeQuotes(raw)).append("\" NIL)");
            }
        }
        sb.append(")");
        return sb.toString();
    }

    private String escapeQuotes(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Format INTERNALDATE per RFC 3501: dd-Mon-yyyy HH:mm:ss +zzzz
     */
    private String formatInternalDate(MailMail mail) {
        try {
            String dt = mail.getReceiveDt();
            String tm = mail.getReceiveTime();
            if (dt != null && tm != null) {
                // Parse yyyy-MM-dd format
                SimpleDateFormat inFmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
                Date parsed = inFmt.parse(dt + " " + tm);
                SimpleDateFormat outFmt = new SimpleDateFormat("dd-MMM-yyyy HH:mm:ss Z", Locale.US);
                return outFmt.format(parsed);
            }
        } catch (Exception e) {
            log.debug("Failed to format INTERNALDATE for uid {}", mail.getUid());
        }
        // Fallback
        return new SimpleDateFormat("dd-MMM-yyyy HH:mm:ss Z", Locale.US).format(new Date());
    }

    /**
     * Build BODYSTRUCTURE by parsing the actual EML MIME structure
     */
    private String buildBodyStructure(MailMail mail) {
        try {
            byte[] emlData = messageService.getEmlDataByMessageId(mail.getMessageId());
            if (emlData != null) {
                MimeMessage msg = EmlParser.parse(emlData);
                return buildMimeStructure(msg);
            }
        } catch (Exception e) {
            log.debug("Failed to build BODYSTRUCTURE for uid {}", mail.getUid());
        }
        // Fallback: simple text/plain
        return "(\"TEXT\" \"PLAIN\" (\"CHARSET\" \"UTF-8\") NIL NIL \"7BIT\" " + mail.getSize() + " 0)";
    }

    /**
     * Recursively build MIME structure string for BODYSTRUCTURE response
     */
    private String buildMimeStructure(jakarta.mail.Part part) {
        try {
            String contentType = part.getContentType();
            if (contentType == null)
                contentType = "text/plain";
            String ct = contentType.toLowerCase();

            if (ct.startsWith("multipart/")) {
                Object content = part.getContent();
                if (content instanceof Multipart mp) {
                    StringBuilder sb = new StringBuilder("(");
                    for (int i = 0; i < mp.getCount(); i++) {
                        sb.append(buildMimeStructure(mp.getBodyPart(i)));
                    }
                    // Extract subtype (e.g., "mixed", "alternative")
                    String subtype = "MIXED";
                    int slashIdx = contentType.indexOf('/');
                    if (slashIdx > 0) {
                        String rest = contentType.substring(slashIdx + 1);
                        int semi = rest.indexOf(';');
                        subtype = (semi > 0 ? rest.substring(0, semi) : rest).trim().toUpperCase();
                    }
                    sb.append(" \"").append(subtype).append("\" NIL NIL NIL)");
                    return sb.toString();
                }
            }

            // Single part
            String type = "TEXT";
            String subtype = "PLAIN";
            int slash = contentType.indexOf('/');
            if (slash > 0) {
                type = contentType.substring(0, slash).trim().toUpperCase();
                String rest = contentType.substring(slash + 1);
                int semi = rest.indexOf(';');
                subtype = (semi > 0 ? rest.substring(0, semi) : rest).trim().toUpperCase();
            }

            // Extract charset
            String charset = "UTF-8";
            String ctLower = contentType.toLowerCase();
            int csIdx = ctLower.indexOf("charset=");
            if (csIdx > 0) {
                String csRest = contentType.substring(csIdx + 8).trim();
                if (csRest.startsWith("\"")) {
                    int end = csRest.indexOf('"', 1);
                    charset = end > 0 ? csRest.substring(1, end) : csRest.substring(1);
                } else {
                    int end = csRest.indexOf(';');
                    charset = (end > 0 ? csRest.substring(0, end) : csRest).trim();
                }
            }

            String encoding = "7BIT";
            if (part instanceof jakarta.mail.internet.MimePart mp) {
                String enc = mp.getEncoding();
                if (enc != null)
                    encoding = enc.toUpperCase();
            }

            int size = part.getSize();
            if (size < 0)
                size = 0;
            int lines = 0;
            if ("TEXT".equals(type)) {
                // Estimate lines for text parts
                try {
                    Object content = part.getContent();
                    if (content instanceof String s) {
                        lines = s.split("\\r?\\n", -1).length;
                    }
                } catch (Exception ignored) {
                }
            }

            return "(\"" + type + "\" \"" + subtype + "\" (\"CHARSET\" \"" + charset.toUpperCase() +
                    "\") NIL NIL \"" + encoding + "\" " + size +
                    ("TEXT".equals(type) ? " " + lines : "") + ")";
        } catch (Exception e) {
            return "(\"TEXT\" \"PLAIN\" (\"CHARSET\" \"UTF-8\") NIL NIL \"7BIT\" 0 0)";
        }
    }

    /**
     * Handle BODY[n] / BODY.PEEK[n] individual MIME part fetches.
     * Outlook uses these to fetch specific parts based on BODYSTRUCTURE.
     */
    private void handleMimePartFetch(String dataItemsRaw, MailMail mail, List<String> items) {
        // Match patterns like BODY[1], BODY.PEEK[1], BODY[1.1], BODY.PEEK[2.MIME]
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("BODY(?:\\.PEEK)?\\[(\\d+(?:\\.\\d+)*(?:\\.(?:MIME|HEADER|TEXT))?)\\]",
                        java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(dataItemsRaw);

        while (matcher.find()) {
            String partSpec = matcher.group(1);
            String sectionName = "BODY[" + partSpec + "]";
            try {
                byte[] emlData = messageService.getEmlDataByMessageId(mail.getMessageId());
                if (emlData == null)
                    continue;

                MimeMessage msg = EmlParser.parse(emlData);
                String partContent = extractMimePart(msg, partSpec);
                if (partContent != null) {
                    items.add(sectionName + " {" + partContent.length() + "}\r\n" + partContent);
                } else {
                    items.add(sectionName + " \"\"");
                }
            } catch (Exception e) {
                log.debug("Failed to fetch MIME part {} for uid {}", partSpec, mail.getUid());
                items.add(sectionName + " \"\"");
            }
        }
    }

    /**
     * Extract a specific MIME part by its section number (e.g., "1", "1.2",
     * "2.MIME")
     */
    private String extractMimePart(jakarta.mail.Part part, String partSpec) throws Exception {
        if (partSpec == null || partSpec.isEmpty())
            return null;

        // Handle .MIME, .HEADER, .TEXT suffix
        String suffix = null;
        String numPart = partSpec;
        for (String s : new String[] { "MIME", "HEADER", "TEXT" }) {
            if (partSpec.toUpperCase().endsWith("." + s)) {
                suffix = s;
                numPart = partSpec.substring(0, partSpec.length() - s.length() - 1);
                break;
            }
        }

        // Navigate to the target part
        jakarta.mail.Part target = navigateToPart(part, numPart);
        if (target == null)
            return null;

        if (suffix != null) {
            return switch (suffix.toUpperCase()) {
                case "MIME" -> {
                    StringBuilder headers = new StringBuilder();
                    var en = target instanceof jakarta.mail.internet.MimePart mp ? mp.getAllHeaderLines() : null;
                    if (en != null) {
                        while (en.hasMoreElements()) {
                            headers.append(en.nextElement()).append("\r\n");
                        }
                    }
                    headers.append("\r\n");
                    yield headers.toString();
                }
                case "HEADER" -> {
                    StringBuilder headers = new StringBuilder();
                    var en = target instanceof jakarta.mail.internet.MimePart mp ? mp.getAllHeaderLines() : null;
                    if (en != null) {
                        while (en.hasMoreElements()) {
                            headers.append(en.nextElement()).append("\r\n");
                        }
                    }
                    headers.append("\r\n");
                    yield headers.toString();
                }
                case "TEXT" -> {
                    Object content = target.getContent();
                    yield content != null ? content.toString() : "";
                }
                default -> null;
            };
        }

        // Return full part content
        Object content = target.getContent();
        if (content instanceof String s) {
            return s;
        } else if (content instanceof java.io.InputStream is) {
            return new String(is.readAllBytes());
        }
        return content != null ? content.toString() : "";
    }

    /**
     * Navigate to a MIME part by section number (e.g., "1", "2", "1.2")
     */
    private jakarta.mail.Part navigateToPart(jakarta.mail.Part part, String sectionNum) throws Exception {
        if (sectionNum == null || sectionNum.isEmpty())
            return part;

        String[] indices = sectionNum.split("\\.");
        jakarta.mail.Part current = part;

        for (String idxStr : indices) {
            int idx;
            try {
                idx = Integer.parseInt(idxStr);
            } catch (NumberFormatException e) {
                return null;
            }

            Object content = current.getContent();
            if (content instanceof Multipart mp) {
                if (idx < 1 || idx > mp.getCount())
                    return null;
                current = mp.getBodyPart(idx - 1);
            } else {
                // Non-multipart: part 1 refers to self
                if (idx == 1)
                    return current;
                return null;
            }
        }
        return current;
    }

    private String extractSearchKeyword(String args, String key) {
        int idx = args.toUpperCase().indexOf(key);
        if (idx < 0)
            return "";
        String rest = args.substring(idx + key.length()).trim();
        if (rest.startsWith("\"")) {
            int endQuote = rest.indexOf('"', 1);
            return endQuote > 0 ? rest.substring(1, endQuote) : rest.substring(1);
        }
        return rest.split("\\s+")[0];
    }

    private String[] parseLoginArgs(String args) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;

        for (char c : args.toCharArray()) {
            if (c == '"') {
                inQuote = !inQuote;
            } else if (c == ' ' && !inQuote) {
                if (current.length() > 0) {
                    result.add(current.toString());
                    current = new StringBuilder();
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            result.add(current.toString());
        }
        return result.toArray(new String[0]);
    }

    private String unquote(String s) {
        if (s == null)
            return "";
        s = s.trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private String[] splitQuotedArgs(String args, int limit) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        for (char c : args.toCharArray()) {
            if (c == '"') {
                inQuote = !inQuote;
                current.append(c);
            } else if (c == ' ' && !inQuote) {
                if (current.length() > 0) {
                    result.add(current.toString());
                    current = new StringBuilder();
                    if (result.size() >= limit - 1) {
                        // Put the rest into a single token
                    }
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0)
            result.add(current.toString());
        return result.toArray(new String[0]);
    }

    private void respond(ChannelHandlerContext ctx, String response) {
        log.debug("IMAP >> {}", response);
        ctx.writeAndFlush(response + "\r\n");
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
            log.warn("IMAP TLS handshake failed from {}: {} (client may not trust the certificate)", ip, msg);
        } else if ("Connection reset".equals(msg) || cause instanceof java.io.IOException) {
            log.debug("IMAP connection reset from {}: {}", ip, msg);
        } else {
            log.error("IMAP error from {}: {}", ip, msg);
        }
        ctx.close();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.info("IMAP connection closed: {}", session.getRemoteIp());
    }
}
