package com.googledocs.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * Configures SPA route forwarding so direct URLs like /docs/{id} load index.html seamlessly.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource requestedResource = location.createRelative(resourcePath);
                        // If it's a real file (css, js, html), return it
                        if (requestedResource.exists() && requestedResource.isReadable()) {
                            return requestedResource;
                        }
                        // If it's not an API path, fallback to index.html for SPA client-side routing
                        if (!resourcePath.startsWith("api/")) {
                            return location.createRelative("index.html");
                        }
                        return null;
                    }
                });
    }
}
