package com.plot.api.certification

import com.openai.client.OpenAIClientImpl
import com.openai.client.OpenAIClientAsyncImpl
import com.openai.core.ClientOptions
import com.plot.api.ai.prompt.ChangelogPromptFactory
import com.plot.api.ai.provider.SpringAiOpenAiGenerationGateway
import com.plot.api.ai.provider.SpringAiStructuredChatTransport
import com.plot.api.ai.provider.WriterModelRequest
import com.plot.api.config.PlotAiProperties
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient
import tools.jackson.databind.ObjectMapper

@Tag("generation-certification-live")
@EnabledIfEnvironmentVariable(named = "PLOT_GENERATION_CERTIFICATION_PREFLIGHT", matches = "(?i)true")
class OpenRouterCertificationLivePreflightTest {
	@Test
	fun `production transport route is attributable before a model execution can be sealed`() {
		val apiKey = requiredEnvironment("OPENROUTER_API_KEY")
		val requestedModel = requiredEnvironment("PLOT_AI_MODEL")
		val providerSlug = requiredEnvironment("PLOT_AI_ROUTING_PROVIDER")
		val properties = PlotAiProperties(
			enabled = true,
			model = requestedModel,
			routingProvider = providerSlug,
			transportRetries = 0,
			schemaRetries = 0,
			maxOutputTokens = 256,
		)
		val openRouterTransport = JdkOpenRouterCertificationTransport.live(apiKey)
		val httpClient = SpringAiOpenAiHttpClient.builder().timeout(properties.timeout).build()
		val clientOptions = ClientOptions.builder()
			.httpClient(httpClient)
			.apiKey(apiKey)
			.baseUrl(CanonicalExternalOriginPolicy.OPENROUTER_API)
			.maxRetries(0)
			.build()
		val client = OpenAIClientImpl(clientOptions)
		val asyncClient = OpenAIClientAsyncImpl(clientOptions)
		try {
			val model = OpenAiChatModel.builder()
				.openAiClient(client)
				.openAiClientAsync(asyncClient)
				.build()
			val gateway = SpringAiOpenAiGenerationGateway(
				SpringAiStructuredChatTransport(ChatClient.builder(model), properties),
				properties,
				ChangelogPromptFactory(ObjectMapper()),
			)
			val result = OpenRouterPreflight(
				OpenRouterCertificationClient(openRouterTransport),
				OpenRouterGenerationMetadataClient(openRouterTransport),
			).run(
				OpenRouterPreflightRequest(requestedModel, providerSlug, properties.zeroDataRetention),
				ProductionStructuredCanary {
					val call = gateway.write(
						WriterModelRequest(
							generationRunId = UUID.nameUUIDFromBytes("plot-openrouter-certification-canary".toByteArray()),
							instruction = "Transport canary: return one short sentence stating that no changelog evidence was supplied.",
							evidence = emptyList(),
						),
					)
					ProductionCanaryResult(
						ProductionCanaryMetadata(
							responseId = call.metadata.responseId,
							servedModel = call.metadata.servedModel,
							gateway = call.metadata.gateway,
							requestedModel = call.metadata.requestedModel,
						),
						structuredOutputParsed = call.value.sentences.isNotEmpty(),
					)
				},
			) { verified -> verified }

			assertEquals(requestedModel, result.attribution.requestedModel)
			assertEquals(providerSlug, result.attribution.providerSlug)
		} finally {
			client.close()
			asyncClient.close()
		}
	}

	private fun requiredEnvironment(name: String): String = System.getenv(name)?.trim()?.takeIf(String::isNotEmpty)
		?: error("$name is required for the opt-in certification preflight")
}
