package com.ninemail.service;

import com.ninemail.config.ServerProperties;
import com.ninemail.domain.MailUser;
import com.ninemail.mapper.MailUserMapper;
import com.ninemail.util.CryptoUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Authentication service
 * - SHA-256 based password verification
 * - SMTP AUTH (PLAIN, LOGIN)
 * - Authenticate after stripping angle brackets (<>)
 * - Relay IP verification
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final MailUserMapper userMapper;
    private final ServerProperties properties;

    /**
     * Authenticate user (email + password).
     * Strips angle brackets (<>) from username before authentication.
     */
    public boolean authenticate(String username, String password) {
        String cleanUsername = CryptoUtil.stripAngleBrackets(username);
        log.debug("Authentication attempt for: {}", cleanUsername);

        MailUser user = userMapper.findByEmail(cleanUsername);
        if (user == null) {
            user = userMapper.findByUsername(cleanUsername);
        }
        if (user == null) {
            log.warn("Authentication failed: user not found - {}", cleanUsername);
            return false;
        }

        if (user.getActive() != 1) {
            log.warn("Authentication failed: user inactive - {}", cleanUsername);
            return false;
        }

        String hashedPassword = CryptoUtil.sha256(password);
        boolean result = hashedPassword.equalsIgnoreCase(user.getPassword());
        log.debug("Authentication result for {}: {}", cleanUsername, result);
        return result;
    }

    /**
     * Authenticate via AUTH PLAIN
     */
    public boolean authenticatePlain(String base64Credentials) {
        String[] credentials = CryptoUtil.decodeAuthPlain(base64Credentials);
        if (credentials == null || credentials.length < 2) {
            return false;
        }
        return authenticate(credentials[0], credentials[1]);
    }

    /**
     * Decode username for AUTH LOGIN (Base64)
     */
    public String decodeLoginUsername(String base64Username) {
        return CryptoUtil.decodeBase64(base64Username);
    }

    /**
     * Decode password for AUTH LOGIN (Base64)
     */
    public String decodeLoginPassword(String base64Password) {
        return CryptoUtil.decodeBase64(base64Password);
    }

    /**
     * Check if the domain is local
     */
    public boolean isLocalDomain(String domain) {
        return domain != null && domain.equalsIgnoreCase(properties.getDomain());
    }

    /**
     * Check if a local user exists
     */
    public boolean isLocalUser(String email) {
        return userMapper.countByEmail(email) > 0;
    }

    /**
     * Check whether the remote IP is allowed to relay
     */
    public boolean isRelayAllowed(String remoteIp) {
        return properties.getSecurity().getRelayIps().contains(remoteIp);
    }

    /**
     * Check whether relaying to external domains is allowed.
     * Allowed if authenticated, or if request comes from a configured relay IP.
     */
    public boolean canRelayExternal(boolean authenticated, String remoteIp) {
        return authenticated || isRelayAllowed(remoteIp);
    }

    /**
     * Get user
     */
    public MailUser getUser(String email) {
        return userMapper.findByEmail(email);
    }

    /**
     * Create user
     */
    public void createUser(String username, String email, String password) {
        MailUser user = MailUser.builder()
                .username(username)
                .email(email)
                .password(CryptoUtil.sha256(password))
                .active(1)
                .build();
        userMapper.insert(user);
        log.info("User created: {}", email);
    }

    /**
     * Return the configured local domain
     */
    public String getLocalDomain() {
        return properties.getDomain();
    }
}
