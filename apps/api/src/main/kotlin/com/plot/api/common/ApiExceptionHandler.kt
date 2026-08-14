package com.plot.api.common

import com.plot.api.artifact.workflow.ArtifactWorkflowIdempotencyConflictException
import com.plot.api.artifact.workflow.ArtifactWorkflowSourceAccessException
import com.plot.api.routine.RoutineExecutionIdempotencyConflictException
import com.plot.api.routine.AgentRunIdempotencyConflictException
import com.plot.api.artifact.ExportConfirmationRequiredException
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.bind.MissingRequestHeaderException

@RestControllerAdvice
class ApiExceptionHandler {

	@ExceptionHandler(ApiException::class)
	fun handleApiException(exception: ApiException): ResponseEntity<ApiErrorResponse> {
		return ResponseEntity
			.status(exception.status)
			.cacheControl(CacheControl.noStore())
			.body(ApiErrorResponse(exception.error, exception.message, exception.resourceId))
	}

	@ExceptionHandler(MissingRequestHeaderException::class)
	fun handleMissingHeader(exception: MissingRequestHeaderException): ResponseEntity<ApiErrorResponse> = ResponseEntity
		.status(HttpStatus.BAD_REQUEST).cacheControl(CacheControl.noStore())
		.body(ApiErrorResponse("BAD_REQUEST", "${exception.headerName} header is required"))

	@ExceptionHandler(ArtifactWorkflowIdempotencyConflictException::class)
	fun handleIdempotencyConflict(exception: ArtifactWorkflowIdempotencyConflictException): ResponseEntity<ApiErrorResponse> = ResponseEntity
		.status(HttpStatus.CONFLICT).cacheControl(CacheControl.noStore())
		.body(ApiErrorResponse("IDEMPOTENCY_KEY_REUSED", exception.message ?: "Idempotency key was reused"))

	@ExceptionHandler(RoutineExecutionIdempotencyConflictException::class)
	fun handleRoutineIdempotencyConflict(exception: RoutineExecutionIdempotencyConflictException): ResponseEntity<ApiErrorResponse> = ResponseEntity
		.status(HttpStatus.CONFLICT).cacheControl(CacheControl.noStore())
		.body(ApiErrorResponse("IDEMPOTENCY_KEY_REUSED", exception.message ?: "Idempotency key was reused"))

	@ExceptionHandler(AgentRunIdempotencyConflictException::class)
	fun handleAgentRunIdempotencyConflict(exception: AgentRunIdempotencyConflictException): ResponseEntity<ApiErrorResponse> = ResponseEntity
		.status(HttpStatus.CONFLICT).cacheControl(CacheControl.noStore())
		.body(ApiErrorResponse("IDEMPOTENCY_KEY_REUSED", exception.message ?: "Idempotency key was reused"))

	@ExceptionHandler(ArtifactWorkflowSourceAccessException::class)
	fun handleArtifactWorkflowSourceAccess(exception: ArtifactWorkflowSourceAccessException): ResponseEntity<ApiErrorResponse> = ResponseEntity
		.status(HttpStatus.NOT_FOUND).cacheControl(CacheControl.noStore())
		.body(ApiErrorResponse("SOURCE_NOT_FOUND", exception.message ?: "Source is unavailable"))

	@ExceptionHandler(IllegalArgumentException::class)
	fun handleIllegalArgument(exception: IllegalArgumentException): ResponseEntity<ApiErrorResponse> = ResponseEntity
		.status(HttpStatus.BAD_REQUEST).cacheControl(CacheControl.noStore())
		.body(ApiErrorResponse("BAD_REQUEST", exception.message ?: "Request is invalid"))

	@ExceptionHandler(ExportConfirmationRequiredException::class)
	fun handleExportConfirmation(exception: ExportConfirmationRequiredException): ResponseEntity<ApiErrorResponse> = ResponseEntity
		.status(HttpStatus.CONFLICT).cacheControl(CacheControl.noStore())
		.body(ApiErrorResponse(
			"EXPORT_CONFIRMATION_REQUIRED",
			exception.message ?: "Export requires explicit confirmation",
			details = mapOf("warnings" to exception.warnings),
		))

	@ExceptionHandler(MethodArgumentNotValidException::class)
	fun handleValidationException(exception: MethodArgumentNotValidException): ResponseEntity<ApiErrorResponse> {
		val message = exception.bindingResult.fieldErrors.firstOrNull()?.defaultMessage
			?: "Request validation failed"
		return ResponseEntity
			.status(HttpStatus.BAD_REQUEST)
			.cacheControl(CacheControl.noStore())
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
			.cacheControl(CacheControl.noStore())
			.body(ApiErrorResponse("BAD_REQUEST", message))
	}

	@ExceptionHandler(HttpMessageNotReadableException::class)
	fun handleUnreadableJson(exception: HttpMessageNotReadableException): ResponseEntity<ApiErrorResponse> {
		return ResponseEntity
			.status(HttpStatus.BAD_REQUEST)
			.cacheControl(CacheControl.noStore())
			.body(ApiErrorResponse("BAD_REQUEST", "Request body is invalid"))
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException::class)
	fun handleTypeMismatchException(
		exception: MethodArgumentTypeMismatchException,
	): ResponseEntity<ApiErrorResponse> {
		return ResponseEntity
			.status(HttpStatus.BAD_REQUEST)
			.cacheControl(CacheControl.noStore())
			.body(ApiErrorResponse("BAD_REQUEST", "Request parameter is invalid"))
	}
}
