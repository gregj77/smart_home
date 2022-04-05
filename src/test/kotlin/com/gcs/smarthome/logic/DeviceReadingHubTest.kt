package com.gcs.smarthome.logic

import com.gcs.smarthome.testutils.JpaConfig
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension

@DataJpaTest
@ExtendWith(SpringExtension::class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration( classes = [ DeviceReadingHubTest.ScopedConfig::class ])
class DeviceReadingHubTest {

    @BeforeEach
    fun setUp() {
    }

    @Test
    fun onNewBusinessDayAssigned() {
    }

    @Configuration
    @Import(value = [JpaConfig::class])
    @EnableJpaRepositories(basePackages = ["com.gcs.smarthome.data.repository"])
    @EntityScan(basePackages = ["com.gcs.smarthome.data.model"])
    class ScopedConfig {

    }
}