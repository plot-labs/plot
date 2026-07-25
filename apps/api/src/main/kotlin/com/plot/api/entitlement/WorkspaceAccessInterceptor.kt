package com.plot.api.entitlement

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor

@Component
class WorkspaceAccessInterceptor(
	private val workspaceAccessService: WorkspaceAccessService,
) : HandlerInterceptor {
	override fun preHandle(
		request: HttpServletRequest,
		response: HttpServletResponse,
		handler: Any,
	): Boolean {
		if (handler !is HandlerMethod) return true
		if (request.method in SAFE_METHODS || handler.allowsReadOnly()) return true
		workspaceAccessService.requireWritable()
		return true
	}

	private fun HandlerMethod.allowsReadOnly(): Boolean =
		hasMethodAnnotation(ReadOnlyAllowed::class.java) ||
			beanType.isAnnotationPresent(ReadOnlyAllowed::class.java)

	private companion object {
		val SAFE_METHODS = setOf(
			HttpMethod.GET.name(),
			HttpMethod.HEAD.name(),
			HttpMethod.OPTIONS.name(),
		)
	}
}
