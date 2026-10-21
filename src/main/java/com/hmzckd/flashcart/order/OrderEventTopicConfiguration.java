package com.hmzckd.flashcart.order;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration(proxyBeanMethods = false)
class OrderEventTopicConfiguration {

    @Bean
    NewTopic orderEventsTopic() {
        return TopicBuilder.name(OrderEvent.TOPIC).partitions(3).build();
    }
}
