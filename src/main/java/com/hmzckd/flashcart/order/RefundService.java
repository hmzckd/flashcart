package com.hmzckd.flashcart.order;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.hmzckd.flashcart.outbox.Outbox;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class RefundService {

    private final RefundRepository refundRepository;
    private final Outbox outbox;

    public RefundService(RefundRepository refundRepository, Outbox outbox) {
        this.refundRepository = refundRepository;
        this.outbox = outbox;
    }

    public void request(Refund refund) {
        refundRepository.save(refund);
        outbox.add(RefundRequested.TYPE, "refund-" + refund.getId(), RefundRequested.from(refund));
    }

    public void markSent(Long refundId) {
        findRefund(refundId).markSent();
    }

    public void markFailed(Long refundId) {
        findRefund(refundId).markFailed();
    }

    private Refund findRefund(Long refundId) {
        return refundRepository.findById(refundId).orElseThrow();
    }
}
