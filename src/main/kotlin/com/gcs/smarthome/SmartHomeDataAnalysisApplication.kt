package com.gcs.smarthome

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling

@EnableAsync
@EnableScheduling
@SpringBootApplication
@ConfigurationPropertiesScan(basePackageClasses = [SmartHomeDataAnalysisApplication::class])
class SmartHomeDataAnalysisApplication {

	companion object {
		@JvmStatic
		fun main(args: Array<String>) {
			runApplication<SmartHomeDataAnalysisApplication>(*args)
		}
	}
}
