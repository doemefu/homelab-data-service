package ch.furchert.homelab.data.config;

import ch.furchert.homelab.data.security.AllowedClientAuthorizationManager;
import ch.furchert.homelab.data.security.ProblemJsonAccessDeniedHandler;
import ch.furchert.homelab.data.security.ProblemJsonAuthenticationEntryPoint;
import ch.furchert.homelab.data.security.TokenValidationUnavailableFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Collection;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    static final String NETMON_READ = "SCOPE_netmon:read";

    /**
     * Chain 1: the netmon read API (docs/060 §7.1, §7.5). Stateless JWT resource server; v1 grants
     * access only if the token has {@code SCOPE_netmon:read} AND its {@code sub} is an allowed client.
     * {@code ROLE_ADMIN} is deliberately not accepted: user tokens issued to any SSO client carry it.
     * Every response, including 401/403, is {@code Cache-Control: no-store} because the data is personal.
     * CSRF is not required: bearer tokens travel in the Authorization header, which browsers cannot
     * forge cross-site; ignoringRequestMatchers keeps CodeQL satisfied without calling disable().
     */
    @Bean
    @Order(1)
    public SecurityFilterChain netmonApiSecurityFilterChain(HttpSecurity http, NetmonProperties properties,
                                                            JsonMapper jsonMapper) {
        AuthorizationManager<RequestAuthorizationContext> netmonAccess = AuthorizationManagers.allOf(
                AuthorityAuthorizationManager.hasAuthority(NETMON_READ),
                new AllowedClientAuthorizationManager(properties.api().allowedClients()));
        ProblemJsonAuthenticationEntryPoint entryPoint = new ProblemJsonAuthenticationEntryPoint(jsonMapper);
        ProblemJsonAccessDeniedHandler accessDeniedHandler = new ProblemJsonAccessDeniedHandler(jsonMapper);

        http
                .securityMatcher("/api/netmon/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().access(netmonAccess))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(rs -> rs
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .headers(headers -> headers
                        .cacheControl(cache -> cache.disable())
                        .addHeaderWriter(new StaticHeadersWriter("Cache-Control", "no-store"))
                        .addHeaderWriter(new StaticHeadersWriter("Pragma", "no-cache")))
                .addFilterBefore(new TokenValidationUnavailableFilter(jsonMapper), BearerTokenAuthenticationFilter.class)
                .csrf(csrf -> csrf.ignoringRequestMatchers(request -> true));
        return http.build();
    }

    /**
     * Chain 2: actuator. Health and info are public for probes; prometheus is public for the
     * in-cluster ServiceMonitor scrape (docs/060 §12 Q9). data-service has no tunnel route, so none of
     * these is reachable from the internet. Everything else is denied.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain actuatorSecurityFilterChain(HttpSecurity http) {
        http
                .securityMatcher("/actuator/**")
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info",
                                "/actuator/prometheus").permitAll()
                        .anyRequest().denyAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    /**
     * Chain 999: catch-all — deny everything not matched above. Boot renders errors by forwarding to
     * {@code /error} as an ERROR dispatch, which re-enters the filter chain; permitting only that
     * dispatch type (not the {@code /error} path) keeps error bodies intact without exposing
     * {@code /error} directly (same lesson as auth-service#77).
     */
    @Bean
    @Order(999)
    public SecurityFilterChain catchAllSecurityFilterChain(HttpSecurity http) {
        http
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .anyRequest().denyAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.ignoringRequestMatchers("/error"));
        return http.build();
    }

    /**
     * The auth-service converter pattern (docs/060 §7.5): emits the default {@code SCOPE_*} authorities
     * from {@code scope}/{@code scp} plus {@code ROLE_*} from auth-service's {@code role} claim. Do not
     * replace it with a role-only converter: dropping {@code SCOPE_*} would make {@code netmon:read}
     * never match. Both converters return an empty collection when their claim is absent.
     */
    static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter scopesConverter = new JwtGrantedAuthoritiesConverter();

        JwtGrantedAuthoritiesConverter rolesConverter = new JwtGrantedAuthoritiesConverter();
        rolesConverter.setAuthoritiesClaimName("role");
        rolesConverter.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<GrantedAuthority> authorities = new ArrayList<>();
            authorities.addAll(scopesConverter.convert(jwt));
            authorities.addAll(rolesConverter.convert(jwt));
            return authorities;
        });
        return converter;
    }
}
