package com.ninemail.config;

import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import javax.net.ssl.KeyManagerFactory;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;

/**
 * Netty TLS/SSL configuration
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class NettyConfig {

    private final ServerProperties properties;

    @Bean
    @ConditionalOnProperty(name = "ninemail.tls.enabled", havingValue = "true")
    public SslContext sslContext() {
        try {
            String keystorePath = properties.getTls().getKeystorePath();
            KeyStore keyStore = KeyStore.getInstance(properties.getTls().getKeystoreType());

            if (keystorePath.startsWith("classpath:")) {
                String resourcePath = keystorePath.substring("classpath:".length());
                try (InputStream is = new ClassPathResource(resourcePath).getInputStream()) {
                    keyStore.load(is, properties.getTls().getKeystorePassword().toCharArray());
                }
            } else {
                try (FileInputStream fis = new FileInputStream(keystorePath)) {
                    keyStore.load(fis, properties.getTls().getKeystorePassword().toCharArray());
                }
            }

            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, properties.getTls().getKeyPassword().toCharArray());

            // Use OpenSSL provider if available for better performance and mobile
            // compatibility
            SslProvider provider = OpenSsl.isAvailable() ? SslProvider.OPENSSL : SslProvider.JDK;

            SslContext ctx = SslContextBuilder.forServer(kmf)
                    .sslProvider(provider)
                    .protocols("TLSv1.2", "TLSv1.3")
                    .ciphers(null, SupportedCipherSuiteFilter.INSTANCE)
                    .clientAuth(ClientAuth.NONE)
                    .build();
            log.info("SSL/TLS context initialized (keystore: {}, provider: {}, protocols: TLSv1.2+TLSv1.3)",
                    keystorePath, provider);
            return ctx;
        } catch (Exception e) {
            log.error("Failed to initialize SSL context: {}", e.getMessage());
            throw new RuntimeException("SSL context initialization failed", e);
        }
    }
}
