package com.hmzckd.flashcart.order;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundRepository extends JpaRepository<Refund, Long> {

    List<Refund> findByOrderIdOrderByIdAsc(Long orderId);
}
