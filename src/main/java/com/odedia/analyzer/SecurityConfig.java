package com.odedia.analyzer;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
			.csrf(csrf -> csrf.disable())
			.authorizeHttpRequests(auth -> auth
				.requestMatchers(
					"/login",
					"/login.html",
					"/static/**",
					"/css/**",
					"/js/**",
					"/images/**",
					"/logo.png",
					"/favicon.ico",
					"/auth/status",
					"/actuator/health**",
					"/actuator/info",
					"/login**",
					"/oauth2/**"
				).permitAll()
				.requestMatchers("/document/**").authenticated()
				.anyRequest().authenticated()
			)
			.oauth2Login(oauth -> oauth
				.loginPage("/login.html")
				.defaultSuccessUrl("/", true)
			)
			.logout(l -> l.logoutUrl("/logout").logoutSuccessUrl("/"));

		return http.build();
	}
}
