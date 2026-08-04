package com.plot.api.contentpack

import com.plot.api.contentpack.dto.ContentExportResponse
import com.plot.api.contentpack.dto.ContentPackResponse
import com.plot.api.contentpack.dto.ContentPackPageResponse
import com.plot.api.contentpack.dto.EditSentenceRequest
import com.plot.api.contentpack.dto.ExportContentVariantRequest
import com.plot.api.contentpack.dto.SaveContentVariantRequest
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
class ContentPackController(private val service: ContentPackService) {
	@GetMapping("/content-packs")
	fun list(
		@RequestParam(defaultValue = "0") page: Int,
		@RequestParam(defaultValue = "25") size: Int,
	): ResponseEntity<ContentPackPageResponse> = ResponseEntity.ok()
		.cacheControl(CacheControl.noStore()).body(service.list(page, size))

	@GetMapping("/content-packs/{id}")
	fun get(@PathVariable id: UUID): ResponseEntity<ContentPackResponse> = ResponseEntity.ok()
		.cacheControl(CacheControl.noStore()).body(service.get(id))

	@GetMapping("/content-variants/{variantId}")
	fun getVariant(@PathVariable variantId: UUID): ResponseEntity<ContentPackResponse> = ResponseEntity.ok()
		.cacheControl(CacheControl.noStore()).body(service.getVariant(variantId))

	@PatchMapping("/content-variants/{variantId}")
	fun save(
		@PathVariable variantId: UUID,
		@Valid @RequestBody request: SaveContentVariantRequest,
	): ResponseEntity<ContentPackResponse> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
		service.saveVariant(
			variantId,
			requireNotNull(request.expectedRevisionNumber),
			requireNotNull(request.lexicalContent),
			requireNotNull(request.statements),
		),
	)

	@PutMapping("/content-variants/{variantId}")
	fun replace(
		@PathVariable variantId: UUID,
		@Valid @RequestBody request: SaveContentVariantRequest,
	): ResponseEntity<ContentPackResponse> = save(variantId, request)

	@PatchMapping("/content-variants/{variantId}/sentences/{sentenceId}")
	fun edit(
		@PathVariable variantId: UUID,
		@PathVariable sentenceId: UUID,
		@Valid @RequestBody request: EditSentenceRequest,
	): ResponseEntity<ContentPackResponse> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
		service.editSentence(variantId, sentenceId, requireNotNull(request.expectedRevisionNumber), requireNotNull(request.body)),
	)

	@PostMapping("/content-variants/{variantId}/exports")
	@ReadOnlyAllowed
	fun export(
		@PathVariable variantId: UUID,
		@Valid @RequestBody request: ExportContentVariantRequest,
	): ResponseEntity<ContentExportResponse> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
		service.export(
			variantId,
			requireNotNull(request.expectedRevisionNumber),
			request.includeSources,
			request.acknowledgeUnresolved,
			request.acknowledgedWarningKeys,
			request.acknowledgedRevisionIds,
			request.disposition,
		),
	)
}
