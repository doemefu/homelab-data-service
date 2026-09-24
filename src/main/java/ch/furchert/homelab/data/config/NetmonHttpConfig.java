package ch.furchert.homelab.data.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * The HTTP client for upstream calls (Cloudflare, blocklists, AbuseIPDB): 10 s connect and read
 * timeouts (docs/060 §4.2). Deliberately a plain {@link RestClient#builder()} rather than Boot's
 * observed builder: HTTP client observations tag the request URI, and an AbuseIPDB check URI carries
 * the looked-up IP, which must never become a metric label (§7.1, §10).
 */
@Configuration(proxyBeanMethods = false)
public class NetmonHttpConfig {

    static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Bean
    public RestClient netmonRestClient() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(TIMEOUT);
        return RestClient.builder().requestFactory(factory).build();
    }
}
