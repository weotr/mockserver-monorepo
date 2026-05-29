package org.mockserver.llm.client;

import org.junit.After;
import org.junit.Test;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.model.Provider;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

public class LlmBackendResolverTest {

    @After
    public void clearProperties() {
        ConfigurationProperties.llmProvider("");
        ConfigurationProperties.llmApiKey("");
        ConfigurationProperties.llmModel("");
        ConfigurationProperties.llmBaseUrl("");
        ConfigurationProperties.llmBackendsConfig("");
    }

    @Test
    public void resolvesNothingWhenUnconfigured() {
        LlmBackendResolver resolver = new LlmBackendResolver(name -> null);
        assertThat(resolver.resolveDefault().isPresent(), is(false));
    }

    @Test
    public void resolvesDefaultFromProperties() {
        ConfigurationProperties.llmProvider("OPENAI");
        ConfigurationProperties.llmApiKey("sk-prop");
        ConfigurationProperties.llmModel("gpt-4o");
        LlmBackendResolver resolver = new LlmBackendResolver(name -> null);
        Optional<LlmBackend> backend = resolver.resolveDefault();
        assertThat(backend.isPresent(), is(true));
        assertThat(backend.get().provider(), is(Provider.OPENAI));
        assertThat(backend.get().apiKey(), is("sk-prop"));
        assertThat(backend.get().model(), is("gpt-4o"));
    }

    @Test
    public void propertiesTakePrecedenceOverEnvironment() {
        ConfigurationProperties.llmProvider("ANTHROPIC");
        ConfigurationProperties.llmApiKey("ak-prop");
        Map<String, String> env = new HashMap<>();
        env.put("OPENAI_API_KEY", "sk-env");
        LlmBackendResolver resolver = new LlmBackendResolver(env::get);
        assertThat(resolver.resolveDefault().get().provider(), is(Provider.ANTHROPIC));
    }

    @Test
    public void detectsOpenAiFromEnvironment() {
        Map<String, String> env = new HashMap<>();
        env.put("OPENAI_API_KEY", "sk-env");
        LlmBackendResolver resolver = new LlmBackendResolver(env::get);
        Optional<LlmBackend> backend = resolver.resolveDefault();
        assertThat(backend.isPresent(), is(true));
        assertThat(backend.get().provider(), is(Provider.OPENAI));
        assertThat(backend.get().apiKey(), is("sk-env"));
    }

    @Test
    public void detectsOllamaHostFromEnvironmentWithPriority() {
        Map<String, String> env = new HashMap<>();
        env.put("OLLAMA_HOST", "localhost:11434");
        env.put("OPENAI_API_KEY", "sk-env");
        LlmBackendResolver resolver = new LlmBackendResolver(env::get);
        Optional<LlmBackend> backend = resolver.resolveDefault();
        assertThat(backend.get().provider(), is(Provider.OLLAMA));
        assertThat(backend.get().baseUrl(), is("http://localhost:11434"));
    }

    @Test
    public void parsesNamedBackendsFromJson() {
        String json = "[{\"name\":\"prod\",\"provider\":\"ANTHROPIC\",\"apiKey\":\"ak\",\"model\":\"claude\"," +
            "\"headers\":{\"x-custom\":\"v\"},\"timeoutMillis\":5000}," +
            "{\"name\":\"local\",\"provider\":\"OLLAMA\",\"baseUrl\":\"http://localhost:11434\"}]";
        Map<String, LlmBackend> backends = new LlmBackendResolver(name -> null).parseBackends(json);
        assertThat(backends.size(), is(2));
        assertThat(backends.get("prod").provider(), is(Provider.ANTHROPIC));
        assertThat(backends.get("prod").headers().get("x-custom"), is("v"));
        assertThat(backends.get("prod").timeoutMillis(), is(5000L));
        assertThat(backends.get("local").provider(), is(Provider.OLLAMA));
    }

    @Test
    public void skipsNamedBackendsWithUnknownProvider() {
        String json = "[{\"name\":\"bad\",\"provider\":\"NOT_A_PROVIDER\"}]";
        assertThat(new LlmBackendResolver(name -> null).parseBackends(json).isEmpty(), is(true));
    }

    @Test
    public void resolvesNamedBackendFromConfiguredJsonFile() throws Exception {
        Path file = Files.createTempFile("llm-backends", ".json");
        try {
            Files.write(file, ("[{\"name\":\"prod\",\"provider\":\"OPENAI\",\"apiKey\":\"sk-file\"}]")
                .getBytes(StandardCharsets.UTF_8));
            ConfigurationProperties.llmBackendsConfig(file.toString());
            LlmBackendResolver resolver = new LlmBackendResolver(name -> null);
            assertThat(resolver.resolveByName("prod").isPresent(), is(true));
            assertThat(resolver.resolveByName("prod").get().apiKey(), is("sk-file"));
            assertThat(resolver.resolveByName("missing").isPresent(), is(false));
            assertThat(resolver.resolveByName("").isPresent(), is(false));
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
