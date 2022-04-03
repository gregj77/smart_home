package com.gcs.smarthome

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.web.reactive.config.EnableWebFlux
import java.util.*


@EnableAsync
@EnableScheduling
@EnableWebFlux
@SpringBootApplication
@EnableTransactionManagement
@ConfigurationPropertiesScan(basePackageClasses = [SmartHomeDataAnalysisApplication::class])
class SmartHomeDataAnalysisApplication {

	companion object {
		@JvmStatic
		fun main(args: Array<String>) {
			TimeZone.setDefault(TimeZone.getTimeZone("CET"))
			runApplication<SmartHomeDataAnalysisApplication>(*args)
		}
	}
}
