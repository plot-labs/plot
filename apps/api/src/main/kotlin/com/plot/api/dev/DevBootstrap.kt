package com.plot.api.dev

import com.plot.api.common.allowsDevelopmentAuthBypass
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.core.type.AnnotatedTypeMetadata
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "plot.dev-bootstrap", name = ["enabled"], havingValue = "true", matchIfMissing = false)
@Conditional(DevBootstrapSafeEnvironmentCondition::class)
class DevBootstrap(
	private val devBootstrapService: DevBootstrapService,
) : ApplicationRunner {

	override fun run(args: ApplicationArguments) {
		devBootstrapService.bootstrap()
	}
}

class DevBootstrapSafeEnvironmentCondition : Condition {
	override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean =
		context.environment.allowsDevelopmentAuthBypass()
}
