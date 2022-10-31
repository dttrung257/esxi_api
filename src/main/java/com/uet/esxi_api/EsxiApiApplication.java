package com.uet.esxi_api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;

@SpringBootApplication(exclude = SecurityAutoConfiguration.class)
public class EsxiApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(EsxiApiApplication.class, args);
	}

}
