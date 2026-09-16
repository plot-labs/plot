package com.plot.api.content

enum class ContentType {
	ARTIFACT,
	CHANGELOG,
	LAUNCH_ANNOUNCEMENT,
	;

	companion object {
		fun parse(raw: String?): ContentType {
			if (raw.isNullOrBlank()) return CHANGELOG
			return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
				?: throw IllegalArgumentException("Unsupported content type: $raw")
		}
	}
}
