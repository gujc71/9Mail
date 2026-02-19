package com.ninemail.controller;

import com.ninemail.config.ServerProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thunderbird / Mail client auto-configuration endpoint.
 *
 * Thunderbird checks:
 *   http://autoconfig.{domain}/mail/config-v1.1.xml
 *   http://{domain}/.well-known/autoconfig/mail/config-v1.1.xml
 *   http://{domain}/mail/config-v1.1.xml
 */
@RestController
@RequiredArgsConstructor
public class AutoconfigController {

    private final ServerProperties properties;

    @GetMapping(value = {
            "/mail/config-v1.1.xml",
            "/.well-known/autoconfig/mail/config-v1.1.xml"
    }, produces = MediaType.APPLICATION_XML_VALUE)
    public String autoconfig(@RequestParam(value = "emailaddress", required = false) String email) {
        String domain = properties.getDomain();
        String hostname = properties.getAdvertisedHostname();

        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <clientConfig version="1.1">
                  <emailProvider id="%s">
                    <domain>%s</domain>
                    <displayName>9Mail</displayName>
                    <displayShortName>9Mail</displayShortName>
                    <incomingServer type="imap">
                      <hostname>%s</hostname>
                      <port>%d</port>
                      <socketType>SSL</socketType>
                      <authentication>password-cleartext</authentication>
                      <username>%%EMAILADDRESS%%</username>
                    </incomingServer>
                    <incomingServer type="imap">
                      <hostname>%s</hostname>
                      <port>%d</port>
                      <socketType>STARTTLS</socketType>
                      <authentication>password-cleartext</authentication>
                      <username>%%EMAILADDRESS%%</username>
                    </incomingServer>
                    <outgoingServer type="smtp">
                      <hostname>%s</hostname>
                      <port>%d</port>
                      <socketType>SSL</socketType>
                      <authentication>password-cleartext</authentication>
                      <username>%%EMAILADDRESS%%</username>
                    </outgoingServer>
                    <outgoingServer type="smtp">
                      <hostname>%s</hostname>
                      <port>%d</port>
                      <socketType>STARTTLS</socketType>
                      <authentication>password-cleartext</authentication>
                      <username>%%EMAILADDRESS%%</username>
                    </outgoingServer>
                  </emailProvider>
                </clientConfig>
                """.formatted(
                domain, domain,
                hostname, properties.getImap().getSslPort(),
                hostname, properties.getImap().getPort(),
                hostname, properties.getSmtp().getSslPort(),
                hostname, properties.getSmtp().getSubmissionPort()
        );
    }
}
