package com.plot.api.contract

import com.plot.api.TestcontainersConfiguration
import com.plot.api.artifact.ArtifactLexicalDocumentValidator
import com.plot.api.artifact.NormalizedStatement
import com.plot.api.dev.DevContext
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@TestPropertySource(properties = ["plot.dev-bootstrap.enabled=true"])
class PlotApiContractIntegrationTest {
	@Autowired private lateinit var mockMvc: MockMvc
	@Autowired private lateinit var objectMapper: ObjectMapper
	@Autowired private lateinit var lexicalValidator: ArtifactLexicalDocumentValidator
	@Autowired private lateinit var devContext: DevContext
	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	private lateinit var handlerMapping: RequestMappingHandlerMapping

	@Test
	fun `manifest and fixtures define every representative transport case`() {
		val manifest = resource("manifest.json")
		assertEquals(1, manifest.path("version").intValue())
		assertTrue(manifest.path("prefixNeutral").booleanValue())
		val cases = manifest.path("cases").asArray().values().toList()
		assertTrue(cases.size >= 10)
		assertEquals(
			setOf("workspace", "github", "routine", "chat", "artifact"),
			cases.map { it.path("surface").stringValue() }.toSet(),
		)

		cases.forEach { contractCase ->
			val routeTemplate = contractCase.path("routeTemplate").stringValue()
			val route = contractCase.path("route").stringValue()
			assertTrue(routeTemplate.startsWith("/"), "${contractCase.path("id")} route template")
			assertFalse(routeTemplate.startsWith("/api"), "${contractCase.path("id")} must be prefix-neutral")
			assertTrue(route.startsWith("/"), "${contractCase.path("id")} route")
			assertFalse(route.startsWith("/api"), "${contractCase.path("id")} must omit API prefix")
			assertTrue(contractCase.path("method").isTextual)
			assertTrue(contractCase.path("clientMethod").isTextual)
			assertTrue(contractCase.path("requiredHeaders").asArray().size() > 0)
			assertTrue(contractCase.path("requiredHeaders").asArray().values().any { it.stringValue() == "accept" })

			val successFixture = fixturePath(contractCase, "successFixture")
			val errorFixture = fixturePath(contractCase, "errorFixture")
			assertTrue((successFixture == null) xor (errorFixture == null), "${contractCase.path("id")} needs one outcome fixture")
			(successFixture ?: errorFixture)?.let { fixture(it) }
			fixturePath(contractCase, "requestFixture")?.let { fixture(it) }
			if (successFixture != null) {
				assertTrue(contractCase.path("successStatus").intValue() in 200..299)
			} else {
				assertTrue(contractCase.path("errorStatus").intValue() >= 400)
			}
		}
	}

	@Test
	fun `manifest routes are registered by the backend`() {
		val mappings = handlerMapping.handlerMethods.keys.flatMap { mapping ->
			mapping.patternValues.map { path -> normalizeRoute(path) to mapping.methodsCondition.methods }
		}
		resource("manifest.json").path("cases").asArray().values().forEach { contractCase ->
			val expectedRoute = normalizeRoute("/api${contractCase.path("routeTemplate").stringValue()}")
			val expectedMethod = RequestMethod.valueOf(contractCase.path("method").stringValue())
			assertTrue(
				mappings.any { (route, methods) ->
					route == expectedRoute && (methods.isEmpty() || expectedMethod in methods)
				},
				"${contractCase.path("id").stringValue()} must map $expectedMethod $expectedRoute",
			)
		}
	}

	@Test
	fun `artifact save fixture passes server lexical validation`() {
		val request = fixture("fixtures/artifact-save-request.json")
		val statements = request.path("statements").asArray().values().map { statement ->
			NormalizedStatement(
				UUID.fromString(statement.path("id").stringValue()),
				statement.path("orderIndex").intValue(),
				statement.path("body").stringValue(),
			)
		}
		val sanitized = lexicalValidator.validateAndSanitizeLexicalContent(
			request.path("lexicalContent"),
			statements,
		)
		assertEquals(request.path("lexicalContent"), sanitized)
	}

	@Test
	fun `workspace endpoint keeps canonical response field set`() {
		val expected = fixture("fixtures/workspace-summary.json")
		val actual = mockMvc.get("/api/workspaces/${devContext.devWorkspaceId}")
			.andReturn()
			.response
		assertEquals(200, actual.status)
		val actualTree = objectMapper.readTree(actual.contentAsString)
		assertEquals(expected.propertyNames().toSet(), actualTree.propertyNames().toSet())
		assertEquals(devContext.devWorkspaceId.toString(), actualTree.path("id").stringValue())
	}

	private fun normalizeRoute(route: String): String = route.replace(Regex("""\{[^}]+}"""), "{}")

	private fun resource(path: String): JsonNode = objectMapper.readTree(
		ClassPathResource(path).inputStream.use { it.readBytes() },
	)

	private fun fixturePath(contractCase: JsonNode, field: String): String? = contractCase.path(field)
		.takeUnless { it.isMissingNode || it.isNull }
		?.stringValue()

	private fun fixture(path: String): JsonNode = resource(path)
}
