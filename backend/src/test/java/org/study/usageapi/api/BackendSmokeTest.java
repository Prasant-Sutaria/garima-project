package org.study.usageapi.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.study.usageapi.service.SelfRegistrationRateLimitFilter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:usage-api-smoke;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "backend.crypto.keystore-password=local-smoke-test-password-123",
        "backend.admin.username=smoke-admin",
        "backend.admin.password=local-smoke-admin-password-123"
})
@ActiveProfiles("local")
@AutoConfigureMockMvc
class BackendSmokeTest {

    private static final Path KEYSTORE_PATH = Path.of(System.getProperty("java.io.tmpdir"),
            "usage-api-smoke-" + UUID.randomUUID() + ".p12");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void configureTestProperties(DynamicPropertyRegistry registry) {
        registry.add("backend.crypto.keystore-path", KEYSTORE_PATH::toString);
    }

    @AfterAll
    static void removeTemporaryKeystore() throws Exception {
        Files.deleteIfExists(KEYSTORE_PATH);
    }

    @Test
    void healthAndJwksArePublicAndOpenApiIsAvailable() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mockMvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].alg").value("ES256"))
                .andExpect(jsonPath("$.keys[0].kid").value("local-v1"));

        mockMvc.perform(get("/v3/api-docs/app"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("uploadSnapshot")));
    }

    @Test
    void adminEndpointRequiresSeparateAdministratorAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/admin/registrations")
                        .contentType("application/json")
                        .content("{\"registrations\":[]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthorized"));

        mockMvc.perform(get("/v3/api-docs/admin"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void selfRegistrationRateLimitsInvalidRequestsBeforeValidation() throws Exception {
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/v1/registrations")
                            .contentType("application/json")
                            .content("{}"))
                    .andExpect(status().isUnprocessableEntity());
        }
        mockMvc.perform(post("/api/v1/registrations")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.code").value("rate_limited"));
    }

    @Test
    void selfRegistrationIsDisabledWhenProductionProfileIsActive() {
        new ApplicationContextRunner()
                .withPropertyValues("spring.profiles.active=local,prod")
                .withUserConfiguration(SelfRegistrationController.class, SelfRegistrationRateLimitFilter.class)
                .run(context -> {
                    assertTrue(context.getBeansOfType(SelfRegistrationController.class).isEmpty());
                    assertTrue(context.getBeansOfType(SelfRegistrationRateLimitFilter.class).isEmpty());
                });
    }

    @Test
    void preservesSnapshotWindowOffsetsWhenReadingPayloads() throws Exception {
        UsageDtos.UsageWindow window = objectMapper.readValue(
                """
                {"start":"2026-11-20T00:00:00+05:30","end":"2026-11-20T23:55:00+05:30"}
                """, UsageDtos.UsageWindow.class);

        assertEquals(330, window.start().getOffset().getTotalSeconds() / 60);
        assertEquals(330, window.end().getOffset().getTotalSeconds() / 60);
    }

    @Test
    void exportsGroupedOpenApiContractsToBuildOutput() throws Exception {
        Path outputDirectory = Path.of("target", "openapi");
        Files.createDirectories(outputDirectory);
        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        for (String group : new String[]{"app", "admin"}) {
            var request = get("/v3/api-docs/" + group);
            if ("admin".equals(group)) {
                request = request.with(httpBasic("smoke-admin", "local-smoke-admin-password-123"));
            }
            MvcResult result = mockMvc.perform(request)
                    .andExpect(status().isOk())
                    .andReturn();
            var contract = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
            String contractText = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertTrue(contract.path("openapi").asText().startsWith("3.1"));
            if ("app".equals(group)) {
                assertTrue(contractText.contains("uploadSnapshot"));
                assertFalse(contractText.contains("adminImportRegistrations"));
            } else {
                assertTrue(contractText.contains("adminImportRegistrations"));
                assertFalse(contractText.contains("uploadSnapshot"));
            }
            String yaml = yamlMapper.writeValueAsString(contract);
            Files.writeString(outputDirectory.resolve("usage-api-" + group + ".yaml"), yaml);
            Path committedContract = Path.of("api", "openapi", "usage-api-" + group + ".yaml");
            assertEquals(Files.readString(committedContract), yaml,
                    "Committed OpenAPI contract drift for " + group);
        }
    }
}
