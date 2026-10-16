package com.hmzckd.flashcart;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.resilience.annotation.EnableResilientMethods;

@SpringBootApplication
@EnableResilientMethods
public class FlashcartApplication {

	public static void main(String[] args) {
		SpringApplication.run(FlashcartApplication.class, args);
	}

}
