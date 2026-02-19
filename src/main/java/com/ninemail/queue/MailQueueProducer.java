package com.ninemail.queue;

import com.ninemail.config.ServerProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ActiveMQ mail queue producer
 * - Enqueue outbound mails for external delivery
 * - Retry-capable message format
 * - Separate inbound/outbound queues
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MailQueueProducer {

    private final JmsTemplate jmsTemplate;
    private final ServerProperties properties;

    /**
     * Enqueue outbound mail to the outbound queue
     */
    public void enqueueOutbound(byte[] emlData, String sender, String recipient) {
        String destination = properties.getQueue().getOutboundDestination();
        jmsTemplate.send(destination, session -> {
            var message = session.createBytesMessage();
            message.writeBytes(emlData);
            message.setStringProperty("sender", sender);
            message.setStringProperty("recipient", recipient);
            message.setIntProperty("attemptCount", 0);
            message.setLongProperty("nextRetryTime", System.currentTimeMillis());
            return message;
        });
        log.info("Mail enqueued to outbound: {} -> {}", sender, recipient);
    }

    /**
     * Enqueue inbound mail to the inbound queue
     */
    public void enqueueInbound(byte[] emlData, String sender, List<String> recipients) {
        String destination = properties.getQueue().getInboundDestination();
        jmsTemplate.send(destination, session -> {
            var message = session.createBytesMessage();
            message.writeBytes(emlData);
            message.setStringProperty("sender", sender);
            message.setStringProperty("recipients", String.join(",", recipients));
            return message;
        });
        log.info("Mail enqueued to inbound: {} -> {}", sender, recipients);
    }

    /**
     * Enqueue deferred retry mail to the deferred queue
     */
    public void enqueueDeferred(byte[] emlData, String sender, String recipient, int attemptCount, long nextRetryTime) {
        String destination = properties.getQueue().getOutboundDestination() + ".deferred";
        jmsTemplate.send(destination, session -> {
            var message = session.createBytesMessage();
            message.writeBytes(emlData);
            message.setStringProperty("sender", sender);
            message.setStringProperty("recipient", recipient);
            message.setIntProperty("attemptCount", attemptCount);
            message.setLongProperty("nextRetryTime", nextRetryTime);
            return message;
        });
        log.info("Mail enqueued to deferred (attempt {}): {} -> {}", attemptCount, sender, recipient);
    }
}
