package com.plot.api.contentpack

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class ContentPackConfiguration {
	@Bean
	fun markdownExportService(): MarkdownExportService = MarkdownExportService()
}
