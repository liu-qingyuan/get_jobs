package com.getjobs.application.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

@Configuration
public class StaticResourceConfiguration implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations(
                        "classpath:/dist/",
                        "file:src/main/resources/dist/",
                        "file:src/main/resources/static/",
                        "classpath:/static/"
                )
                .setCachePeriod(0)
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        if (resourcePath.equals("api") || resourcePath.startsWith("api/")) return null;

                        Resource resource = super.getResource(resourcePath, location);
                        if (resource != null || resourcePath.contains(".")) return resource;

                        String route = resourcePath.endsWith("/")
                                ? resourcePath.substring(0, resourcePath.length() - 1)
                                : resourcePath;
                        // Next.js exports pages as HTML and App Router payloads as text.
                        resource = super.getResource(route + ".html", location);
                        if (resource != null) return resource;
                        resource = super.getResource(route + ".txt", location);
                        if (resource != null) return resource;

                        return super.getResource("index.html", location);
                    }
                });
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
    }
}
