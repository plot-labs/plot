package com.plot.api.common

import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@RestControllerAdvice
class ApiExceptionHandler {

	@ExceptionHandler(ApiException::class)
	fun handleApiException(exception: ApiException): ResponseEntity<ApiErrorResponse> {
		val response = ResponseEntity
			.status(exception.status)
			.body(ApiErrorResponse(exception.error, exception.message, exception.resourceId))
		return if (exception.error.startsWith("GITHUB") || exception.error.startsWith("IMPORT") || exception.error == "INVALID_GITHUB_STATE") {
			ResponseEntity.status(response.statusCode).cacheControl(CacheControl.noStore()).body(response.body)
		} else {
			response
		}
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

	@ExceptionHandler(MethodArgumentTypeMismatchException::class)
	fun handleTypeMismatchException(
		exception: MethodArgumentTypeMismatchException,
	): ResponseEntity<ApiErrorResponse> {
		return ResponseEntity
			.status(HttpStatus.BAD_REQUEST)
			.body(ApiErrorResponse("BAD_REQUEST", "Request parameter is invalid"))
	}
}
