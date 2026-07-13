package com.plot.api

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	@ConditionalOnProperty(prefix = "plot.testcontainers", name = ["enabled"], havingValue = "true", matchIfMissing = true)
	fun pgvectorContainer(): PostgreSQLContainer {
		return PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16"))
	}

}
