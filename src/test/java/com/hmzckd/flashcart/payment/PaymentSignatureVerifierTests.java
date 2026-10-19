package com.hmzckd.flashcart.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class PaymentSignatureVerifierTests {

    private static final byte[] BODY = """
            {"id":"evt_1","type":"payment.succeeded","orderId":1,"amount":300.00}"""
            .getBytes(StandardCharsets.UTF_8);
    private static final String SIGNATURE_WITH_TEST_SECRET =
            "89c95247c1e738eab4569b47ab89bb975b7ad045034c291359bed1ec8dfa45dd";
    private static final String SIGNATURE_WITH_ANOTHER_SECRET =
            "d5175b3000a64c4f9aec16a28428b742454a959fe28927ed1707b8f8e7111fbc";

    private final PaymentSignatureVerifier signatureVerifier = new PaymentSignatureVerifier("test-secret");

    @Test
    void signatureMadeWithTheSharedSecretIsValid() {
        assertThat(signatureVerifier.isValid(BODY, SIGNATURE_WITH_TEST_SECRET)).isTrue();
    }

    @Test
    void signatureMadeWithAnotherSecretIsInvalid() {
        assertThat(signatureVerifier.isValid(BODY, SIGNATURE_WITH_ANOTHER_SECRET)).isFalse();
    }

    @Test
    void changedBodyMakesTheSignatureInvalid() {
        byte[] changedBody = """
                {"id":"evt_1","type":"payment.succeeded","orderId":1,"amount":3.00}"""
                .getBytes(StandardCharsets.UTF_8);

        assertThat(signatureVerifier.isValid(changedBody, SIGNATURE_WITH_TEST_SECRET)).isFalse();
    }

    @Test
    void missingSignatureIsInvalid() {
        assertThat(signatureVerifier.isValid(BODY, null)).isFalse();
    }
}
