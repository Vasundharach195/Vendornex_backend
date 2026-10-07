package com.ikyam.vendornex.config;

import com.ikyam.vendornex.security.CurrentUser;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger UI at /swagger-ui.html, OpenAPI JSON at /v3/api-docs.
 * Click "Authorize" in the UI and paste the JWT from POST /api/auth/login (without the "Bearer " prefix).
 */
@Configuration
public class SwaggerConfig {

    private static final String BEARER = "bearerAuth";

    static {
        // CurrentUser is filled in by CurrentUserArgumentResolver from the token, never sent by the caller.
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(CurrentUser.class);
    }

    @Bean
    public OpenAPI vendornexOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Ikyam VendorNex API")
                        .description("VendorNex APIs")
                        .version("1.0.0")
                        .contact(new Contact().name("Vasundhara CH").url("https://ikyam.com/").email("vasundhara.c@ikyam.com"))
                        .license(new License().name("Ikyam")))
                .components(new Components().addSecuritySchemes(BEARER,
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
