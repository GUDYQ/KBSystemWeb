package org.example.kbsystemproject;

//import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;

@SpringBootApplication(exclude = {OpenAiChatAutoConfiguration.class, OpenAiEmbeddingAutoConfiguration.class})
@EnableReactiveMethodSecurity
@ConfigurationPropertiesScan
public class KbSystemProjectApplication {

    public static void main(String[] args) {
        SpringApplication.run(KbSystemProjectApplication.class, args);
    }

}
