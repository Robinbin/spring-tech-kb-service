package com.springtechkb.user.internal;

import com.springtechkb.JwtPrincipalResolver;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users/me")
class UserProfileController {

    private final AuthService users;

    UserProfileController(AuthService users) {
        this.users = users;
    }

    @GetMapping
    AuthService.PublicUser get(@AuthenticationPrincipal Jwt jwt) {
        return users.currentUser(JwtPrincipalResolver.userId(jwt));
    }
}
