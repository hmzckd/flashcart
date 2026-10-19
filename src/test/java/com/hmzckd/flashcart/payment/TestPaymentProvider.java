package com.hmzckd.flashcart.payment;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

public class TestPaymentProvider {

    private final String secret;

    public TestPaymentProvider(String secret) {
        this.secret = secret;
    }

    public MockHttpServletRequestBuilder paymentSucceeded(long orderId, BigDecimal amount) {
        return event(newEventId(), "payment.succeeded", orderId, amount);
    }

    public MockHttpServletRequestBuilder event(String eventId, String type, long orderId, BigDecimal amount) {
        return signed("""
                {"id": "%s", "type": "%s", "orderId": %d, "amount": %s}
                """.formatted(eventId, type, orderId, amount.toPlainString()));
    }

    public MockHttpServletRequestBuilder signed(String body) {
        return post("/api/payments/events")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Payment-Signature", sign(body))
                .content(body);
    }

    public static String newEventId() {
        return "evt_" + UUID.randomUUID();
    }

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
