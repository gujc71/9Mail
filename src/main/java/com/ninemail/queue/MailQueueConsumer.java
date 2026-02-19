package com.ninemail.queue;

import com.ninemail.config.ServerProperties;
import com.ninemail.service.MailDeliveryService;
import com.ninemail.service.MessageService;
import jakarta.jms.BytesMessage;
import jakarta.jms.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * ActiveMQ mail queue consumer
 * - Outbound queue: external delivery
 * - Inbound queue: incoming mail processing
 * - Exponential-backoff retry logic
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MailQueueConsumer {

    private final MailDeliveryService deliveryService;
    private final MessageService messageService;
    private final MailQueueProducer queueProducer;
    private final ServerProperties properties;

    /**
     * Process outbound queue: send external mail
     */
    @JmsListener(destination = "${ninemail.queue.outbound-destination:mail.outbound.queue}")
    public void processOutbound(Message message) {
        try {
            if (!(message instanceof BytesMessage bytesMessage)) {
                log.warn("Unexpected message type in outbound queue");
                return;
            }

            byte[] emlData = new byte[(int) bytesMessage.getBodyLength()];
            bytesMessage.readBytes(emlData);

            String sender = bytesMessage.getStringProperty("sender");
            String recipient = bytesMessage.getStringProperty("recipient");
            int attemptCount = bytesMessage.getIntProperty("attemptCount");

            log.info("Processing outbound mail: {} -> {} (attempt {})", sender, recipient, attemptCount);

            Boolean success = deliveryService.deliverExternalMail(emlData, sender, recipient).block();

            if (Boolean.TRUE.equals(success)) {
                log.info("Successfully delivered to {}", recipient);
            } else {
                // Retry
                if (attemptCount < properties.getQueue().getRetryMaxAttempts()) {
                    long backoff = deliveryService.calculateBackoff(attemptCount);
                    long nextRetry = System.currentTimeMillis() + backoff;
                    queueProducer.enqueueDeferred(emlData, sender, recipient, attemptCount + 1, nextRetry);
                    log.warn("Delivery failed, will retry in {}ms (attempt {})", backoff, attemptCount + 1);
                } else {
                    log.error("Max retry attempts reached for {} -> {}. Mail dropped.", sender, recipient);
                    // TODO: generate bounce message
                }
            }
        } catch (Exception e) {
            log.error("Error processing outbound queue message", e);
        }
    }

    /**
     * Process inbound queue: deliver inbound mail
     */
    @JmsListener(destination = "${ninemail.queue.inbound-destination:mail.inbound.queue}")
    public void processInbound(Message message) {
        try {
            if (!(message instanceof BytesMessage bytesMessage)) {
                log.warn("Unexpected message type in inbound queue");
                return;
            }

            byte[] emlData = new byte[(int) bytesMessage.getBodyLength()];
            bytesMessage.readBytes(emlData);

            String sender = bytesMessage.getStringProperty("sender");
            String recipientsStr = bytesMessage.getStringProperty("recipients");
            List<String> recipients = Arrays.asList(recipientsStr.split(","));

            log.info("Processing inbound mail: {} -> {}", sender, recipients);
            messageService.processIncomingMail(emlData, sender, recipients);

        } catch (Exception e) {
            log.error("Error processing inbound queue message", e);
        }
    }
}
