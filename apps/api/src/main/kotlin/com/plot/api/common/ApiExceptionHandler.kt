package com.plot.api.common

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException

@RestControllerAdvice
class ApiExceptionHandler {

	@ExceptionHandler(ApiException::class)
	fun handleApiException(exception: ApiException): ResponseEntity<ApiErrorResponse> {
		return ResponseEntity
			.status(exception.status)
			.body(ApiErrorResponse(exception.error, exception.message))
	}

	@ExceptionHandler(MethodArgumentNotValidException::class)
	fun handleValidationException(exception: MethodArgumentNotValidException): ResponseEntity<ApiErrorResponse> {
		val message = exception.bindingResult.fieldErrors.firstOrNull()?.defaultMessage
			?: "Request validation failed"
		return ResponseEntity
			.status(HttpStatus.BAD_REQUEST)
			.body(ApiErrorResponse("BAD_REQUEST", message))
	}

	@ExceptionHandler(HandlerMethodValidationException::class)
	fun handleHandlerMethodValidationException(
		exception: HandlerMethodValidationException,
	): ResponseEntity<ApiErrorResponse> {
		val message = exception.parameterValidationResults
			.asSequence()
			.flatMap { it.resolvableErrors.asSequence() }
			.mapNotNull { it.defaultMessage }
			.firstOrNull()
			?: "Request validation failed"
		return ResponseEntity
			.status(HttpStatus.BAD_REQUEST)
			.body(ApiErrorResponse("BAD_REQUEST", message))
	}

	@ExceptionHandler(HttpMessageNotReadableException::class)
	fun handleUnreadableJson(exception: HttpMessageNotReadableException): ResponseEntity<ApiErrorResponse> {
		return ResponseEntity
			.status(HttpStatus.BAD_REQUEST)
			.body(ApiErrorResponse("BAD_REQUEST", "Request body is invalid"))
	}
}
