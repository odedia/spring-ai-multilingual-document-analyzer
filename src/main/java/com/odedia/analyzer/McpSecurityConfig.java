package com.odedia.analyzer;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Slim security for the internal MCP process. The app is not on a public route;
 * Tanzu MCP Gateway is the intended caller (SSO / one-click Cursor).
 */
@Configuration
@EnableWebSecurity
@Profile("mcp")
public class McpSecurityConfig {

	@Bean
	SecurityFilterChain mcpSecurityFilterChain(HttpSecurity http) throws Exception {
		http
				.csrf(csrf -> csrf.disable())
				.sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/mcp", "/mcp/**", "/actuator/health", "/actuator/health/**",
								"/actuator/info")
						.permitAll()
						.anyRequest().denyAll())
				.httpBasic(basic -> basic.disable())
				.formLogin(form -> form.disable())
				.logout(logout -> logout.disable());
		return http.build();
	}
}
