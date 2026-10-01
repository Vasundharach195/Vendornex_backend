package com.ikyam.vendornex.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ikyam.vendornex.http.Json;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Makes Spring MVC's request/response JSON conversion use the exact existing Jackson
 * config (plain BigDecimal, toString() for LocalDate/Instant/UUID, lenient unknown
 * properties) instead of Spring Boot's own Jackson auto-configuration.
 */
@Configuration
public class JacksonConfig {

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        return Json.MAPPER;
    }
}
