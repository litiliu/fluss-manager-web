package org.apache.fluss.manager;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FlussManagerApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlussManagerApplication.class, args);
    }
}
