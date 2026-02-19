package com.ninemail.controller;

import com.ninemail.config.ServerProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Server diagnostic endpoint for troubleshooting connectivity issues.
 * Hit GET /api/diagnostic to see port status and configuration.
 */
@Slf4j
@RestController
@RequestMapping("/api/diagnostic")
@RequiredArgsConstructor
public class DiagnosticController {

    private final ServerProperties properties;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> diagnostic() {
        Map<String, Object> result = new LinkedHashMap<>();

        // Server config
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("domain", properties.getDomain());
        config.put("hostname", properties.getHostname());
        config.put("advertisedHostname", properties.getAdvertisedHostname());
        config.put("tlsEnabled", properties.getTls().isEnabled());
        config.put("keystorePath", properties.getTls().getKeystorePath());
        result.put("config", config);

        // Port status - local loopback connectivity test
        Map<String, Object> ports = new LinkedHashMap<>();
        ports.put("smtp_" + properties.getSmtp().getPort(),
                checkPort("SMTP", properties.getSmtp().getPort()));
        ports.put("submission_" + properties.getSmtp().getSubmissionPort(),
                checkPort("Submission (STARTTLS)", properties.getSmtp().getSubmissionPort()));
        ports.put("smtps_" + properties.getSmtp().getSslPort(),
                checkPort("SMTPS (implicit SSL)", properties.getSmtp().getSslPort()));
        ports.put("imap_" + properties.getImap().getPort(),
                checkPort("IMAP", properties.getImap().getPort()));
        ports.put("imaps_" + properties.getImap().getSslPort(),
                checkPort("IMAPS (implicit SSL)", properties.getImap().getSslPort()));
        ports.put("http_8080", checkPort("REST API", 8080));
        result.put("ports", ports);

        // Firewall check commands
        result.put("firewallHelp", Map.of(
                "checkFirewalld", "sudo firewall-cmd --list-ports",
                "checkIptables", "sudo iptables -L -n | grep -E '25|465|587|993'",
                "checkListening", "sudo ss -tlnp | grep -E ':25 |:465 |:587 |:993 '",
                "openFirewalld",
                "sudo firewall-cmd --permanent --add-port={25,465,587}/tcp && sudo firewall-cmd --reload",
                "openIptables", "sudo iptables -A INPUT -p tcp -m multiport --dports 25,465,587 -j ACCEPT",
                "checkCloudSecurityGroup",
                "Check cloud provider console (AWS/GCP/OpenStack) inbound rules for ports 465, 587"));

        // Outlook setup guide
        result.put("outlookManualSetup", Map.of(
                "email", "user@" + properties.getDomain(),
                "imapServer", properties.getAdvertisedHostname(),
                "imapPort", properties.getImap().getSslPort(),
                "imapSecurity", "SSL/TLS",
                "smtpServer", properties.getAdvertisedHostname(),
                "smtpPort", properties.getSmtp().getSslPort(),
                "smtpSecurity", "SSL/TLS",
                "authMethod", "Password (PLAIN/LOGIN)"));

        log.info("Diagnostic check performed");
        return result;
    }

    private Map<String, Object> checkPort(String name, int port) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("name", name);
        status.put("port", port);

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2000);
            status.put("localListening", true);
            status.put("status", "OK - port is listening");
        } catch (IOException e) {
            status.put("localListening", false);
            status.put("status", "FAIL - " + e.getMessage());
        }

        return status;
    }
}
