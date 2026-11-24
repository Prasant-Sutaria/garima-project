package org.study.usageapi.service;

import java.util.UUID;

public record ParticipantPrincipal(String registrationId, UUID deviceId) {
}
