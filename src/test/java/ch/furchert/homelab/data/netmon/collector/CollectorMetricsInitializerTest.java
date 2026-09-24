package ch.furchert.homelab.data.netmon.collector;

import ch.furchert.homelab.data.config.NetmonProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Startup gauge registration: only collectors that are enabled, available and able to succeed (docs/060 §4.1). */
class CollectorMetricsInitializerTest {

    @Test
    void registersGaugesOnlyForCollectorsThatCanSucceed() {
        CollectorStateRepository repository = mock(CollectorStateRepository.class);
        when(repository.findAll()).thenReturn(List.of());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ok", collector("ok", true, true));
        beans.addBean("switchedOff", collector("switched-off", true, true));
        beans.addBean("noKey", collector("no-key", false, true));
        beans.addBean("noCredentials", collector("no-credentials", true, false));
        ObjectProvider<NetmonCollector> provider = beans.getBeanProvider(NetmonCollector.class);
        NetmonProperties properties = new NetmonProperties(new NetmonProperties.Api(List.of("furchert-ch")),
                Map.of("switched-off", new NetmonProperties.Collector(false)));

        new CollectorMetricsInitializer(provider, repository, new CollectorMetrics(registry), properties).registerGauges();

        assertThat(registry.find(CollectorMetrics.GAUGE).gauges())
                .extracting(g -> g.getId().getTag("collector"))
                .containsExactly("ok");
        assertThat(registry.get(CollectorMetrics.GAUGE).tag("collector", "ok").gauge().value()).isNaN();
    }

    private static NetmonCollector collector(String name, boolean available, boolean exportsGauge) {
        return new NetmonCollector() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Duration cadence() {
                return Duration.ofMinutes(5);
            }

            @Override
            public boolean available() {
                return available;
            }

            @Override
            public boolean exportsFreshnessGauge() {
                return exportsGauge;
            }

            @Override
            public void collect() {
            }
        };
    }
}
