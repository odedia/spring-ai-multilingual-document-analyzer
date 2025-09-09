package com.odedia.analyzer;

import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.view.RedirectView;

import java.util.Map;

@Controller
public class LoginController {
	private final ClientRegistrationRepository clientRegistrationRepository;

	public LoginController(ClientRegistrationRepository clientRegistrationRepository) {
		this.clientRegistrationRepository = clientRegistrationRepository;
	}

	@GetMapping("/login")
	public RedirectView loginRedirect() {
		// Prefer CF SSO tile registrations if present (java-cfenv auto-binds them)
		String[] cfCandidates = new String[] { "p-identity", "uaa", "sso", "p-identity-sso", "cf", "okta" };
		for (String id : cfCandidates) {
			ClientRegistration reg = findRegistration(id);
			if (reg != null) {
				return new RedirectView("/oauth2/authorization/" + reg.getRegistrationId());
			}
		}
		// Fallback to GitHub if configured
		ClientRegistration github = findRegistration("github");
		if (github != null) {
			return new RedirectView("/oauth2/authorization/github");
		}
		// If no providers are configured, show default Spring login (will error nicely)
		return new RedirectView("/login.html");
	}

	@GetMapping("/auth/provider")
	@ResponseBody
	public Map<String, String> authProvider() {
		String provider = "none";
		String[] cfCandidates = new String[] { "p-identity", "uaa", "sso", "p-identity-sso", "cf", "okta" };
		for (String id : cfCandidates) {
			if (findRegistration(id) != null) {
				provider = "cf-sso";
				break;
			}
		}
		if ("none".equals(provider) && findRegistration("github") != null) {
			provider = "github";
		}
		return Map.of("provider", provider);
	}

	private ClientRegistration findRegistration(String id) {
		if (clientRegistrationRepository == null) {
			return null;
		}
		try {
			return ((org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository) clientRegistrationRepository)
					.findByRegistrationId(id);
		} catch (ClassCastException e) {
			return null;
		}
	}
}
