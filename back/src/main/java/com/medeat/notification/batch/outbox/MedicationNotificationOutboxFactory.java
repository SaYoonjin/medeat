package com.medeat.notification.batch.outbox;

import com.medeat.medical.dto.MedicationDto;
import com.medeat.notification.batch.schedule.ScheduledDose;
import org.springframework.stereotype.Component;

@Component
public class MedicationNotificationOutboxFactory {

    private final MedicationNotificationPayloadFactory payloadFactory;

    public MedicationNotificationOutboxFactory(MedicationNotificationPayloadFactory payloadFactory) {
        this.payloadFactory = payloadFactory;
    }

    public MedicationNotificationOutboxCommand create(
            MedicationDto medication,
            ScheduledDose dose,
            Long originJobExecutionId
    ) {
        MedicationNotificationPayload payload = payloadFactory.create(medication, dose);
        return new MedicationNotificationOutboxCommand(
                dose.userId(),
                dose.medicationId(),
                dose.scheduledAt(),
                dose.doseSequence(),
                payload.title(),
                payload.body(),
                payload.payloadJson(),
                originJobExecutionId
        );
    }
}
