package com.facecook.push.service;

import com.facecook.push.config.VapidProperties;
import com.facecook.push.entity.PushSubscription;
import com.sun.net.httpserver.HttpServer;
import nl.martijndwars.webpush.Utils;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.interfaces.ECPrivateKey;
import org.bouncycastle.jce.interfaces.ECPublicKey;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Provider;
import java.security.Security;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// BC는 JVM 전역 상태다. 다른 테스트와 동시에 실행하지 않고 매 테스트 뒤 원래 상태를 복원한다.
@Isolated
class VapidWebPushGatewayTest {

    private HttpServer pushServer;
    private VapidWebPushGateway gateway;

    private Provider originalProvider;
    private int originalProviderPosition;

    @BeforeEach
    void startWithoutRegisteredProvider() {
        Provider[] providers = Security.getProviders();
        for (int i = 0; i < providers.length; i++) {
            if (BouncyCastleProvider.PROVIDER_NAME.equals(providers[i].getName())) {
                originalProvider = providers[i];
                originalProviderPosition = i + 1;
                break;
            }
        }
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME);
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (gateway != null) {
                gateway.destroy();
            }
        } finally {
            try {
                if (pushServer != null) {
                    pushServer.stop(0);
                }
            } finally {
                Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME);
                if (originalProvider != null) {
                    Security.insertProviderAt(originalProvider, originalProviderPosition);
                }
            }
        }
    }

    @Test
    void firstSendWithoutRegisteredProviderEncryptsPayloadAndCallsPushEndpointWithVapidAuthorization() throws Exception {
        KeyPair vapidKeyPair = keyPair();
        KeyPair subscriberKeyPair = keyPair();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> contentEncoding = new AtomicReference<>();
        AtomicInteger bodySize = new AtomicInteger();

        pushServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        pushServer.createContext("/subscription", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            contentEncoding.set(exchange.getRequestHeaders().getFirst("Content-Encoding"));
            bodySize.set(exchange.getRequestBody().readAllBytes().length);
            exchange.sendResponseHeaders(201, -1);
            exchange.close();
        });
        pushServer.start();

        VapidProperties properties = new VapidProperties(
                encodePublicKey(vapidKeyPair),
                encodePrivateKey(vapidKeyPair)
        );
        gateway = new VapidWebPushGateway(properties);
        PushSubscription subscription = PushSubscription.create(
                2L,
                "http://127.0.0.1:" + pushServer.getAddress().getPort() + "/subscription",
                encodePublicKey(subscriberKeyPair),
                Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16])
        );

        assertThat(Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)).isNull();
        PushDeliveryResult result = gateway.send(subscription, "{\"title\":\"test\"}");

        assertThat(result.statusCode()).isEqualTo(201);
        assertThat(authorization.get()).startsWith("vapid t=");
        assertThat(contentEncoding.get()).isEqualTo("aes128gcm");
        assertThat(bodySize.get()).isPositive();
    }

    @Test
    void givesUpOnUnresponsivePushServerWithinTimeoutAndStaysUsable() throws Exception {
        KeyPair vapidKeyPair = keyPair();
        KeyPair subscriberKeyPair = keyPair();
        CountDownLatch releaseSlowResponse = new CountDownLatch(1);

        pushServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // 느린 요청이 서버를 막지 않게 요청마다 스레드를 따로 쓴다.
        pushServer.setExecutor(Executors.newCachedThreadPool());
        pushServer.createContext("/slow", exchange -> {
            exchange.getRequestBody().readAllBytes();
            try {
                releaseSlowResponse.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(201, -1);
            exchange.close();
        });
        pushServer.createContext("/fast", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(201, -1);
            exchange.close();
        });
        pushServer.start();

        gateway = new VapidWebPushGateway(
                new VapidProperties(encodePublicKey(vapidKeyPair), encodePrivateKey(vapidKeyPair)),
                Duration.ofMillis(500),
                Duration.ofMillis(300)
        );
        String base = "http://127.0.0.1:" + pushServer.getAddress().getPort();
        String p256dh = encodePublicKey(subscriberKeyPair);
        String auth = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]);

        try {
            long start = System.nanoTime();
            assertThatThrownBy(() -> gateway.send(PushSubscription.create(2L, base + "/slow", p256dh, auth), "{}"))
                    .isInstanceOfAny(TimeoutException.class, ExecutionException.class);
            long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

            // 서버는 10초 동안 응답하지 않는다. 제한 시간(연결 0.5초 + 응답 0.3초) 근처에서 끝나야 한다.
            assertThat(elapsedMs).isLessThan(2_000);

            PushDeliveryResult next = gateway.send(PushSubscription.create(2L, base + "/fast", p256dh, auth), "{}");
            assertThat(next.statusCode()).isEqualTo(201);
        } finally {
            releaseSlowResponse.countDown();
        }
    }

    private KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("ECDH", new BouncyCastleProvider());
        generator.initialize(ECNamedCurveTable.getParameterSpec("prime256v1"));
        return generator.generateKeyPair();
    }

    private String encodePublicKey(KeyPair keyPair) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Utils.encode((ECPublicKey) keyPair.getPublic()));
    }

    private String encodePrivateKey(KeyPair keyPair) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Utils.encode((ECPrivateKey) keyPair.getPrivate()));
    }
}
