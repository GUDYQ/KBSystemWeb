package org.example.kbsystemproject.config.ai;

import io.r2dbc.spi.ConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.r2dbc.connection.init.CompositeDatabasePopulator;
import org.springframework.r2dbc.connection.init.ConnectionFactoryInitializer;
import org.springframework.r2dbc.connection.init.ResourceDatabasePopulator;

@Configuration
public class R2dbcSchemaConfig {

    @Bean
    public ConnectionFactoryInitializer learningSessionSchemaInitializer(ConnectionFactory connectionFactory) {
        ConnectionFactoryInitializer initializer = new ConnectionFactoryInitializer();
        initializer.setConnectionFactory(connectionFactory);
        initializer.setDatabasePopulator(new CompositeDatabasePopulator(
                new ResourceDatabasePopulator(new ClassPathResource("db/schema-learning-session.sql")),
                new ResourceDatabasePopulator(new ClassPathResource("db/schema-learning-session-runtime.sql")),
                new ResourceDatabasePopulator(new ClassPathResource("db/schema-conversation-archive.sql")),
                new ResourceDatabasePopulator(new ClassPathResource("db/schema-learning-profile.sql")),
                new ResourceDatabasePopulator(new ClassPathResource("db/schema-learning-compression.sql"))
        ));
        return initializer;
    }
}
