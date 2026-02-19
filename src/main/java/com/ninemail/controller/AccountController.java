package com.ninemail.controller;

import com.ninemail.domain.MailUser;
import com.ninemail.mapper.MailUserMapper;
import com.ninemail.service.AuthService;
import com.ninemail.service.MailboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Account management REST API
 * - Create account (POST /api/accounts)
 * - Delete account (DELETE /api/accounts/{email})
 * - List accounts (GET /api/accounts)
 * - Get account (GET /api/accounts/{email})
 */
@Slf4j
@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AuthService authService;
    private final MailboxService mailboxService;
    private final MailUserMapper userMapper;

    /**
        * Create account
     * POST /api/accounts
     * Body: { "username": "user1", "email": "user1@localhost", "password": "pass123" }
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createAccount(@RequestBody Map<String, String> request) {
        String username = request.get("username");
        String email = request.get("email");
        String password = request.get("password");

        // Required field validation
        if (username == null || username.isBlank()) {
            return errorResponse(HttpStatus.BAD_REQUEST, "username is required.");
        }
        if (email == null || email.isBlank()) {
            return errorResponse(HttpStatus.BAD_REQUEST, "email is required.");
        }
        if (password == null || password.isBlank()) {
            return errorResponse(HttpStatus.BAD_REQUEST, "password is required.");
        }

        // Email format validation
        if (!email.contains("@")) {
            return errorResponse(HttpStatus.BAD_REQUEST, "Invalid email format.");
        }

        // Server domain validation
        String domain = email.substring(email.indexOf("@") + 1);
        if (!authService.isLocalDomain(domain)) {
            return errorResponse(HttpStatus.BAD_REQUEST,
                    "Domain is not allowed. Allowed domain: " + authService.getLocalDomain());
        }

        // Duplicate check
        if (userMapper.countByEmail(email) > 0) {
            return errorResponse(HttpStatus.CONFLICT, "Email already exists: " + email);
        }

        // Create account
        authService.createUser(username, email, password);

        // Create default mailboxes (INBOX, Sent, Drafts, Trash, Junk)
        mailboxService.createDefaultMailboxes(email);

        log.info("Account created via API: {}", email);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "success");
        response.put("message", "Account created.");
        response.put("email", email);
        response.put("username", username);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
        * Delete account
     * DELETE /api/accounts/{email}
     */
    @DeleteMapping("/{email}")
    public ResponseEntity<Map<String, Object>> deleteAccount(@PathVariable String email) {
        // Existence check
        MailUser user = userMapper.findByEmail(email);
        if (user == null) {
            return errorResponse(HttpStatus.NOT_FOUND, "Account not found: " + email);
        }

        // Delete account
        userMapper.deleteByEmail(email);
        log.info("Account deleted via API: {}", email);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "success");
        response.put("message", "Account deleted.");
        response.put("email", email);

        return ResponseEntity.ok(response);
    }

    /**
        * List accounts
     * GET /api/accounts
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> listAccounts() {
        List<MailUser> users = userMapper.findAll();
        List<Map<String, Object>> userList = users.stream().map(user -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("username", user.getUsername());
            map.put("email", user.getEmail());
            map.put("active", user.getActive());
            map.put("createdDt", user.getCreatedDt());
            return map;
        }).collect(Collectors.toList());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "success");
        response.put("count", userList.size());
        response.put("accounts", userList);

        return ResponseEntity.ok(response);
    }

    /**
        * Get account
     * GET /api/accounts/{email}
     */
    @GetMapping("/{email}")
    public ResponseEntity<Map<String, Object>> getAccount(@PathVariable String email) {
        MailUser user = userMapper.findByEmail(email);
        if (user == null) {
            return errorResponse(HttpStatus.NOT_FOUND, "Account not found: " + email);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "success");
        response.put("username", user.getUsername());
        response.put("email", user.getEmail());
        response.put("active", user.getActive());
        response.put("createdDt", user.getCreatedDt());

        return ResponseEntity.ok(response);
    }

    private ResponseEntity<Map<String, Object>> errorResponse(HttpStatus status, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "error");
        response.put("message", message);
        return ResponseEntity.status(status).body(response);
    }
}
