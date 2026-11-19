package com.filevault.storage;

import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

@Configuration
@RestController
@ConditionalOnWebApplication
class StaticWebConfig implements WebMvcConfigurer {
    private final WebRoot web;

    StaticWebConfig(WebRoot web, @Value("${spring.web.resources.add-mappings:false}") boolean defaultMappings) {
        if (defaultMappings) throw new IllegalStateException("FileVault requires the default static mappings to be disabled.");
        this.web = web;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        try {
            registry.addResourceHandler("/assets/**").addResourceLocations(web.assets())
                    .resourceChain(false).addResolver(new PathResourceResolver() {
                        @Override
                        protected boolean checkResource(Resource candidate, Resource location) throws IOException {
                            return super.checkResource(candidate, location) && web.containsAsset(candidate);
                        }
                    });
        } catch (IOException failure) {
            throw new IllegalStateException("FileVault could not configure static assets.", failure);
        }
    }

    @GetMapping({"/", "/index.html"})
    ResponseEntity<Resource> index() throws IOException {
        Resource index = web.index();
        if (index == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(index);
    }
}
