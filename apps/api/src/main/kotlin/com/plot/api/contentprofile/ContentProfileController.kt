package com.plot.api.contentprofile

import com.plot.api.contentprofile.dto.ContentProfileResponse
import com.plot.api.contentprofile.dto.UpdateContentProfileRequest
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/content-profile")
class ContentProfileController(
	private val service: ContentProfileService,
) {
	@GetMapping
	fun get(): ContentProfileResponse = service.get()

	@PutMapping
	fun update(@Valid @RequestBody request: UpdateContentProfileRequest): ContentProfileResponse =
		service.update(request)
}
