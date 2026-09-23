package com.plot.api.auth

import com.plot.api.TestcontainersConfiguration
import com.plot.api.auth.workos.WorkOSMembershipRecord
import com.plot.api.auth.workos.WorkOSMembershipGateway
import com.plot.api.auth.workos.WorkOSMembershipSnapshot
import com.plot.api.auth.workos.WorkOSOrganizationGateway
import com.plot.api.auth.workos.WorkOSOrganizationRecord
import com.plot.api.auth.workos.WorkOSProviderException
import com.plot.api.auth.workos.WorkOSUserGateway
import com.plot.api.auth.workos.WorkOSUserProfile
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, WorkOSBootstrapFakeConfiguration::class)
@ActiveProfiles("integration")
@TestPropertySource(properties = [
	"plot.dev-bootstrap.enabled=false",
	"plot.workos.enabled=true",
	"plot.workos.api-key=sk_test_bootstrap",
	"plot.workos.client-id=client_test_bootstrap",
	"plot.workos.issuer=https://issuer.workos.test",
	"plot.workos.audience=plot-api",
	"plot.workos.jwks-uri=https://issuer.workos.test/.well-known/jwks.json",
])
class WorkOSAccountBootstrapApiIntegrationTest {
	@Autowired private lateinit var mockMvc: MockMvc
	@Autowired private lateinit var jdbcTemplate: JdbcTemplate
	@Autowired private lateinit var fakeUserGateway: FakeWorkOSUserGateway
	@Autowired private lateinit var fakeOrganizationGateway: FakeWorkOSOrganizationGateway

	@BeforeEach
	fun resetFixtures() {
		jdbcTemplate.update("delete from workos_provisioning where workos_user_id = ?", TEST_USER_ID)
		jdbcTemplate.update("delete from workos_organization_mappings where workos_user_id = ?", TEST_USER_ID)
		jdbcTemplate.update("delete from workos_identity_mappings where workos_user_id = ?", TEST_USER_ID)
		jdbcTemplate.update(
			"delete from workspace_members where user_id in (select id from users where email = ?)",
			TEST_EMAIL,
		)
		jdbcTemplate.update("delete from workspaces where created_by_user_id in (select id from users where email = ?)", TEST_EMAIL)
		jdbcTemplate.update("delete from users where email = ?", TEST_EMAIL)
		fakeUserGateway.reset()
		fakeOrganizationGateway.reset()
	}

	@AfterEach
	fun cleanupFixtures() {
		resetFixtures()
	}

	@Test
	fun verifiedUserGetsOneMappedAccountOrganizationWorkspaceAndOwnerMembership() {
		bootstrap().andExpect {
			status { isOk() }
			jsonPath("$.created") { value(true) }
			jsonPath("$.organizationId") { value(TEST_ORGANIZATION_ID) }
		}

		assertEquals(1, count("workos_identity_mappings"))
		assertEquals(1, count("workos_organization_mappings"))
		assertEquals(1, count("workos_provisioning where workos_user_id = '$TEST_USER_ID' and state = 'COMPLETED'"))
		assertEquals(1, count("users where email = '$TEST_EMAIL'"))
		assertEquals(1, count("workspaces where name = 'Personal' and created_by_user_id in (select id from users where email = '$TEST_EMAIL')"))
		assertEquals(1, count("workspace_members where workos_membership_id = '$TEST_MEMBERSHIP_ID' and role = 'OWNER'"))
		val newWorkspaceEntitlement = jdbcTemplate.queryForMap(
			"select plan, entitlement_status, access_mode from workspaces where name = 'Personal' and created_by_user_id in (select id from users where email = ?)",
			TEST_EMAIL,
		)
		assertEquals("none", newWorkspaceEntitlement["plan"])
		assertEquals("subscription_required", newWorkspaceEntitlement["entitlement_status"])
		assertEquals("read_only", newWorkspaceEntitlement["access_mode"])

		mockMvc.get("/api/me") {
			with(authenticated())
		}.andExpect {
			status { isOk() }
			jsonPath("$.user.email") { value(TEST_EMAIL) }
			jsonPath("$.workspaces[0].name") { value("Personal") }
		}
	}

	@Test
	fun repeatedBootstrapConvergesWithoutCreatingAnotherLocalOrProviderResource() {
		val first = bootstrap().andReturn().response.contentAsString
		val second = bootstrap().andExpect {
			status { isOk() }
			jsonPath("$.created") { value(false) }
			jsonPath("$.organizationId") { value(TEST_ORGANIZATION_ID) }
		}.andReturn().response.contentAsString

		assertEquals(first.substringAfter("\"userId\":\"").substringBefore('"'), second.substringAfter("\"userId\":\"").substringBefore('"'))
		assertEquals(1, count("users where email = '$TEST_EMAIL'"))
		assertEquals(1, count("workspaces where name = 'Personal' and created_by_user_id in (select id from users where email = '$TEST_EMAIL')"))
		assertEquals(1, fakeOrganizationGateway.organizationCreateCount.get())
		assertEquals(1, fakeOrganizationGateway.membershipCreateCount.get())
	}

	@Test
	fun unverifiedUserCannotBootstrapOrCreateAPlotProjection() {
		fakeUserGateway.emailVerified = false

		bootstrap().andExpect {
			status { isForbidden() }
			jsonPath("$.error") { value("EMAIL_VERIFICATION_REQUIRED") }
		}

		assertEquals(0, count("users where email = '$TEST_EMAIL'"))
		assertEquals(0, count("workos_provisioning where workos_user_id = '$TEST_USER_ID'"))
	}

	@Test
	fun legacyEmailCollisionDoesNotPerformEmailOnlyLinking() {
		jdbcTemplate.update(
			"insert into users (id, email, display_name, status, created_at, updated_at) values (?, ?, 'Legacy', 'ACTIVE', now(), now())",
			java.util.UUID.randomUUID(), TEST_EMAIL,
		)

		bootstrap().andExpect {
			status { isConflict() }
			jsonPath("$.error") { value("ACCOUNT_LINK_REQUIRED") }
		}

		assertEquals(0, fakeOrganizationGateway.organizationCreateCount.get())
		assertEquals(0, count("workos_provisioning where workos_user_id = '$TEST_USER_ID'"))
	}

	@Test
	fun providerFailureLeavesARecoverableFailedLedgerAndNoPlotAccount() {
		fakeOrganizationGateway.failure = WorkOSProviderException(
			"provider unavailable",
			IllegalStateException("timeout"),
		)

		bootstrap().andExpect {
			status { isServiceUnavailable() }
			jsonPath("$.error") { value("WORKOS_PROVIDER_UNAVAILABLE") }
		}

		assertEquals(1, count("workos_provisioning where workos_user_id = '$TEST_USER_ID' and state = 'FAILED'"))
		assertEquals(0, count("users where email = '$TEST_EMAIL'"))

		fakeOrganizationGateway.failure = null
		bootstrap().andExpect {
			status { isOk() }
			jsonPath("$.created") { value(true) }
		}
		assertEquals(1, count("users where email = '$TEST_EMAIL'"))
	}

	private fun bootstrap() = mockMvc.post("/api/account/bootstrap") {
		with(authenticated())
	}

	private fun authenticated() = jwt().jwt { token ->
		token.issuer(TEST_ISSUER).subject(TEST_USER_ID).audience(listOf(TEST_AUDIENCE))
	}

	private fun count(tableExpression: String): Int = jdbcTemplate.queryForObject(
		"select count(*) from $tableExpression",
		Int::class.java,
	) ?: 0

}

private const val TEST_USER_ID = "user_workos_bootstrap"
private const val TEST_ORGANIZATION_ID = "org_workos_bootstrap"
private const val TEST_MEMBERSHIP_ID = "membership_workos_bootstrap"
private const val TEST_EMAIL = "bootstrap@example.com"
private const val TEST_ISSUER = "https://issuer.workos.test"
private const val TEST_AUDIENCE = "plot-api"

@TestConfiguration(proxyBeanMethods = false)
class WorkOSBootstrapFakeConfiguration {
	@Bean
	@Primary
	fun fakeUserGateway() = FakeWorkOSUserGateway()

	@Bean
	@Primary
	fun fakeOrganizationGateway() = FakeWorkOSOrganizationGateway()

	@Bean
	@Primary
	fun fakeMembershipGateway() = BootstrapNoopWorkOSMembershipGateway()
}

class BootstrapNoopWorkOSMembershipGateway : WorkOSMembershipGateway {
	override fun find(organizationId: String, userId: String): WorkOSMembershipSnapshot? = null

	override fun listForUser(userId: String): List<WorkOSMembershipSnapshot> = emptyList()
}

class FakeWorkOSUserGateway : WorkOSUserGateway {
	var emailVerified = true

	override fun get(userId: String) = WorkOSUserProfile(
		id = userId,
		email = TEST_EMAIL,
		emailVerified = emailVerified,
		displayName = "Bootstrap User",
	)

	fun reset() {
		emailVerified = true
	}
}

class FakeWorkOSOrganizationGateway : WorkOSOrganizationGateway {
	val organizationCreateCount = AtomicInteger()
	val membershipCreateCount = AtomicInteger()
	var failure: RuntimeException? = null

	override fun findByExternalId(externalId: String): WorkOSOrganizationRecord? =
		if (organizationCreateCount.get() > 0) WorkOSOrganizationRecord(TEST_ORGANIZATION_ID, externalId) else null

	override fun createPersonalOrganization(name: String, externalId: String, idempotencyKey: String): WorkOSOrganizationRecord {
		failure?.let { throw it }
		organizationCreateCount.incrementAndGet()
		return WorkOSOrganizationRecord(TEST_ORGANIZATION_ID, externalId)
	}

	override fun ensureOwnerMembership(organizationId: String, userId: String, idempotencyKey: String): WorkOSMembershipRecord {
		failure?.let { throw it }
		membershipCreateCount.incrementAndGet()
		return WorkOSMembershipRecord(TEST_MEMBERSHIP_ID, organizationId, userId)
	}

	fun reset() {
		organizationCreateCount.set(0)
		membershipCreateCount.set(0)
		failure = null
	}

}
