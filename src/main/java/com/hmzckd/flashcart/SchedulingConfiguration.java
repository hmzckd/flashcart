package com.hmzckd.flashcart;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnBooleanProperty("flashcart.scheduling.enabled")
class SchedulingConfiguration {
}
