package com.hackerton;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class HackertonApplication {

	public static void main(String[] args) {
		SpringApplication.run(HackertonApplication.class, args);
	}

}
