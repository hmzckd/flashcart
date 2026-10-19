package com.hmzckd.flashcart.payment;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Validator;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/payments")
public class PaymentEventController {

    private final PaymentSignatureVerifier signatureVerifier;
    private final JsonMapper jsonMapper;
    private final Validator validator;
    private final PaymentService paymentService;

    public PaymentEventController(PaymentSignatureVerifier signatureVerifier, JsonMapper jsonMapper,
            Validator validator, PaymentService paymentService) {
        this.signatureVerifier = signatureVerifier;
        this.jsonMapper = jsonMapper;
        this.validator = validator;
        this.paymentService = paymentService;
    }

    @PostMapping("/events")
    public void receive(@RequestBody byte[] body,
            @RequestHeader(name = "Payment-Signature", required = false) String signature) {
        if (!signatureVerifier.isValid(body, signature)) {
            throw new InvalidPaymentSignatureException();
        }
        paymentService.handle(parse(body));
    }

    private PaymentEvent parse(byte[] body) {
        PaymentEvent event;
        try {
            event = jsonMapper.readValue(body, PaymentEvent.class);
        } catch (JacksonException ex) {
            throw new InvalidPaymentEventException();
        }
        if (event == null || !validator.validate(event).isEmpty()) {
            throw new InvalidPaymentEventException();
        }
        return event;
    }
}
