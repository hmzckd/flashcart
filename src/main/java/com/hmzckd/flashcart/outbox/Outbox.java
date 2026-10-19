package com.hmzckd.flashcart.outbox;

import java.time.Clock;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

@Component
public class Outbox {

    private final OutboxMessageRepository outboxMessageRepository;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public Outbox(OutboxMessageRepository outboxMessageRepository, JsonMapper jsonMapper, Clock clock) {
        this.outboxMessageRepository = outboxMessageRepository;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void add(String type, Object payload) {
        outboxMessageRepository.save(
                new OutboxMessage(type, jsonMapper.writeValueAsString(payload), clock.instant()));
    }
}
