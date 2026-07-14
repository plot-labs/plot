package com.plot.api.writingblock

import java.security.MessageDigest
import java.util.HexFormat

fun writingBlockContentHash(title: String?, body: String?): String = HexFormat.of().formatHex(
	MessageDigest.getInstance("SHA-256")
		.digest("${title.orEmpty()}\n${body.orEmpty()}".toByteArray(Charsets.UTF_8)),
)
