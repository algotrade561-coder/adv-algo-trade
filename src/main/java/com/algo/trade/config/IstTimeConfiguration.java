package com.algo.trade.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IstTimeConfiguration {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter IST_INSTANT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").withZone(IST);

    @Bean
    Jackson2ObjectMapperBuilderCustomizer istJacksonCustomizer() {
        return builder -> {
            builder.timeZone("Asia/Kolkata");
            builder.modulesToInstall(istJavaTimeModule());
        };
    }

    public static JavaTimeModule istJavaTimeModule() {
        JavaTimeModule module = new JavaTimeModule();
        module.addSerializer(Instant.class, new com.fasterxml.jackson.databind.JsonSerializer<>() {
            @Override
            public void serialize(Instant value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                if (value == null) {
                    gen.writeNull();
                    return;
                }
                gen.writeString(IST_INSTANT_FORMAT.format(value));
            }
        });
        return module;
    }

    public static SimpleModule istModule() {
        return istJavaTimeModule();
    }
}
