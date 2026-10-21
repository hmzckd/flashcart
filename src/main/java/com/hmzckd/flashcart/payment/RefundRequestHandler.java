package com.hmzckd.flashcart.payment;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.hmzckd.flashcart.order.RefundRequested;
import com.hmzckd.flashcart.order.RefundService;
import com.hmzckd.flashcart.outbox.DeliveryFailedException;
import com.hmzckd.flashcart.outbox.OutboxMessageHandler;
import com.hmzckd.flashcart.outbox.RetryPolicy;

import tools.jackson.databind.json.JsonMapper;

@Component
class RefundRequestHandler implements OutboxMessageHandler {

    private final RestClient restClient;
    private final RefundService refundService;
    private final JsonMapper jsonMapper;

    RefundRequestHandler(@Value("${flashcart.payment.provider-url}") String providerUrl,
            @Value("${flashcart.payment.api-key}") String apiKey,
            @Value("${flashcart.payment.provider-timeout}") Duration timeout,
            RefundService refundService, JsonMapper jsonMapper) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeout).build());
        requestFactory.setReadTimeout(timeout);
        this.restClient = RestClient.builder()
                .baseUrl(providerUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .requestFactory(requestFactory)
                .build();
        this.refundService = refundService;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public String type() {
        return RefundRequested.TYPE;
    }

    @Override
    public RetryPolicy retryPolicy() {
        return RetryPolicy.GIVE_UP_AFTER_FIVE_ATTEMPTS;
    }

    @Override
    public void send(String payload) {
        RefundRequested refundRequest = read(payload);
        try {
            restClient.post()
                    .uri("/refunds")
                    .header("Idempotency-Key", "refund-" + refundRequest.refundId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (request, response) -> {
                        throw new RestClientException("Payment provider answered " + response.getStatusCode());
                    })
                    .toBodilessEntity();
        } catch (RestClientException ex) {
            throw new DeliveryFailedException("Payment provider did not accept refund " + refundRequest.refundId(), ex);
        }
        refundService.markSent(refundRequest.refundId());
    }

    @Override
    public void giveUp(String payload) {
        refundService.markFailed(read(payload).refundId());
    }

    private RefundRequested read(String payload) {
        return jsonMapper.readValue(payload, RefundRequested.class);
    }
}
