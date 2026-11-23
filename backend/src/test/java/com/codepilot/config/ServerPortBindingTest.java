package com.codepilot.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class ServerPortBindingTest {

    @Test
    void bindsServerPortToInjectedPortAndDefaultsTo8080() throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> yamlSources = loader.load("application",
                new FileSystemResource("src/main/resources/application.yml"));

        StandardEnvironment defaultEnv = new StandardEnvironment();
        yamlSources.forEach(src -> defaultEnv.getPropertySources().addLast(src));
        assertThat(defaultEnv.getProperty("server.port")).isEqualTo("8080");
        assertThat(defaultEnv.getProperty("server.address")).isEqualTo("0.0.0.0");

        StandardEnvironment renderEnv = new StandardEnvironment();
        Properties injected = new Properties();
        injected.setProperty("PORT", "10000");
        MutablePropertySources sources = renderEnv.getPropertySources();
        sources.addFirst(new PropertiesPropertySource("render-env", injected));
        yamlSources.forEach(sources::addLast);
        assertThat(renderEnv.getProperty("server.port")).isEqualTo("10000");
    }
}
