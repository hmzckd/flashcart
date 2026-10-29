package com.hmzckd.flashcart.order;

import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "flashcart.stock-locking=optimistic")
class OptimisticOrderIdempotencyTests extends OrderIdempotencyTests {
}
