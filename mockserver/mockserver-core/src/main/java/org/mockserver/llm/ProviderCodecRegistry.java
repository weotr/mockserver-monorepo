package org.mockserver.llm;

import org.mockserver.llm.codec.AnthropicCodec;
import org.mockserver.llm.codec.AzureOpenAiCodec;
import org.mockserver.llm.codec.BedrockCodec;
import org.mockserver.llm.codec.GeminiCodec;
import org.mockserver.llm.codec.OllamaCodec;
import org.mockserver.llm.codec.OpenAiChatCompletionsCodec;
import org.mockserver.llm.codec.OpenAiResponsesCodec;
import org.mockserver.model.Provider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class ProviderCodecRegistry {

    private static final ProviderCodecRegistry INSTANCE = new ProviderCodecRegistry();

    // codecs are registered here at boot
    static {
        INSTANCE.register(new AnthropicCodec());
        INSTANCE.register(new OpenAiChatCompletionsCodec());
        INSTANCE.register(new OpenAiResponsesCodec());
        INSTANCE.register(new GeminiCodec());
        INSTANCE.register(new BedrockCodec());
        INSTANCE.register(new AzureOpenAiCodec());
        INSTANCE.register(new OllamaCodec());
    }

    private final ConcurrentHashMap<Provider, ProviderCodec> codecs = new ConcurrentHashMap<>();

    public static ProviderCodecRegistry getInstance() {
        return INSTANCE;
    }

    public void register(ProviderCodec codec) {
        codecs.put(codec.provider(), codec);
    }

    public Optional<ProviderCodec> lookup(Provider provider) {
        return Optional.ofNullable(codecs.get(provider));
    }

    public List<String> supportedProviderNames() {
        List<String> names = new ArrayList<>();
        for (Provider p : codecs.keySet()) {
            names.add(p.name());
        }
        Collections.sort(names);
        return names;
    }
}
