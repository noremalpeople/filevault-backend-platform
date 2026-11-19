package com.filevault;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

@SpringBootApplication
public class FileVaultApplication {
    public static void main(String[] args) {
        if (args.length == 1 && args[0].equals("--reconcile-storage")) {
            new SpringApplicationBuilder(FileVaultApplication.class)
                    .web(WebApplicationType.NONE)
                    .run("--filevault.reconcile-only=true", "--spring.flyway.enabled=false",
                            "--logging.level.root=ERROR", "--spring.main.banner-mode=off")
                    .close();
            return;
        }
        SpringApplication.run(FileVaultApplication.class, args);
    }
}
