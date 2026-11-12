package com.springtechkb.user.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
class AuthController {

    private final AuthService authService;

    AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    AuthService.AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request.email(), request.username(), request.password());
    }

    @PostMapping("/login")
    AuthService.AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.identifier(), request.password());
    }

    record RegisterRequest(
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{3,100}") String username,
            @NotBlank @Size(min = 12, max = 128) String password) {}

    record LoginRequest(
            @NotBlank @Size(max = 255) String identifier,
            @NotBlank @Size(max = 128) String password) {}
}
