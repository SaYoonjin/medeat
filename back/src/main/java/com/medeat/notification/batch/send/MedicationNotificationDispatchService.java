package com.medeat.notification.batch.send;

import com.medeat.notification.batch.outbox.MedicationNotificationSendCandidate;
import com.medeat.notification.dto.PushSubscriptionDto;
import com.medeat.notification.service.PushSubscriptionService;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.apache.http.HttpResponse;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

@Service
public class MedicationNotificationDispatchService {

    private final PushService pushService;
    private final PushSubscriptionService pushSubscriptionService;

    public MedicationNotificationDispatchService(
            PushService pushService,
            PushSubscriptionService pushSubscriptionService
    ) {
        this.pushService = pushService;
        this.pushSubscriptionService = pushSubscriptionService;
    }

    public WebPushDispatchResult dispatch(MedicationNotificationSendCandidate candidate) {
        if (!pushSubscriptionService.isPushEnabled(candidate.userId())) {
            return new WebPushDispatchResult(0, 0, 0, 0, List.of());
        }

        List<PushSubscriptionDto> subscriptions = pushSubscriptionService.getByUserId(candidate.userId());
        if (subscriptions == null || subscriptions.isEmpty()) {
            return new WebPushDispatchResult(0, 0, 0, 0, List.of());
        }

        int successCount = 0;
        int retryableFailureCount = 0;
        int terminalFailureCount = 0;
        List<WebPushFailure> failures = new ArrayList<>();
        byte[] payloadBytes = payload(candidate).getBytes(StandardCharsets.UTF_8);

        for (PushSubscriptionDto subscription : subscriptions) {
            WebPushFailure validationFailure = validate(subscription);
            if (validationFailure != null) {
                terminalFailureCount++;
                failures.add(validationFailure);
                continue;
            }

            try {
                Notification notification = new Notification(
                        subscription.getEndpoint(),
                        subscription.getP256dh(),
                        subscription.getAuth(),
                        payloadBytes
                );
                HttpResponse response = pushService.send(notification);
                int statusCode = response.getStatusLine().getStatusCode();
                if (statusCode >= 200 && statusCode < 300) {
                    successCount++;
                } else if (isTerminalStatus(statusCode)) {
                    terminalFailureCount++;
                    failures.add(failure(subscription, "WEB_PUSH_TERMINAL_" + statusCode,
                            "WebPush terminal response status: " + statusCode, false));
                } else {
                    retryableFailureCount++;
                    failures.add(failure(subscription, "WEB_PUSH_RETRYABLE_" + statusCode,
                            "WebPush retryable response status: " + statusCode, true));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                retryableFailureCount++;
                failures.add(failure(subscription, "WEB_PUSH_INTERRUPTED", e.getMessage(), true));
            } catch (IOException | ExecutionException e) {
                retryableFailureCount++;
                failures.add(failure(subscription, "WEB_PUSH_TEMPORARY_ERROR", e.getMessage(), true));
            } catch (GeneralSecurityException | org.jose4j.lang.JoseException | IllegalArgumentException e) {
                terminalFailureCount++;
                failures.add(failure(subscription, "WEB_PUSH_INVALID_SUBSCRIPTION", e.getMessage(), false));
            } catch (Exception e) {
                retryableFailureCount++;
                failures.add(failure(subscription, "WEB_PUSH_UNEXPECTED_ERROR", e.getMessage(), true));
            }
        }

        return new WebPushDispatchResult(
                subscriptions.size(),
                successCount,
                retryableFailureCount,
                terminalFailureCount,
                List.copyOf(failures)
        );
    }

    private boolean isTerminalStatus(int statusCode) {
        return statusCode == 400
                || statusCode == 401
                || statusCode == 403
                || statusCode == 404
                || statusCode == 410;
    }

    private WebPushFailure validate(PushSubscriptionDto subscription) {
        if (subscription.getEndpoint() == null || subscription.getEndpoint().isBlank()) {
            return failure(subscription, "INVALID_SUBSCRIPTION_ENDPOINT", "Subscription endpoint is missing", false);
        }
        if (subscription.getP256dh() == null || subscription.getP256dh().isBlank()) {
            return failure(subscription, "INVALID_SUBSCRIPTION_KEY", "Subscription p256dh is missing", false);
        }
        if (subscription.getAuth() == null || subscription.getAuth().isBlank()) {
            return failure(subscription, "INVALID_SUBSCRIPTION_AUTH", "Subscription auth is missing", false);
        }
        return null;
    }

    private String payload(MedicationNotificationSendCandidate candidate) {
        if (candidate.payloadJson() != null && !candidate.payloadJson().isBlank()) {
            return candidate.payloadJson();
        }
        return "{\"title\":\"" + escape(candidate.notificationTitle()) + "\",\"body\":\""
                + escape(candidate.notificationBody()) + "\"}";
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private WebPushFailure failure(
            PushSubscriptionDto subscription,
            String failureCode,
            String failureReason,
            boolean retryable
    ) {
        return new WebPushFailure(subscription.getId(), failureCode, failureReason, retryable);
    }
}
