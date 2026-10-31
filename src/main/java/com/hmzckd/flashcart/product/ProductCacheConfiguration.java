package com.hmzckd.flashcart.product;

import java.util.List;

import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
class ProductCacheConfiguration {

    static final String PRODUCTS = "products";

    @Bean
    RedisCacheManagerBuilderCustomizer productListAsJson(JsonMapper jsonMapper) {
        JavaType productList = jsonMapper.getTypeFactory().constructCollectionType(List.class, ProductResponse.class);
        return builder -> builder.withCacheConfiguration(PRODUCTS, builder.cacheDefaults().serializeValuesWith(
                SerializationPair.fromSerializer(new JacksonJsonRedisSerializer<>(jsonMapper, productList))));
    }
}
