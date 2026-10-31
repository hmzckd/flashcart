package com.hmzckd.flashcart.product;

import java.util.List;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

@Service
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Cacheable(cacheNames = ProductCacheConfiguration.PRODUCTS, key = "'all'")
    public List<ProductResponse> listProducts() {
        return productRepository.findAll(Sort.by("id"))
                .stream()
                .map(ProductResponse::from)
                .toList();
    }
}
