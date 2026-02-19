package com.ninemail.controller;

import com.ninemail.config.ServerProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Microsoft Outlook Autodiscover endpoint.
 *
 * Outlook checks endpoints such as:
 * POST /autodiscover/autodiscover.xml (classic XML autodiscover)
 * GET /autodiscover/autodiscover.json (Outlook Mobile / v2 JSON)
 */
@RestController
@RequiredArgsConstructor
public class AutodiscoverController {

  private static final Pattern EMAIL_PATTERN = Pattern.compile("<EMailAddress>([^<]+)</EMailAddress>",
      Pattern.CASE_INSENSITIVE);

  private final ServerProperties properties;

  @PostMapping(value = { "/autodiscover/autodiscover.xml", "/Autodiscover/Autodiscover.xml" }, consumes = {
      MediaType.APPLICATION_XML_VALUE,
      MediaType.TEXT_XML_VALUE }, produces = { MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE })
  public String autodiscover(@RequestBody(required = false) String requestBody) {
    String email = extractEmailAddress(requestBody);
    return buildAutodiscoverXml(email);
  }

  /**
   * GET variant of Autodiscover (some Outlook versions try GET first)
   */
  @GetMapping(value = { "/autodiscover/autodiscover.xml", "/Autodiscover/Autodiscover.xml" }, produces = {
      MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE })
  public String autodiscoverGet(@RequestParam(value = "Email", required = false) String email) {
    return buildAutodiscoverXml(email);
  }

  /**
   * Autodiscover V2 JSON endpoint (used by Outlook Mobile and newer clients).
   * Outlook Mobile sends separate requests per protocol:
   * GET /autodiscover/autodiscover.json?Email=user@domain&Protocol=Imap
   * GET /autodiscover/autodiscover.json?Email=user@domain&Protocol=Smtp
   * GET /autodiscover/autodiscover.json?Email=user@domain&Protocol=ActiveSync
   *
   * Each request expects a protocol-specific JSON response.
   */
  @GetMapping(value = { "/autodiscover/autodiscover.json",
      "/Autodiscover/Autodiscover.json" }, produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<String> autodiscoverJson(
      @RequestParam(value = "Email", required = false) String email,
      @RequestParam(value = "Protocol", required = false) String protocol,
      @RequestParam(value = "RedirectCount", required = false) String redirectCount) {

    String hostname = properties.getAdvertisedHostname();
    String loginName = email != null ? email : "";

    if (protocol == null || protocol.isBlank()) {
      protocol = "AutodiscoverV1";
    }

    String json;
    switch (protocol) {
      case "Imap" -> {
        json = """
            {
              "Protocol": "IMAP",
              "Server": "%s",
              "Port": %d,
              "LoginName": "%s",
              "Encryption": "SSL",
              "Authentication": "password-cleartext"
            }
            """.formatted(
            hostname,
            properties.getImap().getSslPort(),
            loginName);
      }
      case "Smtp" -> {
        json = """
            {
              "Protocol": "SMTP",
              "Server": "%s",
              "Port": %d,
              "LoginName": "%s",
              "Encryption": "SSL",
              "Authentication": "password-cleartext"
            }
            """.formatted(
            hostname,
            properties.getSmtp().getSslPort(),
            loginName);
      }
      case "Pop" -> {
        // POP3 not supported - return error
        json = """
            {
              "ErrorCode": "ProtocolNotSupported",
              "ErrorMessage": "POP3 is not supported by this server. Use IMAP instead."
            }
            """;
        return ResponseEntity.ok(json);
      }
      case "ActiveSync" -> {
        // ActiveSync not supported - redirect to IMAP
        json = """
            {
              "ErrorCode": "ProtocolNotSupported",
              "ErrorMessage": "ActiveSync is not supported. Use IMAP/SMTP."
            }
            """;
        return ResponseEntity.ok(json);
      }
      default -> {
        // AutodiscoverV1 or unknown - return combined info
        json = """
            {
              "Protocol": "IMAP",
              "Url": "imaps://%s:%d",
              "Server": "%s",
              "LoginName": "%s",
              "IMAP": {
                "Server": "%s",
                "Port": %d,
                "Encryption": "SSL",
                "Authentication": "password-cleartext"
              },
              "SMTP": {
                "Server": "%s",
                "Port": %d,
                "Encryption": "SSL",
                "Authentication": "password-cleartext"
              }
            }
            """.formatted(
            hostname, properties.getImap().getSslPort(),
            hostname,
            loginName,
            hostname, properties.getImap().getSslPort(),
            hostname, properties.getSmtp().getSslPort());
      }
    }
    return ResponseEntity.ok(json);
  }

  private String buildAutodiscoverXml(String emailInput) {
    String hostname = properties.getAdvertisedHostname();
    String domain = properties.getDomain();
    String email = (emailInput != null && emailInput.endsWith("@" + domain)) ? emailInput : null;
    String loginName = (email == null || email.isBlank()) ? "%EMAILADDRESS%" : email;

    return """
        <?xml version="1.0" encoding="utf-8"?>
        <Autodiscover xmlns="http://schemas.microsoft.com/exchange/autodiscover/responseschema/2006">
          <Response xmlns="http://schemas.microsoft.com/exchange/autodiscover/outlook/responseschema/2006a">
            <Account>
              <AccountType>email</AccountType>
              <Action>settings</Action>
              <Protocol>
                <Type>IMAP</Type>
                <Server>%s</Server>
                <Port>%d</Port>
                <DomainRequired>off</DomainRequired>
                <SPA>off</SPA>
                <SSL>on</SSL>
                <Encryption>SSL</Encryption>
                <AuthRequired>on</AuthRequired>
                <LoginName>%s</LoginName>
              </Protocol>
              <Protocol>
                <Type>SMTP</Type>
                <Server>%s</Server>
                <Port>%d</Port>
                <DomainRequired>off</DomainRequired>
                <SPA>off</SPA>
                <SSL>on</SSL>
                <Encryption>SSL</Encryption>
                <AuthRequired>on</AuthRequired>
                <LoginName>%s</LoginName>
                <UsePOPAuth>off</UsePOPAuth>
                <SMTPLast>on</SMTPLast>
              </Protocol>
              <Protocol>
                <Type>SMTP</Type>
                <Server>%s</Server>
                <Port>%d</Port>
                <DomainRequired>off</DomainRequired>
                <SPA>off</SPA>
                <SSL>off</SSL>
                <Encryption>TLS</Encryption>
                <AuthRequired>on</AuthRequired>
                <LoginName>%s</LoginName>
                <UsePOPAuth>off</UsePOPAuth>
                <SMTPLast>on</SMTPLast>
              </Protocol>
            </Account>
          </Response>
        </Autodiscover>
        """.formatted(
        hostname, properties.getImap().getSslPort(), loginName,
        hostname, properties.getSmtp().getSslPort(), loginName,
        hostname, properties.getSmtp().getSubmissionPort(), loginName);
  }

  private String extractEmailAddress(String requestBody) {
    if (requestBody == null || requestBody.isBlank()) {
      return null;
    }
    Matcher matcher = EMAIL_PATTERN.matcher(requestBody);
    if (matcher.find()) {
      String found = matcher.group(1);
      if (found != null && found.endsWith("@" + properties.getDomain())) {
        return found;
      }
    }
    return null;
  }
}
