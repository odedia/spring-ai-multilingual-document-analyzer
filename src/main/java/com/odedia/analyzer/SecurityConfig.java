package com.odedia.analyzer;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
			.csrf(csrf -> csrf.disable())
			// API calls (/document/**) must get a 401 when the session has expired, NOT a
			// 302 redirect to the login page — otherwise fetch() follows the redirect and the
			// login HTML streams into the chat as if it were an answer. The browser-navigation
			// login redirect still applies to normal page loads.
			.exceptionHandling(ex -> ex.defaultAuthenticationEntryPointFor(
					new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
					new AntPathRequestMatcher("/document/**")))
			.authorizeHttpRequests(auth -> auth
				.requestMatchers(
					"/login",
					"/login.html",
					"/static/**",
					"/css/**",
					"/js/**",
					"/images/**",
					"/favicon.ico",
					"/auth/status",
					"/auth/provider",
					"/actuator/health**",
					"/actuator/info",
					"/login**",
					"/oauth2/**"
				).permitAll()
				.requestMatchers("/document/**").authenticated()
				.anyRequest().authenticated()
			)
			.oauth2Login(oauth -> oauth
				// render static login page; its button will choose provider
				.loginPage("/login.html")
				.defaultSuccessUrl("/", true)
			)
			.logout(l -> l.logoutUrl("/logout").logoutSuccessUrl("/"));

		return http.build();
	}
}
