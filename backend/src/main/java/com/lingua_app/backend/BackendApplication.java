package com.lingua_app.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// @SpringBootApplication is shorthand for three annotations combined:
//   @Configuration       — marks this class as a source of Spring bean definitions
//   @EnableAutoConfiguration — tells Spring Boot to auto-configure beans based on classpath dependencies
//   @ComponentScan       — scans this package (and sub-packages) for @Component, @Service, @Repository, etc.
@SpringBootApplication
public class BackendApplication {

	public static void main(String[] args) {
		// Bootstraps the entire Spring context, starts the embedded Tomcat server,
		// runs Flyway migrations, and begins accepting HTTP requests.
		SpringApplication.run(BackendApplication.class, args);
	}

}
