package com.plot.api.auth.workos

import com.plot.api.auth.WorkOSAuthProperties
import com.workos.WorkOS
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "plot.workos", name = ["enabled"], havingValue = "true")
class WorkOSClientConfiguration {
	@Bean
	fun workOSClient(properties: WorkOSAuthProperties): WorkOS = WorkOS(
		properties.apiKey,
		properties.clientId,
	)
}

@Component
class WorkOSClientProvider(
	private val clients: ObjectProvider<WorkOS>,
) {
	fun require(): WorkOS = clients.ifAvailable ?: throw WorkOSNotConfiguredException()
}

class WorkOSNotConfiguredException : IllegalStateException("WorkOS is not configured")

class WorkOSProviderException(
	message: String,
	cause: Throwable,
) : RuntimeException(message, cause)

class WorkOSUserNotFoundException(
	val workOSUserId: String,
	cause: Throwable? = null,
) : RuntimeException("WorkOS user was not found: $workOSUserId", cause)
