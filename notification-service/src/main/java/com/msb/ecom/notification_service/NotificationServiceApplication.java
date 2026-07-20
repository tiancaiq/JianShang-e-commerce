package com.msb.ecom.notification_service;

import com.msb.ecom.notification_service.config.NotificationConsumerProperties;
import com.msb.ecom.notification_service.config.NotificationReadProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
		NotificationConsumerProperties.class,
		NotificationReadProperties.class
})
public class NotificationServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(NotificationServiceApplication.class, args);
	}

}
