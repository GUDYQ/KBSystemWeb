package org.example.kbsystemproject.config.ai;

//import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

@Configuration
public class JdbcConfig {

//     1. 手动创建 DataSource (读取 application.yml 中的 spring.datasource.* 配置)
    @Bean
    @ConfigurationProperties(prefix = "spring.datasource.hikari")
    public DataSource jdbcDataSource() {
        return new HikariDataSource();
    }

    // 2. 手动创建 JdbcTemplate
    @Bean
    public JdbcTemplate jdbcTemplate(DataSource jdbcDataSource) {
        return new JdbcTemplate(jdbcDataSource);
    }
}
