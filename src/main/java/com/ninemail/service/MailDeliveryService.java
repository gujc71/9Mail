package com.ninemail.service;

import com.ninemail.config.ServerProperties;
import com.ninemail.util.DnsUtil;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Properties;

/**
 * External mail delivery service
 * - Direct delivery after MX record lookup
 * - Uses Jakarta Mail
 * - Reactive async delivery
 * - Exponential-backoff retry strategy
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailDeliveryService {

    private final ServerProperties properties;

    /**
     * Deliver mail to an external domain (Reactive)
     */
    public Mono<Boolean> deliverExternalMail(byte[] emlData, String sender, String recipient) {
        return Mono.fromCallable(() -> sendToExternalServer(emlData, sender, recipient))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnSuccess(result -> log.info("External delivery to {} : {}", recipient, result ? "SUCCESS" : "FAILED"))
                .doOnError(e -> log.error("External delivery failed to {}", recipient, e))
                .onErrorReturn(false);
    }

    /**
     * Send mail to an external SMTP server
     */
    private boolean sendToExternalServer(byte[] emlData, String sender, String recipient) {
        String domain = recipient.substring(recipient.lastIndexOf('@') + 1);
        List<String> mxHosts = DnsUtil.lookupMx(domain);

        for (String mxHost : mxHosts) {
            try {
                Properties props = new Properties();
                props.put("mail.smtp.host", mxHost);
                props.put("mail.smtp.port", "25");
                props.put("mail.smtp.connectiontimeout", "10000");
                props.put("mail.smtp.timeout", "30000");
                props.put("mail.smtp.writetimeout", "20000");
                props.put("mail.smtp.localhost", properties.getAdvertisedHostname());
                props.put("mail.smtp.starttls.enable", "true");
                props.put("mail.smtp.starttls.required", "false");

                Session session = Session.getInstance(props);
                MimeMessage message = new MimeMessage(session, new ByteArrayInputStream(emlData));

                Transport transport = session.getTransport("smtp");
                try {
                    transport.connect(mxHost, 25, null, null);
                    transport.sendMessage(message, new Address[]{new InternetAddress(recipient)});
                    log.info("Mail sent to {} via MX host {}", recipient, mxHost);
                    return true;
                } finally {
                    transport.close();
                }
            } catch (Exception e) {
                log.warn("Failed to deliver to {} via {}: {}", recipient, mxHost, e.getMessage());
            }
        }

        log.error("All MX hosts failed for {}", recipient);
        return false;
    }

    /**
     * Calculate exponential backoff wait time
     * WaitTime = min(2^n + random_ms, max_backoff)
     */
    public long calculateBackoff(int attempt) {
        long baseDelay = properties.getQueue().getRetryInitialDelayMs();
        long maxDelay = properties.getQueue().getRetryMaxDelayMs();
        long delay = (long) (baseDelay * Math.pow(2, attempt));
        long jitter = (long) (Math.random() * 1000);
        return Math.min(delay + jitter, maxDelay);
    }
}
