package com.ninemail.service;

import com.ninemail.config.ServerProperties;
import com.ninemail.domain.MailUser;
import com.ninemail.mapper.MailUserMapper;
import com.ninemail.util.CryptoUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * AuthService unit tests
 * - SHA-256 verification and stripping of <>
 * - Local-to-local mail logic
 * - Security rules for external relay (login/relay IP)
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private MailUserMapper userMapper;

    @Mock
    private ServerProperties properties;

    @InjectMocks
    private AuthService authService;

    private MailUser testUser;

    @BeforeEach
    void setUp() {
        testUser = MailUser.builder()
                .username("testuser")
                .email("testuser@localhost")
                .password(CryptoUtil.sha256("password123"))
                .active(1)
                .build();
    }

    @Test
    @DisplayName("SHA-256 verification: correct password")
    void testAuthenticate_Success() {
        when(userMapper.findByEmail("testuser@localhost")).thenReturn(testUser);

        boolean result = authService.authenticate("testuser@localhost", "password123");
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("SHA-256 verification: wrong password")
    void testAuthenticate_WrongPassword() {
        when(userMapper.findByEmail("testuser@localhost")).thenReturn(testUser);

        boolean result = authService.authenticate("testuser@localhost", "wrongpassword");
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Authentication after stripping <>: <user@localhost> -> user@localhost")
    void testAuthenticate_AngleBracketRemoval() {
        when(userMapper.findByEmail("testuser@localhost")).thenReturn(testUser);

        boolean result = authService.authenticate("<testuser@localhost>", "password123");
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Authentication fails for non-existent user")
    void testAuthenticate_UserNotFound() {
        when(userMapper.findByEmail("unknown@localhost")).thenReturn(null);
        when(userMapper.findByUsername("unknown@localhost")).thenReturn(null);

        boolean result = authService.authenticate("unknown@localhost", "password123");
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Authentication fails for inactive account")
    void testAuthenticate_InactiveUser() {
        MailUser inactiveUser = MailUser.builder()
                .username("inactive")
                .email("inactive@localhost")
                .password(CryptoUtil.sha256("password123"))
                .active(0)
                .build();
        when(userMapper.findByEmail("inactive@localhost")).thenReturn(inactiveUser);

        boolean result = authService.authenticate("inactive@localhost", "password123");
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("AUTH PLAIN authentication (Base64)")
    void testAuthenticatePlain() {
        when(userMapper.findByEmail("testuser@localhost")).thenReturn(testUser);

        // Base64 encode: \0testuser@localhost\0password123
        String credentials = CryptoUtil.encodeBase64("\0testuser@localhost\0password123");
        boolean result = authService.authenticatePlain(credentials);
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Check local domain")
    void testIsLocalDomain() {
        ServerProperties.Security security = new ServerProperties.Security();
        when(properties.getDomain()).thenReturn("localhost");

        assertThat(authService.isLocalDomain("localhost")).isTrue();
        assertThat(authService.isLocalDomain("external.com")).isFalse();
    }

    @Test
    @DisplayName("Check local user existence")
    void testIsLocalUser() {
        when(userMapper.countByEmail("testuser@localhost")).thenReturn(1);
        when(userMapper.countByEmail("unknown@localhost")).thenReturn(0);

        assertThat(authService.isLocalUser("testuser@localhost")).isTrue();
        assertThat(authService.isLocalUser("unknown@localhost")).isFalse();
    }

    @Test
    @DisplayName("Check allowed relay IP")
    void testIsRelayAllowed() {
        ServerProperties.Security security = new ServerProperties.Security();
        security.setRelayIps(List.of("127.0.0.1", "::1"));
        when(properties.getSecurity()).thenReturn(security);

        assertThat(authService.isRelayAllowed("127.0.0.1")).isTrue();
        assertThat(authService.isRelayAllowed("192.168.1.100")).isFalse();
    }

    @Test
    @DisplayName("External relay: authenticated user allowed")
    void testCanRelayExternal_Authenticated() {
        assertThat(authService.canRelayExternal(true, "192.168.1.100")).isTrue();
    }

    @Test
    @DisplayName("External relay: relay IP allowed")
    void testCanRelayExternal_RelayIp() {
        ServerProperties.Security security = new ServerProperties.Security();
        security.setRelayIps(List.of("127.0.0.1"));
        when(properties.getSecurity()).thenReturn(security);

        assertThat(authService.canRelayExternal(false, "127.0.0.1")).isTrue();
    }

    @Test
    @DisplayName("External relay: unauthenticated + disallowed IP denied")
    void testCanRelayExternal_Denied() {
        ServerProperties.Security security = new ServerProperties.Security();
        security.setRelayIps(List.of("127.0.0.1"));
        when(properties.getSecurity()).thenReturn(security);

        assertThat(authService.canRelayExternal(false, "10.0.0.1")).isFalse();
    }
}
