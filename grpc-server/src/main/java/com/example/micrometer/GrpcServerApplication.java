package com.example.micrometer;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.protobuf.SimpleRequest;
import io.grpc.testing.protobuf.SimpleResponse;
import io.grpc.testing.protobuf.SimpleServiceGrpc.SimpleServiceImplBase;
import io.micrometer.core.instrument.binder.grpc.ObservationGrpcServerInterceptor;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Hooks;

@SpringBootApplication
public class GrpcServerApplication {

    private static final Logger logger = LoggerFactory.getLogger(GrpcServerApplication.class);

    @Value("${server.port:9090}")
    int port;

    public static void main(String... args) {
        Hooks.enableAutomaticContextPropagation();
        new SpringApplicationBuilder(GrpcServerApplication.class).web(WebApplicationType.NONE).run(args);
    }

    @Bean
    public ObservationGrpcServerInterceptor interceptor(ObservationRegistry observationRegistry) {
        return new ObservationGrpcServerInterceptor(observationRegistry);
    }

    @Bean
    Server server(EchoService echoService, ObservationGrpcServerInterceptor interceptor) {
        return ServerBuilder.forPort(this.port).addService(echoService).intercept(interceptor).build();
    }

    @Bean
    InitializingBean startServer(Server server) {
        return () -> {
            logger.info("Running server in port={}", this.port);
            server.start();
            new Thread(() -> {
                try {
                    server.awaitTermination();
                }
                catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        };
    }

    @Bean
    DisposableBean stopServer(Server server) {
        return server::shutdownNow;
    }

    @Bean
    WebClient webClient(WebClient.Builder builder, @Value("${url:https://reqres.in/}") String url) {
        return builder.baseUrl(url).build();
    }

    // gRPC service extending SimpleService and provides echo implementation.
    @Service
    static class EchoService extends SimpleServiceImplBase {

        private static final Logger log = LoggerFactory.getLogger(EchoService.class);

        private final Tracer tracer;

        private final WebClient webClient;

        public EchoService(Tracer tracer, WebClient webClient) {
            this.tracer = tracer;
            this.webClient = webClient;
        }

        // echo the request message
        @Override
        public void unaryRpc(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            String message = request.getRequestMessage() + " from EchoService";
            SimpleResponse response = SimpleResponse.newBuilder().setResponseMessage(message).build();

            webClient.get()
                .uri("api/users")
                .retrieve()
                .bodyToMono(String.class)
                .doOnNext(abc -> {
                    log.info("Inside webclient thread.");
                    log.info("<ACCEPTANCE_TEST_GRPC+REACTIVE_WEBCLIENT> <TRACE:{}> Hello from producer",
                        this.tracer.currentSpan().context().traceId());
                })
                .block();

            responseObserver.onNext(response);
            // log it before onCompleted. The onCompleted triggers closing the span.
            log.info("<ACCEPTANCE_TEST> <TRACE:{}> Hello from producer", this.tracer.currentSpan().context().traceId());
            responseObserver.onCompleted();
        }

    }

}
