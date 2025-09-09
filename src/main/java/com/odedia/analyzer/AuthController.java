package com.odedia.analyzer;

import java.security.Principal;

import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class AuthController {

    @GetMapping("/auth/status")
    public ResponseEntity<Map<String, Object>> authStatus(Authentication authentication, Principal principal) {
        boolean isAuthenticated = authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);

        String username = null;
        String email = null;
        String displayName = null;

        if (isAuthenticated) {
            if (principal != null) {
                username = principal.getName();
            }
            if (authentication.getPrincipal() instanceof OAuth2User oAuth2User) {
                Object emailAttr = oAuth2User.getAttributes().get("email");
                Object nameAttr = oAuth2User.getAttributes().get("name");
                Object loginAttr = oAuth2User.getAttributes().get("login");
                email = emailAttr == null ? null : String.valueOf(emailAttr);
                // Prefer full name, fallback to GitHub login, then username
                if (nameAttr != null) {
                    displayName = String.valueOf(nameAttr);
                } else if (loginAttr != null) {
                    displayName = String.valueOf(loginAttr);
                } else if (username != null) {
                    displayName = username;
                }
                if (displayName == null) {
                    displayName = email;
                }
            }
        }

        return ResponseEntity.ok(Map.of(
                "authenticated", isAuthenticated,
                "username", username == null ? "" : username,
                "email", email == null ? "" : email,
                "displayName", displayName == null ? "" : displayName
        ));
    }
}


