package com.qualitygate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class QualityGateApplication {

    public static void main(String[] args) {
        SpringApplication.run(QualityGateApplication.class, args);
    }
}
