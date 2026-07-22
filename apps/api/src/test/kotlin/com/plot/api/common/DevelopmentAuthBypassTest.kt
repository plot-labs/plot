package com.plot.api.common

import com.plot.api.dev.DevBootstrapSafeEnvironmentCondition
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.mock.env.MockEnvironment

class DevelopmentAuthBypassTest {
	@Test
	fun `unsafe profile cannot enable the auth bypass or bootstrap`() {
		val environment = environment("production", "0.0.0.0")
		assertFalse(environment.allowsDevelopmentAuthBypass())

		bootstrapContext("production", "0.0.0.0").run { context ->
			assertFalse(context.containsBean("bootstrapMarker"))
		}
		ApplicationContextRunner()
			.withUserConfiguration(ActualBootstrapConfiguration::class.java)
			.withPropertyValues("plot.dev-bootstrap.enabled=true", "server.address=0.0.0.0")
			.withInitializer { it.environment.setActiveProfiles("production") }
			.run { context -> assertFalse(context.containsBean("devBootstrap")) }
	}

	@Test
	fun `local test and loopback certification contexts allow the bypass and bootstrap`() {
		listOf(
			"local" to "0.0.0.0",
			"test" to "0.0.0.0",
			"generation-certification" to "127.0.0.1",
		).forEach { (profile, address) ->
			assertTrue(environment(profile, address).allowsDevelopmentAuthBypass())
			bootstrapContext(profile, address).run { context ->
				assertTrue(context.containsBean("bootstrapMarker"))
			}
		}
	}

	@Test
	fun `certification bypass and bootstrap require loopback`() {
		assertFalse(environment("generation-certification", "0.0.0.0").allowsDevelopmentAuthBypass())
		bootstrapContext("generation-certification", "0.0.0.0").run { context ->
			assertFalse(context.containsBean("bootstrapMarker"))
		}
	}

	private fun environment(profile: String, address: String) = MockEnvironment().apply {
		setActiveProfiles(profile)
		setProperty("server.address", address)
		setProperty("plot.dev-bootstrap.enabled", "true")
	}

	private fun bootstrapContext(profile: String, address: String) = ApplicationContextRunner()
		.withUserConfiguration(BootstrapMarkerConfiguration::class.java)
		.withPropertyValues("plot.dev-bootstrap.enabled=true", "server.address=$address")
		.withInitializer { it.environment.setActiveProfiles(profile) }

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(prefix = "plot.dev-bootstrap", name = ["enabled"], havingValue = "true")
	@Conditional(DevBootstrapSafeEnvironmentCondition::class)
	private class BootstrapMarkerConfiguration {
		@Bean
		fun bootstrapMarker() = Any()
	}

	@Configuration(proxyBeanMethods = false)
	@Import(com.plot.api.dev.DevBootstrap::class)
	private class ActualBootstrapConfiguration
}
