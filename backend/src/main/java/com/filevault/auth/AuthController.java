package com.filevault.auth;

import com.filevault.common.ApiException;
import com.filevault.user.UserMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final UserMapper users;
    private final PasswordEncoder passwords;

    public AuthController(UserMapper users, PasswordEncoder passwords) {
        this.users = users;
        this.passwords = passwords;
    }

    public record Registration(@NotNull @Pattern(regexp = "[a-z0-9_]{3,32}") String username,
                               @NotNull String password) { }

    @GetMapping("/csrf")
    Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, String> register(@Valid @RequestBody Registration request) {
        int bytes = request.password().getBytes(StandardCharsets.UTF_8).length;
        if (bytes < 8 || bytes > 72 || request.password().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD", "Use 8 to 72 UTF-8 bytes.");
        }
        try {
            users.insert(request.username(), passwords.encode(request.password()));
        } catch (DuplicateKeyException duplicate) {
            throw new ApiException(HttpStatus.CONFLICT, "USERNAME_TAKEN", "The username is already used.");
        }
        return Map.of("username", request.username());
    }

    @GetMapping("/me")
    Map<String, String> me(Principal principal) {
        return Map.of("username", principal.getName());
    }
}
