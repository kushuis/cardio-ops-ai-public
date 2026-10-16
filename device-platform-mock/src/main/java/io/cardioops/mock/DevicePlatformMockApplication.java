package io.cardioops.mock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Starts the synthetic, read-only MCP evidence service used by the public demo. */
@SpringBootApplication
public class DevicePlatformMockApplication {

    public static void main(String[] args) {
        SpringApplication.run(DevicePlatformMockApplication.class, args);
    }
}
