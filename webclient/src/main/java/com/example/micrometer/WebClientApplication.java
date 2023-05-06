package com.example.micrometer;

import static reactor.netty.Metrics.*;
import static reactor.netty.Metrics.REGISTRY;
import static reactor.netty.resources.ConnectionProvider.DEFAULT_POOL_MAX_CONNECTIONS;

import io.micrometer.common.lang.NonNull;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import io.micrometer.tracing.Tracer;

import java.net.SocketAddress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionPoolMetrics;
import reactor.netty.resources.ConnectionProvider;

@SpringBootApplication
public class WebClientApplication implements CommandLineRunner {

    public static void main(String... args) {
        new SpringApplicationBuilder(WebClientApplication.class).web(WebApplicationType.NONE).run(args);
    }

    @Autowired
    WebClientService webClientService;

    @Override
    public void run(String... args) throws Exception {
        int i = 0;
        while (true) {
            String result = this.webClientService.call().block();
            System.out.println(i);
            System.out.println("Got response len: " + result.length());
            i +=1;
        }
    }

}

@Configuration
class Config {

    private static final int DEFAULT_POOL_MAX_CONNECTIONS = 50;
    private static final long DEFAULT_PENDING_POOL_ACQUIRE_TIMEOUT_MILLIS = 15000;
    private static final int DEFAULT_PENDING_POOL_ACQUIRE_MAX_COUNT = 1500;
    private static final long DEFAULT_MAX_IDLE_TIMEOUT_MILLIS = 300_000;
    private static final Duration CONNECTION_MAX_IDLE_TIME_MS = Duration.ofMillis(120_000); // 2 mins
    private static final Duration CONNECTION_MAX_LIFE_TIME_MS = Duration.ofMillis(180_000); // 3 mins

    // You must register WebClient as a bean!
    @Bean
    WebClient webClient(WebClient.Builder builder, @Value("${url:http://localhost:3000}") String url) {
        final int size = 16 * 1024 * 1024;
        final ExchangeStrategies strategies = ExchangeStrategies.builder()
            .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(size))
            .build();

        final var httpClient =
            HttpClient.create(
                    createConnectionProviderBuilder()
                        .maxIdleTime(CONNECTION_MAX_IDLE_TIME_MS)
                        .maxLifeTime(CONNECTION_MAX_LIFE_TIME_MS)
                        .build())
                .compress(true);

        return builder.baseUrl(url).exchangeStrategies(strategies)
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .observationConvention(new SpotnanaWebClientClientRequestObservationConvention("abc")).build();
    }

    public ConnectionProvider.Builder createConnectionProviderBuilder() {
        return ConnectionProvider.builder("clientName.getName()")
            // Idle timeout is added to avoid the readAddress related connection issue that was observed
            // multiple times. See: https://spotnana.atlassian.net/browse/ST-23027
            .maxIdleTime(Duration.ofMillis(DEFAULT_MAX_IDLE_TIMEOUT_MILLIS))
            .maxConnections(DEFAULT_POOL_MAX_CONNECTIONS)
            .pendingAcquireTimeout(Duration.ofMillis(DEFAULT_PENDING_POOL_ACQUIRE_TIMEOUT_MILLIS))
            .pendingAcquireMaxCount(DEFAULT_PENDING_POOL_ACQUIRE_MAX_COUNT)
            .metrics(true, ConnectionProviderMeterRegistrar::new);
    }

}

/*
 * Customizes and controls the metrics and tags emitted on the NettyConnectionPool.
 */
 class ConnectionProviderMeterRegistrar
    implements ConnectionProvider.MeterRegistrar {
    @Override
    public void registerMetrics(
        @NonNull final String poolName,
        @NonNull final String id,
        @NonNull final SocketAddress remoteAddress,
        @NonNull final ConnectionPoolMetrics metrics) {
        var tags =
            new String[] {
                "server_name",
                reactor.netty.Metrics.formatSocketAddress(remoteAddress),
                "CLIENT_NAME",
                poolName
            };

        Gauge.builder(
                CONNECTION_PROVIDER_PREFIX + TOTAL_CONNECTIONS,
                metrics,
                ConnectionPoolMetrics::allocatedSize)
            .description("The number of all connections, active or idle.")
            .tags(tags)
            .register(REGISTRY);

        Gauge.builder(
                CONNECTION_PROVIDER_PREFIX + ACTIVE_CONNECTIONS,
                metrics,
                ConnectionPoolMetrics::acquiredSize)
            .description(
                "The number of the connections that have been successfully acquired and are in active"
                    + " use")
            .tags(tags)
            .register(REGISTRY);

        Gauge.builder(
                CONNECTION_PROVIDER_PREFIX + IDLE_CONNECTIONS,
                metrics,
                ConnectionPoolMetrics::idleSize)
            .description("The number of the idle connections")
            .tags(tags)
            .register(REGISTRY);

        Gauge.builder(
                CONNECTION_PROVIDER_PREFIX + PENDING_CONNECTIONS,
                metrics,
                ConnectionPoolMetrics::pendingAcquireSize)
            .description("The number of the request, that are pending acquire a connection")
            .tags(tags)
            .register(REGISTRY);
    }
}


@Service
class WebClientService {

    private static final String bodyStr = "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";
    private final String bigString;

    private static final Logger log = LoggerFactory.getLogger(WebClientService.class);

    private final WebClient webClient;

    private final Tracer tracer;

    private final ObservationRegistry observationRegistry;

    WebClientService(WebClient webClient, Tracer tracer, ObservationRegistry observationRegistry) {
        this.webClient = webClient;
        this.tracer = tracer;
        this.observationRegistry = observationRegistry;
        this.bigString = bodyStr.repeat(1000);

        System.out.println("String size " + bigString.length());
    }

    Mono<String> call() {
        Observation observation = Observation.start("webclient-sample", observationRegistry);
        return Mono.just(observation).flatMap(span -> {
            observation.scoped(() -> log.info("<ACCEPTANCE_TEST> <TRACE:{}> Hello from consumer",
                    this.tracer.currentSpan().context().traceId()));
            return this.webClient.post().uri("/echo").bodyValue(bigString).retrieve().bodyToMono(String.class);
        })
            .doFinally(signalType -> observation.stop())
            .contextWrite(context -> context.put(ObservationThreadLocalAccessor.KEY, observation));
    }

}
