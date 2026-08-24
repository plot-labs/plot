package com.plot.api.github

import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class GitHubWebhookRequestSizeFilter(private val properties: GitHubProperties) : OncePerRequestFilter() {
	override fun shouldNotFilter(request: HttpServletRequest): Boolean =
		request.method != "POST" || request.requestURI.removePrefix(request.contextPath) != WEBHOOK_PATH

	override fun doFilterInternal(
		request: HttpServletRequest,
		response: HttpServletResponse,
		filterChain: FilterChain,
	) {
		val maxBytes = properties.maxWebhookPayloadBytes
		if (request.contentLengthLong > maxBytes) {
			response.status = HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE
			return
		}
		val rawBody = request.inputStream.use { it.readNBytes(maxBytes + 1) }
		if (rawBody.size > maxBytes) {
			response.status = HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE
			return
		}
		filterChain.doFilter(BoundedBodyRequest(request, rawBody), response)
	}

	private class BoundedBodyRequest(request: HttpServletRequest, private val body: ByteArray) : HttpServletRequestWrapper(request) {
		override fun getContentLength(): Int = body.size

		override fun getContentLengthLong(): Long = body.size.toLong()

		override fun getInputStream(): ServletInputStream = object : ServletInputStream() {
			private val input = ByteArrayInputStream(body)

			override fun read(): Int = input.read()

			override fun isFinished(): Boolean = input.available() == 0

			override fun isReady(): Boolean = true

			override fun setReadListener(readListener: ReadListener) {
				throw UnsupportedOperationException("Non-blocking reads are not supported")
			}
		}

		override fun getReader(): BufferedReader = BufferedReader(
			InputStreamReader(inputStream, safeCharacterEncoding()),
		)

		/** A client-controlled charset value must not turn into a 500 before signature verification. */
		private fun safeCharacterEncoding(): Charset =
			characterEncoding?.let { encoding -> runCatching { Charset.forName(encoding) }.getOrNull() }
				?: StandardCharsets.UTF_8
	}

	private companion object {
		const val WEBHOOK_PATH = "/api/github/webhook"
	}
}
