package com.gcs.smarthome.testutils

import com.mysql.cj.jdbc.exceptions.CommunicationsException
import mu.KotlinLogging
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.boot.jdbc.DatabaseDriver
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.EnableTransactionManagement
import java.sql.DriverManager
import java.time.LocalTime
import javax.sql.DataSource


@Configuration
@TestPropertySource("classpath:application.properties")
@EnableTransactionManagement
class JpaConfig {

    val logger = KotlinLogging.logger {  }

    @Bean
    @Order(-10000)
    fun databaseStartupValidator(
        @Value("\${spring.datasource.url}") url: String,
        @Value("\${spring.datasource.username}") username: String,
        @Value("\${spring.datasource.password}") password: String): InitializingBean {
        logger.warn { "creating database startup validator...." }

        return InitializingBean {

            val expiryTime = LocalTime.now().plusMinutes(1L)

            while (LocalTime.now().isBefore(expiryTime)) {
                Thread.sleep(5000L)

                try {
                    DriverManager.getConnection(url, username, password).use {
                        it.createStatement().use { stmt ->
                                stmt.executeQuery(DatabaseDriver.MYSQL.validationQuery)
                                logger.warn { "database is ready!" }
                                return@InitializingBean
                        }
                    }
                } catch (err: CommunicationsException) {
                    logger.warn { "database is not ready yet...." }
                }
            }
        }
    }


    @Bean
    @Order(-9999)
    fun dependsOnPostProcessor(): BeanFactoryPostProcessor {
        return BeanFactoryPostProcessor {
            it
                .getBeanNamesForType(DataSource::class.java)
                .map { s  -> it.getBeanDefinition(s) }
                .forEach { b -> b.setDependsOn("databaseStartupValidator") }
        }
    }
}