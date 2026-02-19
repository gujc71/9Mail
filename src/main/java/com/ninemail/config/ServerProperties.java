package com.ninemail.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 9Mail server configuration properties
 */
@Data
@Component
@ConfigurationProperties(prefix = "ninemail")
public class ServerProperties {

    private String domain = "localhost";
    private String hostname = "mail.localhost";

    public String getAdvertisedHostname() {
        String configured = hostname == null ? "" : hostname.trim();
        if (configured.isEmpty()) {
            return fallbackAdvertisedHostname();
        }

        String lower = configured.toLowerCase();
        if ("localhost".equals(lower)
                || "mail.localhost".equals(lower)
                || "127.0.0.1".equals(lower)
                || "::1".equals(lower)) {
            return fallbackAdvertisedHostname();
        }
        return configured;
    }

    private String fallbackAdvertisedHostname() {
        String configuredDomain = domain == null ? "" : domain.trim().toLowerCase();
        if (configuredDomain.isEmpty() || "localhost".equals(configuredDomain)) {
            return "localhost";
        }
        return "mail." + configuredDomain;
    }

    private Smtp smtp = new Smtp();
    private Imap imap = new Imap();
    private Storage storage = new Storage();
    private Security security = new Security();
    private Queue queue = new Queue();
    private Tls tls = new Tls();

    @Data
    public static class Smtp {
        private int port = 2525;
        private int submissionPort = 587; // Submission (plain + STARTTLS, RFC 6409)
        private int sslPort = 4650; // SMTPS (implicit SSL, RFC 8314)
        private boolean requireAuth = true;
        private long maxMessageSize = 26214400L; // 25MB
        private int maxRecipients = 100;
        private long timeout = 300000L;
        private String banner = "9Mail ESMTP Server Ready";
    }

    @Data
    public static class Imap {
        private int port = 1143;
        private int sslPort = 9930;
        private long timeout = 1800000L;
        private int maxLineLength = 65536;
    }

    @Data
    public static class Storage {
        private String basePath = "data/maildir";

        /**
         * Raw EMLs are stored under basePath/YYYY/MM/DD/...
         */
        public String getEmlPath() {
            return basePath;
        }
    }

    @Data
    public static class Security {
        private List<String> relayIps = new ArrayList<>(List.of("127.0.0.1", "::1"));
        private int maxAuthFailures = 5;
        private long tarpitDelayMs = 3000L;
        private int rateLimitPerMinute = 30;
    }

    @Data
    public static class Queue {
        private int retryMaxAttempts = 5;
        private long retryInitialDelayMs = 60000L;
        private long retryMaxDelayMs = 3600000L;
        private String outboundDestination = "mail.outbound.queue";
        private String inboundDestination = "mail.inbound.queue";
    }

    @Data
    public static class Tls {
        private boolean enabled = false;
        private String keystorePath = "config/keystore.jks";
        private String keystoreType = "PKCS12";
        private String keystorePassword = "changeit";
        private String keyPassword = "changeit";
    }
}
