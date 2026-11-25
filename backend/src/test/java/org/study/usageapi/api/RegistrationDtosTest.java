package org.study.usageapi.api;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistrationDtosTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private static RegistrationDtos.SelfRegistrationRequest withProfession(String profession) {
        return new RegistrationDtos.SelfRegistrationRequest("Example", "+14155550123",
                "person@example.test", 25, "test", "Reading", "Walking", 6, profession);
    }

    @Test
    void acceptsOnlyTheThreeProfessionCodes() {
        for (String profession : new String[]{"student", "working_professional", "homemaker"}) {
            assertTrue(validator.validate(withProfession(profession)).isEmpty(), profession);
        }
        for (String profession : new String[]{null, "", "Student", "working professional", "retired"}) {
            var violations = validator.validate(withProfession(profession));
            assertEquals(1, violations.size(), String.valueOf(profession));
            assertEquals("profession", violations.iterator().next().getPropertyPath().toString());
        }
    }
}
