package com.plot.api.contentpack

import com.plot.api.contentpack.dto.ContentExportResponse
import com.plot.api.contentpack.dto.ContentPackResponse
import com.plot.api.contentpack.dto.EditSentenceRequest
import com.plot.api.contentpack.dto.ExportContentVariantRequest
import jakarta.validation.Valid
import java.util.UUID
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class ContentPackController(private val service: ContentPackService) {
	@GetMapping("/content-packs/{id}")
	fun get(@PathVariable id: UUID): ResponseEntity<ContentPackResponse> = ResponseEntity.ok()
		.cacheControl(CacheControl.noStore()).body(service.get(id))

	@PatchMapping("/content-variants/{variantId}/sentences/{sentenceId}")
	fun edit(
		@PathVariable variantId: UUID,
		@PathVariable sentenceId: UUID,
		@Valid @RequestBody request: EditSentenceRequest,
	): ResponseEntity<ContentPackResponse> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
		service.editSentence(variantId, sentenceId, requireNotNull(request.expectedRevisionNumber), requireNotNull(request.body)),
	)

	@PostMapping("/content-variants/{variantId}/exports")
	fun export(
		@PathVariable variantId: UUID,
		@Valid @RequestBody request: ExportContentVariantRequest,
	): ResponseEntity<ContentExportResponse> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
		service.export(variantId, request.acknowledgeUnresolved, request.disposition),
	)
}
