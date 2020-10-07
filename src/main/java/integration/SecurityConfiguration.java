package integration;

import integration.database.User;
import integration.database.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.resource.OAuth2ResourceServerConfigurer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Collection;
import java.util.Collections;
import java.util.stream.Collectors;

@Configuration
class SecurityConfiguration {

	// todo figure out how to authenticate with users/passwords in an encrypted database
	// todo figure out how to mask only parts of the API

	@Bean
	UserDetailsService jdbcUserDetailsService(UserRepository repository) {
		return new JdbcUserDetailsService(repository);
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return PasswordEncoderFactories.createDelegatingPasswordEncoder();
	}

	/*
	 * @Bean UserDetailsService authentication() { return new
	 * InMemoryUserDetailsManager(User.withDefaultPasswordEncoder()// .username("jlong")//
	 * .password("password")// .roles("USER")// .build()// ); }
	 */

	@Configuration
	public static class MyConfig extends WebSecurityConfigurerAdapter {

		@Override
		protected void configure(HttpSecurity http) throws Exception {
			http.cors(Customizer.withDefaults()) //
					.authorizeRequests(ae -> ae.mvcMatchers("/podcasts/{uid}").authenticated().anyRequest().permitAll()) //
					.oauth2ResourceServer(OAuth2ResourceServerConfigurer::jwt);
		}

	}

}

@Log4j2
@RequiredArgsConstructor
class JdbcUserDetailsService implements UserDetailsService {

	@RequiredArgsConstructor
	private static class JpaUserDetails implements UserDetails {

		private final User user;

		@Override
		public Collection<? extends GrantedAuthority> getAuthorities() {
			return Collections.singleton(new SimpleGrantedAuthority("USER"));
		}

		@Override
		public String getPassword() {
			return this.user.getPassword();
		}

		@Override
		public String getUsername() {
			return this.user.getUsername();
		}

		@Override
		public boolean isAccountNonExpired() {
			return true;
		}

		@Override
		public boolean isAccountNonLocked() {
			return true;
		}

		@Override
		public boolean isCredentialsNonExpired() {
			return true;
		}

		@Override
		public boolean isEnabled() {
			return true;
		}

	}

	private final UserRepository userRepository;

	@Override
	public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {

		var byUsername = this.userRepository.findByUsername(username).stream().map(JpaUserDetails::new)
				.collect(Collectors.toList());

		if (byUsername.size() != 1) {
			throw new UsernameNotFoundException(
					"couldn't find one and only one instance of the user '" + username + "' ");
		}

		return byUsername.get(0);
	}

}