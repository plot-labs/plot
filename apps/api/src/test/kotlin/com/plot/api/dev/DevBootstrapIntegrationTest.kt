package com.plot.api.dev

import com.plot.api.TestcontainersConfiguration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles

@SpringBootTest(properties = ["plot.dev-bootstrap.enabled=true"])
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
class DevBootstrapIntegrationTest {

	@Autowired
	private lateinit var devContext: DevContext

	@Autowired
	private lateinit var devBootstrapService: DevBootstrapService

	@Autowired
	private lateinit var jdbcTemplate: JdbcTemplate

	@Test
	fun bootstrapCreatesCanonicalDevUserWorkspaceAndMembership() {
		assertCanonicalDevUser()
		assertCanonicalDevWorkspace()
		assertCanonicalDevWorkspaceMembership()
	}

	@Test
	fun bootstrapIsIdempotent() {
		devBootstrapService.bootstrap()
		devBootstrapService.bootstrap()

		val membershipCount = jdbcTemplate.queryForObject(
			"select count(*) from workspace_members where workspace_id = ? and user_id = ?",
			Int::class.java,
			devContext.devWorkspaceId,
			devContext.devUserId,
		)

		assertEquals(1, membershipCount)
	}

	@Test
	fun bootstrapReconcilesStaleRowsWithFixedIds() {
		jdbcTemplate.update(
			"""
			update users
			set email = 'stale@plot.local',
			    display_name = 'Stale User',
			    status = 'DISABLED'
			where id = ?
			""".trimIndent(),
			devContext.devUserId,
		)
		jdbcTemplate.update(
			"""
			update workspaces
			set name = 'Stale Workspace',
			    slug = 'stale-workspace',
			    created_by_user_id = null,
			    status = 'ARCHIVED'
			where id = ?
			""".trimIndent(),
			devContext.devWorkspaceId,
		)
		jdbcTemplate.update(
			"""
			update workspace_members
			set role = 'VIEWER',
			    status = 'REMOVED'
			where id = ?
			""".trimIndent(),
			devContext.devWorkspaceMemberId,
		)

		devBootstrapService.bootstrap()

		assertCanonicalDevUser()
		assertCanonicalDevWorkspace()
		assertCanonicalDevWorkspaceMembership()
	}

	@Test
	fun bootstrapMovesConflictingDevUserEmailToCanonicalUser() {
		devBootstrapService.bootstrap()
		val conflictingUserId = UUID.randomUUID()

		jdbcTemplate.update(
			"""
			update users
			set email = ?,
			    display_name = 'Stale Canonical User',
			    status = 'DISABLED'
			where id = ?
			""".trimIndent(),
			"canonical-stale-$conflictingUserId@plot.local",
			devContext.devUserId,
		)
		jdbcTemplate.update(
			"""
			insert into users (id, email, display_name, status, created_at, updated_at)
			values (?, 'dev@plot.local', 'Conflicting Dev User', 'ACTIVE', now(), now())
			""".trimIndent(),
			conflictingUserId,
		)

		devBootstrapService.bootstrap()

		assertCanonicalDevUser()
		val conflictingEmail = jdbcTemplate.queryForObject(
			"select email from users where id = ?",
			String::class.java,
			conflictingUserId,
		)
		assertEquals("dev-conflict-$conflictingUserId@plot.local", conflictingEmail)
	}

	@Test
	fun bootstrapMovesConflictingDevWorkspaceSlugToCanonicalWorkspace() {
		devBootstrapService.bootstrap()
		val conflictingWorkspaceId = UUID.randomUUID()

		jdbcTemplate.update(
			"""
			update workspaces
			set name = 'Stale Canonical Workspace',
			    slug = ?,
			    status = 'ARCHIVED'
			where id = ?
			""".trimIndent(),
			"canonical-stale-$conflictingWorkspaceId",
			devContext.devWorkspaceId,
		)
		jdbcTemplate.update(
			"""
			insert into workspaces (id, name, slug, created_by_user_id, status, created_at, updated_at)
			values (?, 'Conflicting Dev Workspace', 'dev-workspace', ?, 'ACTIVE', now(), now())
			""".trimIndent(),
			conflictingWorkspaceId,
			devContext.devUserId,
		)

		devBootstrapService.bootstrap()

		assertCanonicalDevWorkspace()
		val conflictingSlug = jdbcTemplate.queryForObject(
			"select slug from workspaces where id = ?",
			String::class.java,
			conflictingWorkspaceId,
		)
		assertEquals("dev-workspace-conflict-$conflictingWorkspaceId", conflictingSlug)
	}

	@Test
	fun bootstrapReplacesWrongIdWorkspaceMemberTupleWithCanonicalMember() {
		devBootstrapService.bootstrap()
		val staleUserId = UUID.randomUUID()
		val staleWorkspaceId = UUID.randomUUID()
		val wrongWorkspaceMemberId = UUID.randomUUID()

		jdbcTemplate.update(
			"""
			insert into users (id, email, display_name, status, created_at, updated_at)
			values (?, ?, 'Stale Member User', 'ACTIVE', now(), now())
			""".trimIndent(),
			staleUserId,
			"stale-member-$staleUserId@plot.local",
		)
		jdbcTemplate.update(
			"""
			insert into workspaces (id, name, slug, created_by_user_id, status, created_at, updated_at)
			values (?, 'Stale Member Workspace', ?, ?, 'ACTIVE', now(), now())
			""".trimIndent(),
			staleWorkspaceId,
			"stale-member-workspace-$staleWorkspaceId",
			staleUserId,
		)
		jdbcTemplate.update(
			"""
			update workspace_members
			set workspace_id = ?,
			    user_id = ?,
			    role = 'VIEWER',
			    status = 'REMOVED'
			where id = ?
			""".trimIndent(),
			staleWorkspaceId,
			staleUserId,
			devContext.devWorkspaceMemberId,
		)
		jdbcTemplate.update(
			"""
			insert into workspace_members (id, workspace_id, user_id, role, status, joined_at, created_at, updated_at)
			values (?, ?, ?, 'VIEWER', 'ACTIVE', now(), now(), now())
			""".trimIndent(),
			wrongWorkspaceMemberId,
			devContext.devWorkspaceId,
			devContext.devUserId,
		)

		devBootstrapService.bootstrap()

		assertCanonicalDevWorkspaceMembership()
		val membershipCount = jdbcTemplate.queryForObject(
			"select count(*) from workspace_members where workspace_id = ? and user_id = ?",
			Int::class.java,
			devContext.devWorkspaceId,
			devContext.devUserId,
		)
		assertEquals(1, membershipCount)
	}

	private fun assertCanonicalDevUser() {
		val user = jdbcTemplate.queryForMap(
			"""
			select email, display_name, status, created_at, updated_at
			from users
			where id = ?
			""".trimIndent(),
			devContext.devUserId,
		)

		assertEquals("dev@plot.local", user["email"])
		assertEquals("Dev User", user["display_name"])
		assertEquals("ACTIVE", user["status"])
		assertNotNull(user["created_at"])
		assertNotNull(user["updated_at"])
	}

	private fun assertCanonicalDevWorkspace() {
		val workspace = jdbcTemplate.queryForMap(
			"""
			select name, slug, created_by_user_id, status, created_at, updated_at
			from workspaces
			where id = ?
			""".trimIndent(),
			devContext.devWorkspaceId,
		)

		assertEquals("Dev Workspace", workspace["name"])
		assertEquals("dev-workspace", workspace["slug"])
		assertEquals(devContext.devUserId, workspace["created_by_user_id"])
		assertEquals("ACTIVE", workspace["status"])
		assertNotNull(workspace["created_at"])
		assertNotNull(workspace["updated_at"])
	}

	private fun assertCanonicalDevWorkspaceMembership() {
		val membership = jdbcTemplate.queryForMap(
			"""
			select workspace_id, user_id, role, status, joined_at, created_at, updated_at
			from workspace_members
			where id = ?
			""".trimIndent(),
			devContext.devWorkspaceMemberId,
		)

		assertEquals(devContext.devWorkspaceId, membership["workspace_id"])
		assertEquals(devContext.devUserId, membership["user_id"])
		assertEquals("OWNER", membership["role"])
		assertEquals("ACTIVE", membership["status"])
		assertNotNull(membership["joined_at"])
		assertNotNull(membership["created_at"])
		assertNotNull(membership["updated_at"])
	}

}
