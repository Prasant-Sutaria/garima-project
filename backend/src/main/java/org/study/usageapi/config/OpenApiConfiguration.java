package org.study.usageapi.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {

    @Bean
    OpenAPI usageApiDescription() {
        return new OpenAPI()
                .info(new Info()
                        .title("Usage Publisher API")
                        .version("v1")
                        .description("Local/test contract for the mobile usage publisher and study administrators. "
                                + "Production KMS, TLS, and staff identity-provider integration are not configured."))
                .components(new Components().addSecuritySchemes("deviceBearer",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP)
                                .scheme("bearer").bearerFormat("JWT"))
                        .addSecuritySchemes("adminBasic",
                                new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("basic")));
    }

    @Bean
    GroupedOpenApi appApi() {
        return GroupedOpenApi.builder().group("app")
                .pathsToMatch("/api/v1/registrations", "/api/v1/devices/**", "/api/v1/auth/**", "/api/v1/usage/**",
                        "/api/v1/health", "/.well-known/jwks.json")
                .addOpenApiCustomizer(jsonResponseContentType())
                .build();
    }

    @Bean
    GroupedOpenApi adminApi() {
        return GroupedOpenApi.builder().group("admin")
                .pathsToMatch("/api/v1/admin/**")
                .addOpenApiCustomizer(jsonResponseContentType())
                .build();
    }

    private OpenApiCustomizer jsonResponseContentType() {
        return openApi -> openApi.getPaths().values().forEach(pathItem ->
                pathItem.readOperations().forEach(operation -> operation.getResponses().values().forEach(response -> {
                    if (response.getContent() != null && response.getContent().containsKey("*/*")) {
                        MediaType content = response.getContent().remove("*/*");
                        response.getContent().addMediaType("application/json", content);
                    }
                })));
    }
}
