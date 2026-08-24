package com.plot.api.entitlement

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WorkspaceAccessConfiguration(
	private val workspaceAccessInterceptor: WorkspaceAccessInterceptor,
) : WebMvcConfigurer {
	override fun addInterceptors(registry: InterceptorRegistry) {
		registry.addInterceptor(workspaceAccessInterceptor)
			.addPathPatterns("/api/**")
			.excludePathPatterns(
				"/api/account/bootstrap",
				"/api/me",
				"/api/polar/webhook",
				"/api/github/webhook",
			)
	}
}
