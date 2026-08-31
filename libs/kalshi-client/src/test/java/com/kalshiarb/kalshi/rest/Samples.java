package com.kalshiarb.kalshi.rest;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads the pinned real API responses from {@code docs/kalshi-api-snapshot/samples/}.
 *
 * <p>These are unedited captures from the live API. Parsing them in tests is what makes the DTOs
 * verified against reality rather than against the documentation, which is the point: the
 * fixed-point fields have inconsistent decimal places and the docs do not say so.
 */
final class Samples {

    /**
     * Mapper configured identically to {@link KalshiRestClient}'s.
     *
     * <p>Built by reflection-free duplication on purpose: if the client's configuration changes
     * and this does not, the sample tests stop reflecting what the client actually does, and the
     * resulting failure is the desired signal.
     */
    static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private Samples() {
    }

    static Path dir() {
        String configured = System.getProperty("kalshi.samples.dir");
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(
                    "system property kalshi.samples.dir is not set; the root build should supply "
                            + "it for every Test task");
        }
        return Path.of(configured);
    }

    static String read(String fileName) {
        Path path = dir().resolve(fileName);
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read sample " + path, e);
        }
    }

    static <T> T parse(String fileName, Class<T> type) {
        try {
            return MAPPER.readValue(read(fileName), type);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "sample " + fileName + " did not parse as " + type.getSimpleName(), e);
        }
    }
}
