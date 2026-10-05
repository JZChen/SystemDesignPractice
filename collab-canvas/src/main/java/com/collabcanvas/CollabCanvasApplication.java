package com.collabcanvas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CollabCanvasApplication {

    public static void main(String[] args) {
        SpringApplication.run(CollabCanvasApplication.class, args);
    }
}
