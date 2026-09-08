package com.plot.api.artifact

import com.plot.api.artifact.dto.ContentExportResponse
import com.plot.api.artifact.dto.ArtifactResponse
import com.plot.api.artifact.dto.ArtifactPageResponse
import com.plot.api.artifact.dto.ContentVariantHistoryDetailResponse
import com.plot.api.artifact.dto.ContentVariantHistoryItemResponse
import com.plot.api.artifact.dto.EditSentenceRequest
import com.plot.api.artifact.dto.ExportContentVariantRequest
import com.plot.api.artifact.dto.ProductDeliveryEventResponse
import com.plot.api.artifact.dto.PublishContentVariantRequest
import com.plot.api.artifact.dto.PublishContentVariantResponse
import com.plot.api.artifact.dto.RecordProductDeliveryEventRequest
import com.plot.api.artifact.dto.SaveContentVariantRequest
import com.plot.api.artifact.dto.UnpublishContentVariantResponse
import com.plot.api.artifact.dto.ReplicateContentRequest
import com.plot.api.common.WorkspacePrincipal
import com.plot.api.dev.DevContext
import com.plot.api.entitlement.CompletionAllowed
import com.plot.api.entitlement.ReadOnlyAllowed
import com.plot.api.entitlement.SafetyAllowed
import com.plot.api.routine.ChatAgentAdmissionService
import com.plot.api.routine.dto.ChatAgentRunResponse
import jakarta.validation.Valid
import java.net.URI
import java.util.UUID
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RequestParam

@RestController
@RequestMapping("/api")
class ArtifactController(
	private val queryService: ArtifactQueryService,
	private val revisionService: ArtifactRevisionService,
	private val exportService: ArtifactExportService,
	private val publishService: ArtifactPublishService,
	private val deliveryEventService: ProductDeliveryEventService,
	private val chatAgentAdmissionService: ChatAgentAdmissionService,
	private val devContext: DevContext,
) {
	@GetMapping("/artifacts")
	fun list(
		@RequestParam(defaultValue = "0") page: Int,
		@RequestParam(defaultValue = "25") size: Int,
	): ResponseEntity<ArtifactPageResponse> = ResponseEntity.ok()
		.cacheControl(CacheControl.noStore()).body(queryService.list(page, size))

	@GetMapping("/artifacts/{id}")
	fun get(@PathVariable id: UUID): ResponseEntity<ArtifactResponse> = ResponseEntity.ok()
		.cacheControl(CacheControl.noStore()).body(queryService.get(id))

	@PostMapping("/artifacts/{id}/replicate")
	fun replicate(
		@PathVariable id: UUID,
		@RequestHeader("Idempotency-Key") idempotencyKey: String,
		@Valid @RequestBody request: ReplicateContentRequest,
	): ResponseEntity<ChatAgentRunResponse> {
		val response = chatAgentAdmissionService.admitReplication(
			principal = WorkspacePrincipal(devContext.devWorkspaceId, devContext.devUserId),
			artifactId = id,
			request = request,
			idempotencyKey = idempotencyKey,
		)
		return ResponseEntity.accepted()
			.location(URI.create("/api/agent-runs/${response.id}"))
			.cacheControl(CacheControl.noStore())
			.body(response)
	}

	@GetMapping("/artifact-variants/{variantId}")
	fun getVariant(@PathVariable variantId: UUID): ResponseEntity<ArtifactResponse> = ResponseEntity.ok()
		.cacheControl(CacheControl.noStore()).body(queryService.getVariant(variantId))

	@GetMapping("/artifact-variants/{variantId}/history")
	fun history(@PathVariable variantId: UUID): ResponseEntity<List<ContentVariantHistoryItemResponse>> = ResponseEntity.ok()
		.cacheControl(CacheControl.noStore()).body(queryService.history(variantId))

	@GetMapping("/artifact-variants/{variantId}/history/{revisionId}")
	fun historyDetail(
		@PathVariable variantId: UUID,
		@PathVariable revisionId: UUID,
	): ResponseEntity<ContentVariantHistoryDetailResponse> = ResponseEntity.ok()
		.cacheControl(CacheControl.noStore()).body(queryService.historyDetail(variantId, revisionId))

	@GetMapping("/artifact-variants/{variantId}/history/at/{position}")
	fun historyAt(
		@PathVariable variantId: UUID,
		@PathVariable position: Int,
	): ResponseEntity<ContentVariantHistoryDetailResponse> = ResponseEntity.ok()
		.cacheControl(CacheControl.noStore()).body(queryService.historyDetailAt(variantId, position))

	@PatchMapping("/artifact-variants/{variantId}")
	@CompletionAllowed
	fun save(
		@PathVariable variantId: UUID,
		@Valid @RequestBody request: SaveContentVariantRequest,
	): ResponseEntity<ArtifactResponse> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
		revisionService.saveVariant(
			variantId,
			requireNotNull(request.expectedRevisionNumber),
			requireNotNull(request.lexicalContent),
			requireNotNull(request.statements),
		),
	)

	@PutMapping("/artifact-variants/{variantId}")
	@CompletionAllowed
	fun replace(
		@PathVariable variantId: UUID,
		@Valid @RequestBody request: SaveContentVariantRequest,
	): ResponseEntity<ArtifactResponse> = save(variantId, request)

	@PatchMapping("/artifact-variants/{variantId}/sentences/{sentenceId}")
	@CompletionAllowed
	fun edit(
		@PathVariable variantId: UUID,
		@PathVariable sentenceId: UUID,
		@Valid @RequestBody request: EditSentenceRequest,
	): ResponseEntity<ArtifactResponse> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
		revisionService.editSentence(variantId, sentenceId, requireNotNull(request.expectedRevisionNumber), requireNotNull(request.body)),
	)

	@PostMapping("/artifact-variants/{variantId}/exports")
	@ReadOnlyAllowed
	fun export(
		@PathVariable variantId: UUID,
		@Valid @RequestBody request: ExportContentVariantRequest,
	): ResponseEntity<ContentExportResponse> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
		exportService.export(
			variantId,
			requireNotNull(request.expectedRevisionNumber),
			requireNotNull(request.includeSources),
			request.acknowledgeUnresolved,
			request.acknowledgedWarningKeys,
			request.acknowledgedRevisionIds,
			request.disposition,
		),
	)

	@PostMapping("/artifact-variants/{variantId}/publish")
	@CompletionAllowed
	fun publish(
		@PathVariable variantId: UUID,
		@Valid @RequestBody request: PublishContentVariantRequest,
	): ResponseEntity<PublishContentVariantResponse> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
		publishService.publish(
			variantId,
			requireNotNull(request.expectedRevisionNumber),
			request.acknowledgeUnresolved,
			request.acknowledgedWarningKeys,
			request.acknowledgedRevisionIds,
		),
	)

	@PostMapping("/artifact-variants/{variantId}/unpublish")
	@SafetyAllowed
	fun unpublish(@PathVariable variantId: UUID): ResponseEntity<UnpublishContentVariantResponse> =
		ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(publishService.unpublish(variantId))

	@PostMapping("/artifact-variants/{variantId}/delivery-events")
	@ReadOnlyAllowed
	fun recordDeliveryEvent(
		@PathVariable variantId: UUID,
		@Valid @RequestBody request: RecordProductDeliveryEventRequest,
	): ResponseEntity<ProductDeliveryEventResponse> = ResponseEntity.ok()
		.cacheControl(CacheControl.noStore())
		.body(deliveryEventService.recordClientEvent(variantId, request))
}
