package com.msb.ecom.notification_service.service;

import com.msb.ecom.notification_service.config.NotificationConsumerProperties;
import com.msb.ecom.notification_service.model.NotificationConsumeResult;
import com.msb.ecom.notification_service.repository.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class OrderConfirmedNotificationConsumerTests {

    @Test
    void defaultOffReturnsBeforeParsingMetricsTransactionOrPersistence() {
        OrderConfirmedEventParser parser = mock(OrderConfirmedEventParser.class);
        NotificationRepository repository = mock(NotificationRepository.class);
        NotificationUlidGenerator ids = mock(NotificationUlidGenerator.class);
        NotificationMetrics metrics = mock(NotificationMetrics.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        var consumer = new OrderConfirmedNotificationConsumer(
                new NotificationConsumerProperties(
                        false,
                        "notification-service-order-confirmed-v2",
                        Duration.ofDays(180),
                        false),
                parser,
                repository,
                ids,
                metrics,
                transactions);

        NotificationConsumeResult result = consumer.consume("{not-json}");

        assertThat(result.outcome()).isEqualTo(NotificationConsumeResult.Outcome.DISABLED);
        assertThat(result.safeCode()).isEqualTo("NOTIFICATION_CONSUMER_DISABLED");
        verifyNoInteractions(parser, repository, ids, metrics, transactions);
    }
}
