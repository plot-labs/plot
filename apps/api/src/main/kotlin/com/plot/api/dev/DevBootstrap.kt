package com.plot.api.dev

import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "plot.dev-bootstrap", name = ["enabled"], havingValue = "true", matchIfMissing = false)
class DevBootstrap(
	private val devBootstrapService: DevBootstrapService,
) : ApplicationRunner {

	override fun run(args: ApplicationArguments) {
		devBootstrapService.bootstrap()
	}
}
