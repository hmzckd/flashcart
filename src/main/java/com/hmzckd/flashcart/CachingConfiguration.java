package com.hmzckd.flashcart;

import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.LoggingCacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;

@Configuration(proxyBeanMethods = false)
@EnableCaching
class CachingConfiguration implements CachingConfigurer {

    @Bean
    RedisCacheManagerBuilderCustomizer immediateCacheWrites(RedisConnectionFactory connectionFactory) {
        return builder -> builder.cacheWriter(
                RedisCacheWriter.create(connectionFactory, writer -> writer.immediateWrites()));
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return new LoggingCacheErrorHandler();
    }
}
