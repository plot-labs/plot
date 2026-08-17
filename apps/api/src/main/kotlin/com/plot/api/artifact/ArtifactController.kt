package com.plot.api.artifact

import com.plot.api.artifact.dto.ContentExportResponse
import com.plot.api.artifact.dto.ArtifactResponse
import com.plot.api.artifact.dto.ArtifactPageResponse
import com.plot.api.artifact.dto.ContentVariantHistoryDetailResponse
import com.plot.api.artifact.dto.ContentVariantHistoryItemResponse
import com.plot.api.artifact.dto.EditSentenceRequest
import com.plot.api.artifact.dto.ExportContentVariantRequest
import com.plot.api.artifact.dto.SaveContentVariantRequest
import com.plot.api.entitlement.ReadOnlyAllowed
import jakarta.validation.Valid
import java.util.UUID
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RequestParam

@RestController
@RequestMapping("/api")
class ArtifactController(
	private val queryService: ArtifactQueryService,
	private val revisionService: ArtifactRevisionService,
	private val exportService: ArtifactExportService,
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
	fun replace(
		@PathVariable variantId: UUID,
		@Valid @RequestBody request: SaveContentVariantRequest,
	): ResponseEntity<ArtifactResponse> = save(variantId, request)

	@PatchMapping("/artifact-variants/{variantId}/sentences/{sentenceId}")
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
}
