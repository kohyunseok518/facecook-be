package com.facecook.push.service;

import com.facecook.push.config.VapidProperties;
import com.facecook.push.entity.PushSubscription;
import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.apache.http.util.EntityUtils;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.Security;
import java.time.Duration;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * VAPID 웹 푸시 발송. 요청 서명·암호화는 web-push 라이브러리({@link PushService#preparePost})에
 * 맡기고, 전송은 제한 시간이 걸린 HTTP 클라이언트 하나로 직접 한다.
 *
 * <p>라이브러리의 {@code send}는 발송마다 제한 시간 없는 클라이언트를 새로 만든다. 푸시
 * 서버가 응답하지 않으면 pushExecutor 스레드가 전부 묶이고 대기열이 찬 뒤 알림이
 * 거절된다(facecook-be#113). 여기서는 연결·응답 제한 시간을 넘기면 실패로 끝내 스레드를
 * 돌려준다.</p>
 */
@Component
public class VapidWebPushGateway implements WebPushGateway, DisposableBean {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(5);
    /** pushExecutor 최대 스레드 수(8)만큼은 같은 푸시 서버로 동시에 보낼 수 있게 한다. 기본값은 2다. */
    private static final int MAX_CONNECTIONS_PER_ROUTE = 8;
    private static final int MAX_CONNECTIONS_TOTAL = 24;

    private final VapidProperties vapidProperties;
    private final Duration connectTimeout;
    private final Duration responseTimeout;
    private final CloseableHttpAsyncClient httpClient;
    private volatile PushService pushService;

    @Autowired
    public VapidWebPushGateway(VapidProperties vapidProperties) {
        this(vapidProperties, CONNECT_TIMEOUT, RESPONSE_TIMEOUT);
    }

    VapidWebPushGateway(VapidProperties vapidProperties, Duration connectTimeout, Duration responseTimeout) {
        this.vapidProperties = vapidProperties;
        this.connectTimeout = connectTimeout;
        this.responseTimeout = responseTimeout;
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Math.toIntExact(connectTimeout.toMillis()))
                .setConnectionRequestTimeout(Math.toIntExact(connectTimeout.toMillis()))
                .setSocketTimeout(Math.toIntExact(responseTimeout.toMillis()))
                .build();
        // 라이브러리가 쓰던 createSystem()처럼 시스템 프록시 설정 등은 그대로 따른다.
        this.httpClient = HttpAsyncClients.custom()
                .useSystemProperties()
                .setDefaultRequestConfig(requestConfig)
                .setMaxConnPerRoute(MAX_CONNECTIONS_PER_ROUTE)
                .setMaxConnTotal(MAX_CONNECTIONS_TOTAL)
                .build();
        this.httpClient.start();
    }

    /**
     * subscription으로 payload를 암호화해 보낸다.
     *
     * <p>예외: 연결·응답이 제한 시간을 넘기면 {@link TimeoutException}(또는 소켓 제한
     * 시간 초과를 감싼 {@link java.util.concurrent.ExecutionException}). 호출부
     * {@link PushDeliveryService}가 발송 실패로 처리하고 구독은 지우지 않는다.</p>
     */
    @Override
    public PushDeliveryResult send(PushSubscription subscription, String payload) throws Exception {
        // Notification도 구독 공개키를 읽을 때 BC를 사용하므로 먼저 제공자를 등록한다.
        PushService configuredPushService = pushService();
        Notification notification = new Notification(
                subscription.getEndpoint(),
                subscription.getP256dh(),
                subscription.getAuth(),
                payload
        );
        HttpPost request = configuredPushService.preparePost(notification, Encoding.AES128GCM);
        Future<HttpResponse> pending = httpClient.execute(request, null);
        HttpResponse response;
        try {
            // 소켓 제한 시간은 "데이터가 안 오는 시간"이라 조금씩 오래 끌면 안 걸린다.
            // 전체 대기에도 상한을 둬서 어떤 경우에도 스레드가 돌아오게 한다.
            response = pending.get(connectTimeout.plus(responseTimeout).toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            pending.cancel(true);
            throw exception;
        }
        EntityUtils.consumeQuietly(response.getEntity());
        return new PushDeliveryResult(
                response.getStatusLine().getStatusCode(),
                response.getStatusLine().getReasonPhrase()
        );
    }

    @Override
    public void destroy() throws IOException {
        httpClient.close();
    }

    private PushService pushService() throws GeneralSecurityException {
        PushService current = pushService;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (pushService == null) {
                validateProperties();
                if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                    Security.addProvider(new BouncyCastleProvider());
                }
                pushService = new PushService(vapidProperties.publicKey(), vapidProperties.privateKey());
            }
            return pushService;
        }
    }

    private void validateProperties() {
        if (vapidProperties.publicKey().isBlank() || vapidProperties.privateKey().isBlank()) {
            throw new IllegalStateException("VAPID 공개키와 비밀키가 설정되지 않았습니다.");
        }
    }
}
