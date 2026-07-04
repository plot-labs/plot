# API Domain Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the first Kotlin Spring Boot API domain foundation for workspace-scoped work sessions, tasks, and writing blocks using a fixed development context.

**Architecture:** Implement a schema-first Spring Boot backend with Flyway, JPA entities, repository interfaces, transactional services, and MVC controllers. Domain services apply `DevContext` workspace scoping and status defaults; controllers return DTOs and never serialize entities. Tests run through MockMvc against Testcontainers PostgreSQL so Flyway, JPA mappings, bootstrap data, and HTTP contracts are verified together.

**Tech Stack:** Kotlin 2.2.21, Java 21, Spring Boot 4.0.7, Spring Web MVC, Spring Data JPA, Flyway, PostgreSQL/pgvector Testcontainers, Jakarta Validation, Jackson Kotlin, MockMvc, JUnit 5.

---

## Source Spec

Use [API Domain Foundation Design](../specs/2026-07-04-api-domain-foundation-design.md) as the contract. This plan intentionally does not create `docs/superpowers`.

## Scope Check

This spec is one cohesive backend foundation. It touches schema, development context, four API surfaces, and integration tests, but each task below produces working, testable software and commits independently.

Out of scope for execution:

- authentication and authorization
- delete/archive/cancel endpoints
- workspace member management API
- agent runs, generation runs, packs, variants, citations
- source repositories/imports/connections
- automation recipes and run history
- pagination

## File Structure

Create or modify these files.

### Existing Files To Modify

- `apps/api/src/main/resources/application.properties`  
  Add local API defaults that make JPA/Flyway behavior explicit.

- `apps/api/src/test/kotlin/com/plot/api/ApiApplicationTests.kt`  
  Replace the trivial class-name test with a Spring/Testcontainers context smoke test.

### New Migration

- `apps/api/src/main/resources/db/migration/V1__core_schema.sql`  
  Create `users`, `workspaces`, `workspace_members`, `work_sessions`, `tasks`, and `writing_blocks`.

### New Common Package

- `apps/api/src/main/kotlin/com/plot/api/common/UuidGenerator.kt`  
  Application-side UUIDv7 generator.

- `apps/api/src/main/kotlin/com/plot/api/common/ApiErrorResponse.kt`  
  Stable JSON error DTO.

- `apps/api/src/main/kotlin/com/plot/api/common/ApiException.kt`  
  Small HTTP-aware domain exception.

- `apps/api/src/main/kotlin/com/plot/api/common/ApiExceptionHandler.kt`  
  `@RestControllerAdvice` for `ApiException`, validation failures, and unreadable JSON.

- `apps/api/src/main/kotlin/com/plot/api/common/SecurityConfig.kt`  
  Development security configuration allowing unauthenticated API access and disabling CSRF for JSON endpoints.

### New Dev Package

- `apps/api/src/main/kotlin/com/plot/api/dev/DevContext.kt`  
  Fixed development user/workspace/member IDs.

- `apps/api/src/main/kotlin/com/plot/api/dev/DevBootstrap.kt`  
  Idempotent startup seed for dev user, workspace, and membership.

### New Workspace Package

- `apps/api/src/main/kotlin/com/plot/api/workspace/User.kt`
- `apps/api/src/main/kotlin/com/plot/api/workspace/UserRepository.kt`
- `apps/api/src/main/kotlin/com/plot/api/workspace/Workspace.kt`
- `apps/api/src/main/kotlin/com/plot/api/workspace/WorkspaceRepository.kt`
- `apps/api/src/main/kotlin/com/plot/api/workspace/WorkspaceMember.kt`
- `apps/api/src/main/kotlin/com/plot/api/workspace/WorkspaceMemberRepository.kt`
- `apps/api/src/main/kotlin/com/plot/api/workspace/WorkspaceDtos.kt`
- `apps/api/src/main/kotlin/com/plot/api/workspace/WorkspaceService.kt`
- `apps/api/src/main/kotlin/com/plot/api/workspace/WorkspaceController.kt`

### New Work Session Package

- `apps/api/src/main/kotlin/com/plot/api/worksession/WorkSession.kt`
- `apps/api/src/main/kotlin/com/plot/api/worksession/WorkSessionRepository.kt`
- `apps/api/src/main/kotlin/com/plot/api/worksession/WorkSessionDtos.kt`
- `apps/api/src/main/kotlin/com/plot/api/worksession/WorkSessionService.kt`
- `apps/api/src/main/kotlin/com/plot/api/worksession/WorkSessionController.kt`

### New Task Package

- `apps/api/src/main/kotlin/com/plot/api/task/Task.kt`
- `apps/api/src/main/kotlin/com/plot/api/task/TaskRepository.kt`
- `apps/api/src/main/kotlin/com/plot/api/task/TaskDtos.kt`
- `apps/api/src/main/kotlin/com/plot/api/task/TaskService.kt`
- `apps/api/src/main/kotlin/com/plot/api/task/TaskController.kt`

### New Writing Block Package

- `apps/api/src/main/kotlin/com/plot/api/writingblock/WritingBlock.kt`
- `apps/api/src/main/kotlin/com/plot/api/writingblock/WritingBlockRepository.kt`
- `apps/api/src/main/kotlin/com/plot/api/writingblock/WritingBlockDtos.kt`
- `apps/api/src/main/kotlin/com/plot/api/writingblock/WritingBlockService.kt`
- `apps/api/src/main/kotlin/com/plot/api/writingblock/WritingBlockController.kt`

### New Tests

- `apps/api/src/test/kotlin/com/plot/api/common/UuidGeneratorTest.kt`
- `apps/api/src/test/kotlin/com/plot/api/dev/DevBootstrapIntegrationTest.kt`
- `apps/api/src/test/kotlin/com/plot/api/workspace/WorkspaceApiIntegrationTest.kt`
- `apps/api/src/test/kotlin/com/plot/api/worksession/WorkSessionApiIntegrationTest.kt`
- `apps/api/src/test/kotlin/com/plot/api/task/TaskApiIntegrationTest.kt`
- `apps/api/src/test/kotlin/com/plot/api/writingblock/WritingBlockApiIntegrationTest.kt`

## Implementation Rules

- Use scalar UUID foreign keys in entities for this foundation. Do not add JPA relationship fields unless a task explicitly requires them.
- Generate IDs in services with `UuidGenerator`. Do not use database UUID defaults.
- Use `Instant` for timestamp fields.
- Store status as `String` constants, not enums, to keep the first migration simple.
- All list/detail/update queries for workspace-scoped resources must include `DevContext.devWorkspaceId`.
- Do not expose `workspaceId` in session/task/writing-block responses.
- Do not include `status` in update request DTOs.
- Do not add `session_type`, `task_type`, `objective`, `due_at`, `priority`, `review_mode`, or `source_scope`.
- Keep each commit small and run `just test-api` before the final commit.

---

## Task 1: Common UUID And Error Infrastructure

**Files:**
- Create: `apps/api/src/main/kotlin/com/plot/api/common/UuidGenerator.kt`
- Create: `apps/api/src/main/kotlin/com/plot/api/common/ApiErrorResponse.kt`
- Create: `apps/api/src/main/kotlin/com/plot/api/common/ApiException.kt`
- Create: `apps/api/src/main/kotlin/com/plot/api/common/ApiExceptionHandler.kt`
- Create: `apps/api/src/main/kotlin/com/plot/api/common/SecurityConfig.kt`
- Create: `apps/api/src/test/kotlin/com/plot/api/common/UuidGeneratorTest.kt`

- [ ] **Step 1: Write the failing UUID test**

Create `apps/api/src/test/kotlin/com/plot/api/common/UuidGeneratorTest.kt`:

```kotlin
package com.plot.api.common

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class UuidGeneratorTest {

	@Test
	fun generatedIdsUseUuidVersion7() {
		val generator = UuidGenerator()

		val id = generator.next()

		assertEquals(7, id.version())
		assertEquals(2, id.variant())
	}

	@Test
	fun generatedIdsAreMostlyTimeOrdered() {
		val generator = UuidGenerator()

		val first = generator.next()
		Thread.sleep(2)
		val second = generator.next()

		assertTrue(first.toString() < second.toString())
	}
}
```

- [ ] **Step 2: Run the UUID test to verify it fails**

Run:

```bash
cd apps/api && ./gradlew test --tests com.plot.api.common.UuidGeneratorTest
```

Expected: FAIL because `UuidGenerator` does not exist.

- [ ] **Step 3: Implement UUIDv7 and common web infrastructure**

Create `apps/api/src/main/kotlin/com/plot/api/common/UuidGenerator.kt`:

```kotlin
package com.plot.api.common

import java.security.SecureRandom
import java.time.Clock
import java.util.UUID
import org.springframework.stereotype.Component

@Component
class UuidGenerator(
	private val clock: Clock = Clock.systemUTC(),
) {
	private val random = SecureRandom()

	fun next(): UUID {
		val timestampMillis = clock.millis() and 0x0000FFFFFFFFFFFFL
		val randomA = random.nextLong() and 0xFFFL
		val randomB = random.nextLong() and 0x3FFFFFFFFFFFFFFFL

		val mostSignificantBits =
			(timestampMillis shl 16) or
				(0x7L shl 12) or
				randomA

		val leastSignificantBits =
			(0x2L shl 62) or
				randomB

		return UUID(mostSignificantBits, leastSignificantBits)
	}
}
```

Create `apps/api/src/main/kotlin/com/plot/api/common/ApiErrorResponse.kt`:

```kotlin
package com.plot.api.common

data class ApiErrorResponse(
	val error: String,
	val message: String,
)
```

Create `apps/api/src/main/kotlin/com/plot/api/common/ApiException.kt`:

```kotlin
package com.plot.api.common

import org.springframework.http.HttpStatus

class ApiException(
	val status: HttpStatus,
	val error: String,
	override val message: String,
) : RuntimeException(message)
```

Create `apps/api/src/main/kotlin/com/plot/api/common/ApiExceptionHandler.kt`:

```kotlin
package com.plot.api.common

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class ApiExceptionHandler {

	@ExceptionHandler(ApiException::class)
	fun handleApiException(exception: ApiException): ResponseEntity<ApiErrorResponse> {
		return ResponseEntity
			.status(exception.status)
			.body(ApiErrorResponse(exception.error, exception.message))
	}

	@ExceptionHandler(MethodArgumentNotValidException::class)
	fun handleValidationException(exception: MethodArgumentNotValidException): ResponseEntity<ApiErrorResponse> {
		val message = exception.bindingResult.fieldErrors.firstOrNull()?.defaultMessage
			?: "Request validation failed"
		return ResponseEntity
			.status(HttpStatus.BAD_REQUEST)
			.body(ApiErrorResponse("BAD_REQUEST", message))
	}

	@ExceptionHandler(HttpMessageNotReadableException::class)
	fun handleUnreadableJson(exception: HttpMessageNotReadableException): ResponseEntity<ApiErrorResponse> {
		return ResponseEntity
			.status(HttpStatus.BAD_REQUEST)
			.body(ApiErrorResponse("BAD_REQUEST", "Request body is invalid"))
	}
}
```

Create `apps/api/src/main/kotlin/com/plot/api/common/SecurityConfig.kt`:

```kotlin
package com.plot.api.common

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.web.SecurityFilterChain

@Configuration
@EnableWebSecurity
class SecurityConfig {

	@Bean
	fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
		return http
			.csrf { it.disable() }
			.authorizeHttpRequests { requests ->
				requests.anyRequest().permitAll()
			}
			.build()
	}
}
```

- [ ] **Step 4: Run the UUID test to verify it passes**

Run:

```bash
cd apps/api && ./gradlew test --tests com.plot.api.common.UuidGeneratorTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/kotlin/com/plot/api/common apps/api/src/test/kotlin/com/plot/api/common
git commit -m "feat(api): add common api infrastructure"
```

---

## Task 2: Flyway Core Schema

**Files:**
- Modify: `apps/api/src/test/kotlin/com/plot/api/ApiApplicationTests.kt`
- Modify: `apps/api/src/main/resources/application.properties`
- Create: `apps/api/src/main/resources/db/migration/V1__core_schema.sql`

- [ ] **Step 1: Replace the smoke test with a Spring/Testcontainers schema test**

Replace `apps/api/src/test/kotlin/com/plot/api/ApiApplicationTests.kt`:

```kotlin
package com.plot.api

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class ApiApplicationTests {

	@Autowired
	private lateinit var jdbcTemplate: JdbcTemplate

	@Test
	fun contextStartsAndFlywayCreatesCoreTables() {
		val tableCount = jdbcTemplate.queryForObject(
			"""
			select count(*)
			from information_schema.tables
			where table_schema = 'public'
			  and table_name in (
			    'users',
			    'workspaces',
			    'workspace_members',
			    'work_sessions',
			    'tasks',
			    'writing_blocks'
			  )
			""".trimIndent(),
			Int::class.java,
		)

		assertEquals(6, tableCount)
	}
}
```

- [ ] **Step 2: Run the schema test to verify it fails**

Run:

```bash
cd apps/api && ./gradlew test --tests com.plot.api.ApiApplicationTests
```

Expected: FAIL because the six tables do not exist.

- [ ] **Step 3: Add application defaults and the Flyway migration**

Append to `apps/api/src/main/resources/application.properties`:

```properties
spring.jpa.open-in-view=false
spring.jpa.hibernate.ddl-auto=validate
spring.flyway.enabled=true
```

Create `apps/api/src/main/resources/db/migration/V1__core_schema.sql`:

```sql
create table users (
  id uuid primary key,
  email text not null unique,
  display_name text not null,
  status varchar not null,
  created_at timestamptz not null,
  updated_at timestamptz not null
);

create table workspaces (
  id uuid primary key,
  name text not null,
  slug text not null unique,
  created_by_user_id uuid references users(id),
  status varchar not null,
  created_at timestamptz not null,
  updated_at timestamptz not null
);

create table workspace_members (
  id uuid primary key,
  workspace_id uuid not null references workspaces(id),
  user_id uuid not null references users(id),
  role varchar not null,
  status varchar not null,
  joined_at timestamptz not null,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  unique (workspace_id, id),
  unique (workspace_id, user_id)
);

create table work_sessions (
  id uuid primary key,
  workspace_id uuid not null references workspaces(id),
  title text,
  status varchar not null,
  created_by_user_id uuid references users(id),
  last_activity_at timestamptz,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  unique (workspace_id, id)
);

create table tasks (
  id uuid primary key,
  workspace_id uuid not null references workspaces(id),
  work_session_id uuid,
  title text not null,
  status varchar not null,
  created_by_user_id uuid references users(id),
  last_activity_at timestamptz,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  unique (workspace_id, id),
  foreign key (workspace_id, work_session_id)
    references work_sessions(workspace_id, id)
);

create table writing_blocks (
  id uuid primary key,
  workspace_id uuid not null references workspaces(id),
  source_origin varchar not null,
  source_kind varchar not null,
  title text,
  body text,
  url text,
  canonical_url text,
  author text,
  platform varchar,
  metadata jsonb,
  content_hash text,
  source_created_at timestamptz,
  source_updated_at timestamptz,
  ingested_at timestamptz not null,
  status varchar not null,
  created_by_user_id uuid references users(id),
  created_at timestamptz not null,
  updated_at timestamptz not null,
  unique (workspace_id, id)
);

create index work_sessions_workspace_created_idx
  on work_sessions(workspace_id, created_at desc);

create index tasks_workspace_created_idx
  on tasks(workspace_id, created_at desc);

create index writing_blocks_workspace_created_idx
  on writing_blocks(workspace_id, created_at desc);

create index writing_blocks_workspace_content_hash_idx
  on writing_blocks(workspace_id, content_hash);
```

- [ ] **Step 4: Run the schema test to verify it passes**

Run:

```bash
cd apps/api && ./gradlew test --tests com.plot.api.ApiApplicationTests
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/resources/application.properties apps/api/src/main/resources/db/migration/V1__core_schema.sql apps/api/src/test/kotlin/com/plot/api/ApiApplicationTests.kt
git commit -m "feat(api): add core database schema"
```

---

## Task 3: Workspace Entities And Dev Bootstrap

**Files:**
- Create: `apps/api/src/main/kotlin/com/plot/api/workspace/User.kt`
- Create: `apps/api/src/main/kotlin/com/plot/api/workspace/UserRepository.kt`
- Create: `apps/api/src/main/kotlin/com/plot/api/workspace/Workspace.kt`
- Create: `apps/api/src/main/kotlin/com/plot/api/workspace/WorkspaceRepository.kt`
- Create: `apps/api/src/main/kotlin/com/plot/api/workspace/WorkspaceMember.kt`
- Create: `apps/api/src/main/kotlin/com/plot/api/workspace/WorkspaceMemberRepository.kt`
- Create: `apps/api/src/main/kotlin/com/plot/api/dev/DevContext.kt`
- Create: `apps/api/src/main/kotlin/com/plot/api/dev/DevBootstrap.kt`
- Create: `apps/api/src/test/kotlin/com/plot/api/dev/DevBootstrapIntegrationTest.kt`

- [ ] **Step 1: Write failing bootstrap tests**

Create `apps/api/src/test/kotlin/com/plot/api/dev/DevBootstrapIntegrationTest.kt`:

```kotlin
package com.plot.api.dev

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import com.plot.api.TestcontainersConfiguration

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class DevBootstrapIntegrationTest {

	@Autowired
	private lateinit var devContext: DevContext

	@Autowired
	private lateinit var devBootstrap: DevBootstrap

	@Autowired
	private lateinit var jdbcTemplate: JdbcTemplate

	@Test
	fun bootstrapCreatesDevUserWorkspaceAndMembership() {
		assertEquals(1, countRows("users", devContext.devUserId))
		assertEquals(1, countRows("workspaces", devContext.devWorkspaceId))
		assertEquals(1, countRows("workspace_members", devContext.devWorkspaceMemberId))
	}

	@Test
	fun bootstrapIsIdempotent() {
		devBootstrap.run()
		devBootstrap.run()

		val membershipCount = jdbcTemplate.queryForObject(
			"select count(*) from workspace_members where workspace_id = ? and user_id = ?",
			Int::class.java,
			devContext.devWorkspaceId,
			devContext.devUserId,
		)

		assertEquals(1, membershipCount)
	}

	private fun countRows(tableName: String, id: Any): Int {
		return jdbcTemplate.queryForObject(
			"select count(*) from $tableName where id = ?",
			Int::class.java,
			id,
		) ?: 0
	}
}
```

- [ ] **Step 2: Run the bootstrap tests to verify they fail**

Run:

```bash
cd apps/api && ./gradlew test --tests com.plot.api.dev.DevBootstrapIntegrationTest
```

Expected: FAIL because `DevContext`, `DevBootstrap`, and workspace entities do not exist.

- [ ] **Step 3: Add workspace entities and repositories**

Create `apps/api/src/main/kotlin/com/plot/api/workspace/User.kt`:

```kotlin
package com.plot.api.workspace

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "users")
class User(
	@Id
	var id: UUID,
	var email: String,
	var displayName: String,
	var status: String,
	var createdAt: Instant,
	var updatedAt: Instant,
)
```

Create `apps/api/src/main/kotlin/com/plot/api/workspace/UserRepository.kt`:

```kotlin
package com.plot.api.workspace

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface UserRepository : JpaRepository<User, UUID>
```

Create `apps/api/src/main/kotlin/com/plot/api/workspace/Workspace.kt`:

```kotlin
package com.plot.api.workspace

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "workspaces")
class Workspace(
	@Id
	var id: UUID,
	var name: String,
	var slug: String,
	var createdByUserId: UUID?,
	var status: String,
	var createdAt: Instant,
	var updatedAt: Instant,
)
```

Create `apps/api/src/main/kotlin/com/plot/api/workspace/WorkspaceRepository.kt`:

```kotlin
package com.plot.api.workspace

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface WorkspaceRepository : JpaRepository<Workspace, UUID> {
	fun findByIdAndStatus(id: UUID, status: String): Workspace?
	fun findAllByIdAndStatus(id: UUID, status: String): List<Workspace>
}
```

Create `apps/api/src/main/kotlin/com/plot/api/workspace/WorkspaceMember.kt`:

```kotlin
package com.plot.api.workspace

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "workspace_members")
class WorkspaceMember(
	@Id
	var id: UUID,
	var workspaceId: UUID,
	var userId: UUID,
	var role: String,
	var status: String,
	var joinedAt: Instant,
	var createdAt: Instant,
	var updatedAt: Instant,
)
```

Create `apps/api/src/main/kotlin/com/plot/api/workspace/WorkspaceMemberRepository.kt`:

```kotlin
package com.plot.api.workspace

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface WorkspaceMemberRepository : JpaRepository<WorkspaceMember, UUID> {
	fun findByWorkspaceIdAndUserId(workspaceId: UUID, userId: UUID): WorkspaceMember?
}
```

- [ ] **Step 4: Add development context and bootstrap**

Create `apps/api/src/main/kotlin/com/plot/api/dev/DevContext.kt`:

```kotlin
package com.plot.api.dev

import java.util.UUID
import org.springframework.stereotype.Component

@Component
class DevContext {
	val devUserId: UUID = UUID.fromString("018fd000-0000-7000-8000-000000000001")
	val devWorkspaceId: UUID = UUID.fromString("018fd000-0000-7000-8000-000000000002")
	val devWorkspaceMemberId: UUID = UUID.fromString("018fd000-0000-7000-8000-000000000003")
}
```

Create `apps/api/src/main/kotlin/com/plot/api/dev/DevBootstrap.kt`:

```kotlin
package com.plot.api.dev

import com.plot.api.workspace.User
import com.plot.api.workspace.UserRepository
import com.plot.api.workspace.Workspace
import com.plot.api.workspace.WorkspaceMember
import com.plot.api.workspace.WorkspaceMemberRepository
import com.plot.api.workspace.WorkspaceRepository
import java.time.Instant
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class DevBootstrap(
	private val devContext: DevContext,
	private val userRepository: UserRepository,
	private val workspaceRepository: WorkspaceRepository,
	private val workspaceMemberRepository: WorkspaceMemberRepository,
) : ApplicationRunner {

	override fun run(args: ApplicationArguments) {
		run()
	}

	@Transactional
	fun run() {
		val now = Instant.now()

		if (!userRepository.existsById(devContext.devUserId)) {
			userRepository.save(
				User(
					id = devContext.devUserId,
					email = "dev@plot.local",
					displayName = "Dev User",
					status = "ACTIVE",
					createdAt = now,
					updatedAt = now,
				),
			)
		}

		if (!workspaceRepository.existsById(devContext.devWorkspaceId)) {
			workspaceRepository.save(
				Workspace(
					id = devContext.devWorkspaceId,
					name = "Dev Workspace",
					slug = "dev-workspace",
					createdByUserId = devContext.devUserId,
					status = "ACTIVE",
					createdAt = now,
					updatedAt = now,
				),
			)
		}

		if (workspaceMemberRepository.findByWorkspaceIdAndUserId(devContext.devWorkspaceId, devContext.devUserId) == null) {
			workspaceMemberRepository.save(
				WorkspaceMember(
					id = devContext.devWorkspaceMemberId,
					workspaceId = devContext.devWorkspaceId,
					userId = devContext.devUserId,
					role = "OWNER",
					status = "ACTIVE",
					joinedAt = now,
					createdAt = now,
					updatedAt = now,
				),
			)
		}
	}
}
```

- [ ] **Step 5: Run the bootstrap tests to verify they pass**

Run:

```bash
cd apps/api && ./gradlew test --tests com.plot.api.dev.DevBootstrapIntegrationTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add apps/api/src/main/kotlin/com/plot/api/workspace apps/api/src/main/kotlin/com/plot/api/dev apps/api/src/test/kotlin/com/plot/api/dev
git commit -m "feat(api): add dev context bootstrap"
```

---

## Task 4: Workspace API

**Files:**
- Create: `apps/api/src/main/kotlin/com/plot/api/workspace/WorkspaceDtos.kt`
- Create: `apps/api/src/main/kotlin/com/plot/api/workspace/WorkspaceService.kt`
- Create: `apps/api/src/main/kotlin/com/plot/api/workspace/WorkspaceController.kt`
- Create: `apps/api/src/test/kotlin/com/plot/api/workspace/WorkspaceApiIntegrationTest.kt`

- [ ] **Step 1: Write failing workspace API tests**

Create `apps/api/src/test/kotlin/com/plot/api/workspace/WorkspaceApiIntegrationTest.kt`:

```kotlin
package com.plot.api.workspace

import com.plot.api.TestcontainersConfiguration
import com.plot.api.dev.DevContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class WorkspaceApiIntegrationTest {

	@Autowired
	private lateinit var mockMvc: MockMvc

	@Autowired
	private lateinit var devContext: DevContext

	@Test
	fun listReturnsDevWorkspace() {
		mockMvc.get("/api/workspaces")
			.andExpect {
				status { isOk() }
				jsonPath("$[0].id") { value(devContext.devWorkspaceId.toString()) }
				jsonPath("$[0].status") { value("ACTIVE") }
			}
	}

	@Test
	fun detailReturnsDevWorkspace() {
		mockMvc.get("/api/workspaces/${devContext.devWorkspaceId}")
			.andExpect {
				status { isOk() }
				jsonPath("$.id") { value(devContext.devWorkspaceId.toString()) }
				jsonPath("$.status") { value("ACTIVE") }
			}
	}

	@Test
	fun patchUpdatesNameAndSlug() {
		mockMvc.patch("/api/workspaces/${devContext.devWorkspaceId}") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"name":"Plot Dev","slug":"plot-dev"}"""
		}.andExpect {
			status { isOk() }
			jsonPath("$.name") { value("Plot Dev") }
			jsonPath("$.slug") { value("plot-dev") }
			jsonPath("$.status") { value("ACTIVE") }
		}
	}

	@Test
	fun patchRejectsBlankName() {
		mockMvc.patch("/api/workspaces/${devContext.devWorkspaceId}") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"name":" ","slug":"plot-dev"}"""
		}.andExpect {
			status { isBadRequest() }
			jsonPath("$.error") { value("BAD_REQUEST") }
		}
	}
}
```

- [ ] **Step 2: Run workspace API tests to verify they fail**

Run:

```bash
cd apps/api && ./gradlew test --tests com.plot.api.workspace.WorkspaceApiIntegrationTest
```

Expected: FAIL with 404 responses because `/api/workspaces` is not implemented.

- [ ] **Step 3: Implement DTOs, service, and controller**

Create `apps/api/src/main/kotlin/com/plot/api/workspace/WorkspaceDtos.kt`:

```kotlin
package com.plot.api.workspace

import jakarta.validation.constraints.NotBlank
import java.time.Instant
import java.util.UUID

data class UpdateWorkspaceRequest(
	@field:NotBlank(message = "Workspace name must not be blank")
	val name: String,
	@field:NotBlank(message = "Workspace slug must not be blank")
	val slug: String,
)

data class WorkspaceResponse(
	val id: UUID,
	val name: String,
	val slug: String,
	val status: String,
	val createdAt: Instant,
	val updatedAt: Instant,
)

fun Workspace.toResponse(): WorkspaceResponse {
	return WorkspaceResponse(
		id = id,
		name = name,
		slug = slug,
		status = status,
		createdAt = createdAt,
		updatedAt = updatedAt,
	)
}
```

Create `apps/api/src/main/kotlin/com/plot/api/workspace/WorkspaceService.kt`:

```kotlin
package com.plot.api.workspace

import com.plot.api.common.ApiException
import com.plot.api.dev.DevContext
import java.time.Instant
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class WorkspaceService(
	private val devContext: DevContext,
	private val workspaceRepository: WorkspaceRepository,
) {

	@Transactional(readOnly = true)
	fun list(): List<WorkspaceResponse> {
		return workspaceRepository
			.findAllByIdAndStatus(devContext.devWorkspaceId, "ACTIVE")
			.map { it.toResponse() }
	}

	@Transactional(readOnly = true)
	fun get(id: UUID): WorkspaceResponse {
		return findDevWorkspace(id).toResponse()
	}

	@Transactional
	fun update(id: UUID, request: UpdateWorkspaceRequest): WorkspaceResponse {
		val workspace = findDevWorkspace(id)
		workspace.name = request.name.trim()
		workspace.slug = request.slug.trim()
		workspace.updatedAt = Instant.now()
		return workspace.toResponse()
	}

	private fun findDevWorkspace(id: UUID): Workspace {
		if (id != devContext.devWorkspaceId) {
			throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Workspace not found")
		}

		return workspaceRepository.findByIdAndStatus(id, "ACTIVE")
			?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Workspace not found")
	}
}
```

Create `apps/api/src/main/kotlin/com/plot/api/workspace/WorkspaceController.kt`:

```kotlin
package com.plot.api.workspace

import jakarta.validation.Valid
import java.util.UUID
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/workspaces")
class WorkspaceController(
	private val workspaceService: WorkspaceService,
) {

	@GetMapping
	fun list(): List<WorkspaceResponse> {
		return workspaceService.list()
	}

	@GetMapping("/{id}")
	fun get(@PathVariable id: UUID): WorkspaceResponse {
		return workspaceService.get(id)
	}

	@PatchMapping("/{id}")
	fun update(
		@PathVariable id: UUID,
		@Valid @RequestBody request: UpdateWorkspaceRequest,
	): WorkspaceResponse {
		return workspaceService.update(id, request)
	}
}
```

- [ ] **Step 4: Run workspace API tests to verify they pass**

Run:

```bash
cd apps/api && ./gradlew test --tests com.plot.api.workspace.WorkspaceApiIntegrationTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/kotlin/com/plot/api/workspace apps/api/src/test/kotlin/com/plot/api/workspace
git commit -m "feat(api): add workspace api"
```

---

## Task 5: Work Session API

**Files:**
- Create: `apps/api/src/main/kotlin/com/plot/api/worksession/WorkSession.kt`
- Create: `apps/api/src/main/kotlin/com/plot/api/worksession/WorkSessionRepository.kt`
- Create: `apps/api/src/main/kotlin/com/plot/api/worksession/WorkSessionDtos.kt`
- Create: `apps/api/src/main/kotlin/com/plot/api/worksession/WorkSessionService.kt`
- Create: `apps/api/src/main/kotlin/com/plot/api/worksession/WorkSessionController.kt`
- Create: `apps/api/src/test/kotlin/com/plot/api/worksession/WorkSessionApiIntegrationTest.kt`

- [ ] **Step 1: Write failing work session API tests**

Create `apps/api/src/test/kotlin/com/plot/api/worksession/WorkSessionApiIntegrationTest.kt`:

```kotlin
package com.plot.api.worksession

import com.plot.api.TestcontainersConfiguration
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class WorkSessionApiIntegrationTest {

	@Autowired
	private lateinit var mockMvc: MockMvc

	@Test
	fun createListDetailAndUpdateSession() {
		val location = mockMvc.post("/api/sessions") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"title":"Prepare July update"}"""
		}.andExpect {
			status { isOk() }
			jsonPath("$.id") { exists() }
			jsonPath("$.title") { value("Prepare July update") }
			jsonPath("$.status") { value("OPEN") }
			jsonPath("$.workspaceId") { doesNotExist() }
		}.andReturn().response.contentAsString

		val sessionId = Regex(""""id":"([^"]+)"""").find(location)!!.groupValues[1]

		mockMvc.get("/api/sessions")
			.andExpect {
				status { isOk() }
				jsonPath("$[0].id") { value(sessionId) }
				jsonPath("$[0].workspaceId") { doesNotExist() }
			}

		mockMvc.get("/api/sessions/$sessionId")
			.andExpect {
				status { isOk() }
				jsonPath("$.id") { value(sessionId) }
				jsonPath("$.workspaceId") { doesNotExist() }
			}

		mockMvc.patch("/api/sessions/$sessionId") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"title":"Prepare August update"}"""
		}.andExpect {
			status { isOk() }
			jsonPath("$.title") { value("Prepare August update") }
			jsonPath("$.status") { value("OPEN") }
			jsonPath("$.workspaceId") { doesNotExist() }
		}
	}
}
```

- [ ] **Step 2: Run work session API tests to verify they fail**

Run:

```bash
cd apps/api && ./gradlew test --tests com.plot.api.worksession.WorkSessionApiIntegrationTest
```

Expected: FAIL because `/api/sessions` is not implemented.

- [ ] **Step 3: Implement entity, repository, DTOs, service, and controller**

Create `apps/api/src/main/kotlin/com/plot/api/worksession/WorkSession.kt`:

```kotlin
package com.plot.api.worksession

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "work_sessions")
class WorkSession(
	@Id
	var id: UUID,
	var workspaceId: UUID,
	var title: String?,
	var status: String,
	var createdByUserId: UUID?,
	var lastActivityAt: Instant?,
	var createdAt: Instant,
	var updatedAt: Instant,
)
```

Create `apps/api/src/main/kotlin/com/plot/api/worksession/WorkSessionRepository.kt`:

```kotlin
package com.plot.api.worksession

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface WorkSessionRepository : JpaRepository<WorkSession, UUID> {
	fun findAllByWorkspaceIdOrderByCreatedAtDesc(workspaceId: UUID): List<WorkSession>
	fun findByWorkspaceIdAndId(workspaceId: UUID, id: UUID): WorkSession?
}
```

Create `apps/api/src/main/kotlin/com/plot/api/worksession/WorkSessionDtos.kt`:

```kotlin
package com.plot.api.worksession

import java.time.Instant
import java.util.UUID

data class CreateWorkSessionRequest(
	val title: String?,
)

data class UpdateWorkSessionRequest(
	val title: String?,
)

data class WorkSessionResponse(
	val id: UUID,
	val title: String?,
	val status: String,
	val lastActivityAt: Instant?,
	val createdAt: Instant,
	val updatedAt: Instant,
)

fun WorkSession.toResponse(): WorkSessionResponse {
	return WorkSessionResponse(
		id = id,
		title = title,
		status = status,
		lastActivityAt = lastActivityAt,
		createdAt = createdAt,
		updatedAt = updatedAt,
	)
}
```

Create `apps/api/src/main/kotlin/com/plot/api/worksession/WorkSessionService.kt`:

```kotlin
package com.plot.api.worksession

import com.plot.api.common.ApiException
import com.plot.api.common.UuidGenerator
import com.plot.api.dev.DevContext
import java.time.Instant
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class WorkSessionService(
	private val devContext: DevContext,
	private val uuidGenerator: UuidGenerator,
	private val workSessionRepository: WorkSessionRepository,
) {

	@Transactional(readOnly = true)
	fun list(): List<WorkSessionResponse> {
		return workSessionRepository
			.findAllByWorkspaceIdOrderByCreatedAtDesc(devContext.devWorkspaceId)
			.map { it.toResponse() }
	}

	@Transactional
	fun create(request: CreateWorkSessionRequest): WorkSessionResponse {
		val now = Instant.now()
		val session = WorkSession(
			id = uuidGenerator.next(),
			workspaceId = devContext.devWorkspaceId,
			title = request.title?.trim(),
			status = "OPEN",
			createdByUserId = devContext.devUserId,
			lastActivityAt = now,
			createdAt = now,
			updatedAt = now,
		)
		return workSessionRepository.save(session).toResponse()
	}

	@Transactional(readOnly = true)
	fun get(id: UUID): WorkSessionResponse {
		return findSession(id).toResponse()
	}

	@Transactional
	fun update(id: UUID, request: UpdateWorkSessionRequest): WorkSessionResponse {
		val session = findSession(id)
		val now = Instant.now()
		session.title = request.title?.trim()
		session.lastActivityAt = now
		session.updatedAt = now
		return session.toResponse()
	}

	fun requireSession(id: UUID): WorkSession {
		return findSession(id)
	}

	private fun findSession(id: UUID): WorkSession {
		return workSessionRepository.findByWorkspaceIdAndId(devContext.devWorkspaceId, id)
			?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Work session not found")
	}
}
```

Create `apps/api/src/main/kotlin/com/plot/api/worksession/WorkSessionController.kt`:

```kotlin
package com.plot.api.worksession

import java.util.UUID
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/sessions")
class WorkSessionController(
	private val workSessionService: WorkSessionService,
) {

	@GetMapping
	fun list(): List<WorkSessionResponse> {
		return workSessionService.list()
	}

	@PostMapping
	fun create(@RequestBody request: CreateWorkSessionRequest): WorkSessionResponse {
		return workSessionService.create(request)
	}

	@GetMapping("/{id}")
	fun get(@PathVariable id: UUID): WorkSessionResponse {
		return workSessionService.get(id)
	}

	@PatchMapping("/{id}")
	fun update(
		@PathVariable id: UUID,
		@RequestBody request: UpdateWorkSessionRequest,
	): WorkSessionResponse {
		return workSessionService.update(id, request)
	}
}
```

- [ ] **Step 4: Run work session API tests to verify they pass**

Run:

```bash
cd apps/api && ./gradlew test --tests com.plot.api.worksession.WorkSessionApiIntegrationTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/kotlin/com/plot/api/worksession apps/api/src/test/kotlin/com/plot/api/worksession
git commit -m "feat(api): add work session api"
```

---

## Task 6: Task API

**Files:**
- Create: `apps/api/src/main/kotlin/com/plot/api/task/Task.kt`
- Create: `apps/api/src/main/kotlin/com/plot/api/task/TaskRepository.kt`
- Create: `apps/api/src/main/kotlin/com/plot/api/task/TaskDtos.kt`
- Create: `apps/api/src/main/kotlin/com/plot/api/task/TaskService.kt`
- Create: `apps/api/src/main/kotlin/com/plot/api/task/TaskController.kt`
- Create: `apps/api/src/test/kotlin/com/plot/api/task/TaskApiIntegrationTest.kt`

- [ ] **Step 1: Write failing task API tests**

Create `apps/api/src/test/kotlin/com/plot/api/task/TaskApiIntegrationTest.kt`:

```kotlin
package com.plot.api.task

import com.plot.api.TestcontainersConfiguration
import java.util.UUID
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class TaskApiIntegrationTest {

	@Autowired
	private lateinit var mockMvc: MockMvc

	@Test
	fun createListDetailAndUpdateTask() {
		val sessionBody = mockMvc.post("/api/sessions") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"title":"Task session"}"""
		}.andReturn().response.contentAsString
		val sessionId = Regex(""""id":"([^"]+)"""").find(sessionBody)!!.groupValues[1]

		val taskBody = mockMvc.post("/api/tasks") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"sessionId":"$sessionId","title":"Prepare update pack"}"""
		}.andExpect {
			status { isOk() }
			jsonPath("$.id") { exists() }
			jsonPath("$.sessionId") { value(sessionId) }
			jsonPath("$.title") { value("Prepare update pack") }
			jsonPath("$.status") { value("QUEUED") }
			jsonPath("$.workspaceId") { doesNotExist() }
		}.andReturn().response.contentAsString
		val taskId = Regex(""""id":"([^"]+)"""").find(taskBody)!!.groupValues[1]

		mockMvc.get("/api/tasks")
			.andExpect {
				status { isOk() }
				jsonPath("$[0].id") { value(taskId) }
				jsonPath("$[0].workspaceId") { doesNotExist() }
			}

		mockMvc.get("/api/tasks/$taskId")
			.andExpect {
				status { isOk() }
				jsonPath("$.id") { value(taskId) }
				jsonPath("$.workspaceId") { doesNotExist() }
			}

		mockMvc.patch("/api/tasks/$taskId") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"title":"Prepare update pack v2"}"""
		}.andExpect {
			status { isOk() }
			jsonPath("$.title") { value("Prepare update pack v2") }
			jsonPath("$.status") { value("QUEUED") }
			jsonPath("$.workspaceId") { doesNotExist() }
		}
	}

	@Test
	fun createRejectsInvalidSessionId() {
		mockMvc.post("/api/tasks") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"sessionId":"${UUID.randomUUID()}","title":"Invalid session"}"""
		}.andExpect {
			status { isBadRequest() }
			jsonPath("$.error") { value("BAD_REQUEST") }
			jsonPath("$.message") { value("Work session not found") }
		}
	}

	@Test
	fun createRejectsBlankTitle() {
		mockMvc.post("/api/tasks") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"title":" "}"""
		}.andExpect {
			status { isBadRequest() }
			jsonPath("$.error") { value("BAD_REQUEST") }
		}
	}
}
```

- [ ] **Step 2: Run task API tests to verify they fail**

Run:

```bash
cd apps/api && ./gradlew test --tests com.plot.api.task.TaskApiIntegrationTest
```

Expected: FAIL because `/api/tasks` is not implemented.

- [ ] **Step 3: Implement task entity, repository, DTOs, service, and controller**

Create `apps/api/src/main/kotlin/com/plot/api/task/Task.kt`:

```kotlin
package com.plot.api.task

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "tasks")
class Task(
	@Id
	var id: UUID,
	var workspaceId: UUID,
	var workSessionId: UUID?,
	var title: String,
	var status: String,
	var createdByUserId: UUID?,
	var lastActivityAt: Instant?,
	var createdAt: Instant,
	var updatedAt: Instant,
)
```

Create `apps/api/src/main/kotlin/com/plot/api/task/TaskRepository.kt`:

```kotlin
package com.plot.api.task

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface TaskRepository : JpaRepository<Task, UUID> {
	fun findAllByWorkspaceIdOrderByCreatedAtDesc(workspaceId: UUID): List<Task>
	fun findByWorkspaceIdAndId(workspaceId: UUID, id: UUID): Task?
}
```

Create `apps/api/src/main/kotlin/com/plot/api/task/TaskDtos.kt`:

```kotlin
package com.plot.api.task

import jakarta.validation.constraints.NotBlank
import java.time.Instant
import java.util.UUID

data class CreateTaskRequest(
	val sessionId: UUID?,
	@field:NotBlank(message = "Task title must not be blank")
	val title: String,
)

data class UpdateTaskRequest(
	@field:NotBlank(message = "Task title must not be blank")
	val title: String,
)

data class TaskResponse(
	val id: UUID,
	val sessionId: UUID?,
	val title: String,
	val status: String,
	val lastActivityAt: Instant?,
	val createdAt: Instant,
	val updatedAt: Instant,
)

fun Task.toResponse(): TaskResponse {
	return TaskResponse(
		id = id,
		sessionId = workSessionId,
		title = title,
		status = status,
		lastActivityAt = lastActivityAt,
		createdAt = createdAt,
		updatedAt = updatedAt,
	)
}
```

Create `apps/api/src/main/kotlin/com/plot/api/task/TaskService.kt`:

```kotlin
package com.plot.api.task

import com.plot.api.common.ApiException
import com.plot.api.common.UuidGenerator
import com.plot.api.dev.DevContext
import com.plot.api.worksession.WorkSessionService
import java.time.Instant
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class TaskService(
	private val devContext: DevContext,
	private val uuidGenerator: UuidGenerator,
	private val taskRepository: TaskRepository,
	private val workSessionService: WorkSessionService,
) {

	@Transactional(readOnly = true)
	fun list(): List<TaskResponse> {
		return taskRepository
			.findAllByWorkspaceIdOrderByCreatedAtDesc(devContext.devWorkspaceId)
			.map { it.toResponse() }
	}

	@Transactional
	fun create(request: CreateTaskRequest): TaskResponse {
		request.sessionId?.let {
			try {
				workSessionService.requireSession(it)
			} catch (exception: ApiException) {
				throw ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Work session not found")
			}
		}

		val now = Instant.now()
		val task = Task(
			id = uuidGenerator.next(),
			workspaceId = devContext.devWorkspaceId,
			workSessionId = request.sessionId,
			title = request.title.trim(),
			status = "QUEUED",
			createdByUserId = devContext.devUserId,
			lastActivityAt = now,
			createdAt = now,
			updatedAt = now,
		)
		return taskRepository.save(task).toResponse()
	}

	@Transactional(readOnly = true)
	fun get(id: UUID): TaskResponse {
		return findTask(id).toResponse()
	}

	@Transactional
	fun update(id: UUID, request: UpdateTaskRequest): TaskResponse {
		val task = findTask(id)
		val now = Instant.now()
		task.title = request.title.trim()
		task.lastActivityAt = now
		task.updatedAt = now
		return task.toResponse()
	}

	private fun findTask(id: UUID): Task {
		return taskRepository.findByWorkspaceIdAndId(devContext.devWorkspaceId, id)
			?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Task not found")
	}
}
```

Create `apps/api/src/main/kotlin/com/plot/api/task/TaskController.kt`:

```kotlin
package com.plot.api.task

import jakarta.validation.Valid
import java.util.UUID
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/tasks")
class TaskController(
	private val taskService: TaskService,
) {

	@GetMapping
	fun list(): List<TaskResponse> {
		return taskService.list()
	}

	@PostMapping
	fun create(@Valid @RequestBody request: CreateTaskRequest): TaskResponse {
		return taskService.create(request)
	}

	@GetMapping("/{id}")
	fun get(@PathVariable id: UUID): TaskResponse {
		return taskService.get(id)
	}

	@PatchMapping("/{id}")
	fun update(
		@PathVariable id: UUID,
		@Valid @RequestBody request: UpdateTaskRequest,
	): TaskResponse {
		return taskService.update(id, request)
	}
}
```

- [ ] **Step 4: Run task API tests to verify they pass**

Run:

```bash
cd apps/api && ./gradlew test --tests com.plot.api.task.TaskApiIntegrationTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/kotlin/com/plot/api/task apps/api/src/test/kotlin/com/plot/api/task
git commit -m "feat(api): add task api"
```

---

## Task 7: Writing Block API

**Files:**
- Create: `apps/api/src/main/kotlin/com/plot/api/writingblock/WritingBlock.kt`
- Create: `apps/api/src/main/kotlin/com/plot/api/writingblock/WritingBlockRepository.kt`
- Create: `apps/api/src/main/kotlin/com/plot/api/writingblock/WritingBlockDtos.kt`
- Create: `apps/api/src/main/kotlin/com/plot/api/writingblock/WritingBlockService.kt`
- Create: `apps/api/src/main/kotlin/com/plot/api/writingblock/WritingBlockController.kt`
- Create: `apps/api/src/test/kotlin/com/plot/api/writingblock/WritingBlockApiIntegrationTest.kt`

- [ ] **Step 1: Write failing writing block API tests**

Create `apps/api/src/test/kotlin/com/plot/api/writingblock/WritingBlockApiIntegrationTest.kt`:

```kotlin
package com.plot.api.writingblock

import com.plot.api.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class WritingBlockApiIntegrationTest {

	@Autowired
	private lateinit var mockMvc: MockMvc

	@Test
	fun createListDetailAndUpdateBlock() {
		val blockBody = mockMvc.post("/api/blocks") {
			contentType = MediaType.APPLICATION_JSON
			content = """
				{
				  "sourceOrigin":"REPOSITORY_IMPORT",
				  "sourceKind":"GITHUB_PR",
				  "title":"Add billing settings",
				  "body":"Merged PR with billing settings copy",
				  "url":"https://github.com/acme/app/pull/123",
				  "canonicalUrl":"https://github.com/acme/app/pull/123",
				  "author":"mira",
				  "platform":"github",
				  "metadata":{"number":123},
				  "sourceCreatedAt":"2026-07-01T10:00:00Z",
				  "sourceUpdatedAt":"2026-07-01T10:10:00Z"
				}
			""".trimIndent()
		}.andExpect {
			status { isOk() }
			jsonPath("$.id") { exists() }
			jsonPath("$.sourceOrigin") { value("REPOSITORY_IMPORT") }
			jsonPath("$.sourceKind") { value("GITHUB_PR") }
			jsonPath("$.title") { value("Add billing settings") }
			jsonPath("$.status") { value("ACTIVE") }
			jsonPath("$.workspaceId") { doesNotExist() }
		}.andReturn().response.contentAsString
		val blockId = Regex(""""id":"([^"]+)"""").find(blockBody)!!.groupValues[1]

		mockMvc.get("/api/blocks")
			.andExpect {
				status { isOk() }
				jsonPath("$[0].id") { value(blockId) }
				jsonPath("$[0].workspaceId") { doesNotExist() }
			}

		mockMvc.get("/api/blocks/$blockId")
			.andExpect {
				status { isOk() }
				jsonPath("$.id") { value(blockId) }
				jsonPath("$.workspaceId") { doesNotExist() }
			}

		mockMvc.patch("/api/blocks/$blockId") {
			contentType = MediaType.APPLICATION_JSON
			content = """
				{
				  "sourceOrigin":"REPOSITORY_IMPORT",
				  "sourceKind":"GITHUB_PR",
				  "title":"Add billing settings v2",
				  "body":"Updated billing settings summary"
				}
			""".trimIndent()
		}.andExpect {
			status { isOk() }
			jsonPath("$.title") { value("Add billing settings v2") }
			jsonPath("$.body") { value("Updated billing settings summary") }
			jsonPath("$.status") { value("ACTIVE") }
			jsonPath("$.workspaceId") { doesNotExist() }
		}
	}

	@Test
	fun createRejectsBlankTitleAndBody() {
		mockMvc.post("/api/blocks") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"sourceOrigin":"REPOSITORY_IMPORT","sourceKind":"GITHUB_PR","title":" ","body":" "}"""
		}.andExpect {
			status { isBadRequest() }
			jsonPath("$.error") { value("BAD_REQUEST") }
			jsonPath("$.message") { value("Writing block requires title or body") }
		}
	}
}
```

- [ ] **Step 2: Run writing block API tests to verify they fail**

Run:

```bash
cd apps/api && ./gradlew test --tests com.plot.api.writingblock.WritingBlockApiIntegrationTest
```

Expected: FAIL because `/api/blocks` is not implemented.

- [ ] **Step 3: Implement writing block entity, repository, DTOs, service, and controller**

Create `apps/api/src/main/kotlin/com/plot/api/writingblock/WritingBlock.kt`:

```kotlin
package com.plot.api.writingblock

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "writing_blocks")
class WritingBlock(
	@Id
	var id: UUID,
	var workspaceId: UUID,
	var sourceOrigin: String,
	var sourceKind: String,
	var title: String?,
	var body: String?,
	var url: String?,
	var canonicalUrl: String?,
	var author: String?,
	var platform: String?,
	@JdbcTypeCode(SqlTypes.JSON)
	var metadata: Map<String, Any?>?,
	var contentHash: String?,
	var sourceCreatedAt: Instant?,
	var sourceUpdatedAt: Instant?,
	var ingestedAt: Instant,
	var status: String,
	var createdByUserId: UUID?,
	var createdAt: Instant,
	var updatedAt: Instant,
)
```

Create `apps/api/src/main/kotlin/com/plot/api/writingblock/WritingBlockRepository.kt`:

```kotlin
package com.plot.api.writingblock

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface WritingBlockRepository : JpaRepository<WritingBlock, UUID> {
	fun findAllByWorkspaceIdOrderByCreatedAtDesc(workspaceId: UUID): List<WritingBlock>
	fun findByWorkspaceIdAndId(workspaceId: UUID, id: UUID): WritingBlock?
}
```

Create `apps/api/src/main/kotlin/com/plot/api/writingblock/WritingBlockDtos.kt`:

```kotlin
package com.plot.api.writingblock

import jakarta.validation.constraints.NotBlank
import java.time.Instant
import java.util.UUID

data class CreateWritingBlockRequest(
	@field:NotBlank(message = "sourceOrigin must not be blank")
	val sourceOrigin: String,
	@field:NotBlank(message = "sourceKind must not be blank")
	val sourceKind: String,
	val title: String?,
	val body: String?,
	val url: String?,
	val canonicalUrl: String?,
	val author: String?,
	val platform: String?,
	val metadata: Map<String, Any?>?,
	val sourceCreatedAt: Instant?,
	val sourceUpdatedAt: Instant?,
)

data class UpdateWritingBlockRequest(
	@field:NotBlank(message = "sourceOrigin must not be blank")
	val sourceOrigin: String,
	@field:NotBlank(message = "sourceKind must not be blank")
	val sourceKind: String,
	val title: String?,
	val body: String?,
	val url: String?,
	val canonicalUrl: String?,
	val author: String?,
	val platform: String?,
	val metadata: Map<String, Any?>?,
	val sourceCreatedAt: Instant?,
	val sourceUpdatedAt: Instant?,
)

data class WritingBlockResponse(
	val id: UUID,
	val sourceOrigin: String,
	val sourceKind: String,
	val title: String?,
	val body: String?,
	val url: String?,
	val canonicalUrl: String?,
	val author: String?,
	val platform: String?,
	val metadata: Map<String, Any?>?,
	val sourceCreatedAt: Instant?,
	val sourceUpdatedAt: Instant?,
	val ingestedAt: Instant,
	val status: String,
	val createdAt: Instant,
	val updatedAt: Instant,
)

fun WritingBlock.toResponse(): WritingBlockResponse {
	return WritingBlockResponse(
		id = id,
		sourceOrigin = sourceOrigin,
		sourceKind = sourceKind,
		title = title,
		body = body,
		url = url,
		canonicalUrl = canonicalUrl,
		author = author,
		platform = platform,
		metadata = metadata,
		sourceCreatedAt = sourceCreatedAt,
		sourceUpdatedAt = sourceUpdatedAt,
		ingestedAt = ingestedAt,
		status = status,
		createdAt = createdAt,
		updatedAt = updatedAt,
	)
}
```

Create `apps/api/src/main/kotlin/com/plot/api/writingblock/WritingBlockService.kt`:

```kotlin
package com.plot.api.writingblock

import com.plot.api.common.ApiException
import com.plot.api.common.UuidGenerator
import com.plot.api.dev.DevContext
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class WritingBlockService(
	private val devContext: DevContext,
	private val uuidGenerator: UuidGenerator,
	private val writingBlockRepository: WritingBlockRepository,
) {

	@Transactional(readOnly = true)
	fun list(): List<WritingBlockResponse> {
		return writingBlockRepository
			.findAllByWorkspaceIdOrderByCreatedAtDesc(devContext.devWorkspaceId)
			.map { it.toResponse() }
	}

	@Transactional
	fun create(request: CreateWritingBlockRequest): WritingBlockResponse {
		validateHasTitleOrBody(request.title, request.body)
		val now = Instant.now()
		val title = request.title?.trim()
		val body = request.body?.trim()
		val block = WritingBlock(
			id = uuidGenerator.next(),
			workspaceId = devContext.devWorkspaceId,
			sourceOrigin = request.sourceOrigin.trim(),
			sourceKind = request.sourceKind.trim(),
			title = title,
			body = body,
			url = request.url?.trim(),
			canonicalUrl = request.canonicalUrl?.trim(),
			author = request.author?.trim(),
			platform = request.platform?.trim(),
			metadata = request.metadata,
			contentHash = contentHash(title, body),
			sourceCreatedAt = request.sourceCreatedAt,
			sourceUpdatedAt = request.sourceUpdatedAt,
			ingestedAt = now,
			status = "ACTIVE",
			createdByUserId = devContext.devUserId,
			createdAt = now,
			updatedAt = now,
		)
		return writingBlockRepository.save(block).toResponse()
	}

	@Transactional(readOnly = true)
	fun get(id: UUID): WritingBlockResponse {
		return findBlock(id).toResponse()
	}

	@Transactional
	fun update(id: UUID, request: UpdateWritingBlockRequest): WritingBlockResponse {
		validateHasTitleOrBody(request.title, request.body)
		val block = findBlock(id)
		val title = request.title?.trim()
		val body = request.body?.trim()
		block.sourceOrigin = request.sourceOrigin.trim()
		block.sourceKind = request.sourceKind.trim()
		block.title = title
		block.body = body
		block.url = request.url?.trim()
		block.canonicalUrl = request.canonicalUrl?.trim()
		block.author = request.author?.trim()
		block.platform = request.platform?.trim()
		block.metadata = request.metadata
		block.contentHash = contentHash(title, body)
		block.sourceCreatedAt = request.sourceCreatedAt
		block.sourceUpdatedAt = request.sourceUpdatedAt
		block.updatedAt = Instant.now()
		return block.toResponse()
	}

	private fun findBlock(id: UUID): WritingBlock {
		return writingBlockRepository.findByWorkspaceIdAndId(devContext.devWorkspaceId, id)
			?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Writing block not found")
	}

	private fun validateHasTitleOrBody(title: String?, body: String?) {
		if (title.isNullOrBlank() && body.isNullOrBlank()) {
			throw ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Writing block requires title or body")
		}
	}

	private fun contentHash(title: String?, body: String?): String {
		val input = listOfNotNull(title, body).joinToString("\n")
		val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
		return digest.joinToString("") { "%02x".format(it) }
	}
}
```

Create `apps/api/src/main/kotlin/com/plot/api/writingblock/WritingBlockController.kt`:

```kotlin
package com.plot.api.writingblock

import jakarta.validation.Valid
import java.util.UUID
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/blocks")
class WritingBlockController(
	private val writingBlockService: WritingBlockService,
) {

	@GetMapping
	fun list(): List<WritingBlockResponse> {
		return writingBlockService.list()
	}

	@PostMapping
	fun create(@Valid @RequestBody request: CreateWritingBlockRequest): WritingBlockResponse {
		return writingBlockService.create(request)
	}

	@GetMapping("/{id}")
	fun get(@PathVariable id: UUID): WritingBlockResponse {
		return writingBlockService.get(id)
	}

	@PatchMapping("/{id}")
	fun update(
		@PathVariable id: UUID,
		@Valid @RequestBody request: UpdateWritingBlockRequest,
	): WritingBlockResponse {
		return writingBlockService.update(id, request)
	}
}
```

- [ ] **Step 4: Run writing block API tests to verify they pass**

Run:

```bash
cd apps/api && ./gradlew test --tests com.plot.api.writingblock.WritingBlockApiIntegrationTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/kotlin/com/plot/api/writingblock apps/api/src/test/kotlin/com/plot/api/writingblock
git commit -m "feat(api): add writing block api"
```

---

## Task 8: Cross-Workspace Scoping Verification

**Files:**
- Modify: `apps/api/src/test/kotlin/com/plot/api/worksession/WorkSessionApiIntegrationTest.kt`
- Modify: `apps/api/src/test/kotlin/com/plot/api/task/TaskApiIntegrationTest.kt`
- Modify: `apps/api/src/test/kotlin/com/plot/api/writingblock/WritingBlockApiIntegrationTest.kt`

- [ ] **Step 1: Add cross-workspace tests**

Append this test to `WorkSessionApiIntegrationTest` and inject `JdbcTemplate` at class level:

```kotlin
@Autowired
private lateinit var jdbcTemplate: org.springframework.jdbc.core.JdbcTemplate

@Test
fun detailReturnsNotFoundForSessionInAnotherWorkspace() {
	val otherWorkspaceId = java.util.UUID.fromString("018fd000-0000-7000-8000-000000000102")
	val otherSessionId = java.util.UUID.fromString("018fd000-0000-7000-8000-000000000103")
	val now = java.time.Instant.now()
	jdbcTemplate.update(
		"insert into workspaces(id, name, slug, created_by_user_id, status, created_at, updated_at) values (?, ?, ?, null, ?, ?, ?)",
		otherWorkspaceId,
		"Other Workspace",
		"other-workspace",
		"ACTIVE",
		now,
		now,
	)
	jdbcTemplate.update(
		"insert into work_sessions(id, workspace_id, title, status, created_by_user_id, last_activity_at, created_at, updated_at) values (?, ?, ?, ?, null, ?, ?, ?)",
		otherSessionId,
		otherWorkspaceId,
		"Hidden session",
		"OPEN",
		now,
		now,
		now,
	)

	mockMvc.get("/api/sessions/$otherSessionId")
		.andExpect {
			status { isNotFound() }
			jsonPath("$.error") { value("NOT_FOUND") }
		}
}
```

Append this test to `TaskApiIntegrationTest` and inject `JdbcTemplate` at class level:

```kotlin
@Autowired
private lateinit var jdbcTemplate: org.springframework.jdbc.core.JdbcTemplate

@Test
fun detailReturnsNotFoundForTaskInAnotherWorkspace() {
	val otherWorkspaceId = java.util.UUID.fromString("018fd000-0000-7000-8000-000000000202")
	val otherTaskId = java.util.UUID.fromString("018fd000-0000-7000-8000-000000000203")
	val now = java.time.Instant.now()
	jdbcTemplate.update(
		"insert into workspaces(id, name, slug, created_by_user_id, status, created_at, updated_at) values (?, ?, ?, null, ?, ?, ?)",
		otherWorkspaceId,
		"Other Task Workspace",
		"other-task-workspace",
		"ACTIVE",
		now,
		now,
	)
	jdbcTemplate.update(
		"insert into tasks(id, workspace_id, work_session_id, title, status, created_by_user_id, last_activity_at, created_at, updated_at) values (?, ?, null, ?, ?, null, ?, ?, ?)",
		otherTaskId,
		otherWorkspaceId,
		"Hidden task",
		"QUEUED",
		now,
		now,
		now,
	)

	mockMvc.get("/api/tasks/$otherTaskId")
		.andExpect {
			status { isNotFound() }
			jsonPath("$.error") { value("NOT_FOUND") }
		}
}
```

Append this test to `WritingBlockApiIntegrationTest` and inject `JdbcTemplate` at class level:

```kotlin
@Autowired
private lateinit var jdbcTemplate: org.springframework.jdbc.core.JdbcTemplate

@Test
fun detailReturnsNotFoundForBlockInAnotherWorkspace() {
	val otherWorkspaceId = java.util.UUID.fromString("018fd000-0000-7000-8000-000000000302")
	val otherBlockId = java.util.UUID.fromString("018fd000-0000-7000-8000-000000000303")
	val now = java.time.Instant.now()
	jdbcTemplate.update(
		"insert into workspaces(id, name, slug, created_by_user_id, status, created_at, updated_at) values (?, ?, ?, null, ?, ?, ?)",
		otherWorkspaceId,
		"Other Block Workspace",
		"other-block-workspace",
		"ACTIVE",
		now,
		now,
	)
	jdbcTemplate.update(
		"insert into writing_blocks(id, workspace_id, source_origin, source_kind, title, body, ingested_at, status, created_by_user_id, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, null, ?, ?)",
		otherBlockId,
		otherWorkspaceId,
		"REPOSITORY_IMPORT",
		"GITHUB_PR",
		"Hidden block",
		"Hidden body",
		now,
		"ACTIVE",
		now,
		now,
	)

	mockMvc.get("/api/blocks/$otherBlockId")
		.andExpect {
			status { isNotFound() }
			jsonPath("$.error") { value("NOT_FOUND") }
		}
}
```

- [ ] **Step 2: Run cross-workspace tests**

Run:

```bash
cd apps/api && ./gradlew test --tests com.plot.api.worksession.WorkSessionApiIntegrationTest --tests com.plot.api.task.TaskApiIntegrationTest --tests com.plot.api.writingblock.WritingBlockApiIntegrationTest
```

Expected: PASS. These tests should pass because services already use `findByWorkspaceIdAndId`.

- [ ] **Step 3: Commit**

```bash
git add apps/api/src/test/kotlin/com/plot/api/worksession/WorkSessionApiIntegrationTest.kt apps/api/src/test/kotlin/com/plot/api/task/TaskApiIntegrationTest.kt apps/api/src/test/kotlin/com/plot/api/writingblock/WritingBlockApiIntegrationTest.kt
git commit -m "test(api): verify dev workspace scoping"
```

---

## Task 9: Final API Verification

**Files:**
- Modify only if tests reveal a concrete issue:
  - `apps/api/src/main/kotlin/com/plot/api/common/*`
  - `apps/api/src/main/kotlin/com/plot/api/dev/*`
  - `apps/api/src/main/kotlin/com/plot/api/workspace/*`
  - `apps/api/src/main/kotlin/com/plot/api/worksession/*`
  - `apps/api/src/main/kotlin/com/plot/api/task/*`
  - `apps/api/src/main/kotlin/com/plot/api/writingblock/*`

- [ ] **Step 1: Run the full API test suite**

Run:

```bash
just test-api
```

Expected: PASS.

- [ ] **Step 2: Inspect generated migration and source for forbidden fields**

Run:

```bash
rg -n "session_type|task_type|objective|due_at|priority|review_mode|source_scope|is_deleted|deleted_at|DELETED|gen_random_uuid" apps/api/src/main apps/api/src/test
```

Expected: no output.

- [ ] **Step 3: Inspect API response DTOs for hidden workspace IDs**

Run:

```bash
rg -n "workspaceId" apps/api/src/main/kotlin/com/plot/api/worksession apps/api/src/main/kotlin/com/plot/api/task apps/api/src/main/kotlin/com/plot/api/writingblock
```

Expected: output only from entity/service internals, not from `WorkSessionResponse`, `TaskResponse`, or `WritingBlockResponse`.

- [ ] **Step 4: Commit final fixes if Step 1, 2, or 3 required code changes**

Use this command only when files changed:

```bash
git add apps/api/src/main apps/api/src/test
git commit -m "fix(api): align domain foundation contract"
```

Expected: commit is created only if the working tree had changes.

- [ ] **Step 5: Confirm clean working tree**

Run:

```bash
git status --short
```

Expected: no output.

---

## Self-Review

### Spec Coverage

- Packages `common`, `dev`, `workspace`, `worksession`, `task`, and `writingblock`: covered by Tasks 1, 3, 4, 5, 6, and 7.
- Flyway V1 schema with six tables: covered by Task 2.
- App-generated UUIDv7: covered by Task 1 and used in services in Tasks 5, 6, and 7.
- DevContext and DevBootstrap with fixed user/workspace/member: covered by Task 3.
- No auth for development APIs: covered by Task 1 `SecurityConfig`.
- Workspace API list/detail/update without POST: covered by Task 4.
- Session API list/create/detail/update: covered by Task 5.
- Task API list/create/detail/update and invalid session rejection: covered by Task 6.
- Writing Block API list/create/detail/update and blank content rejection: covered by Task 7.
- Response DTOs and hidden `workspaceId` for sessions/tasks/blocks: covered by Tasks 5, 6, and 7.
- Cross-workspace 404 behavior: covered by Task 8.
- `just test-api` verification: covered by Task 9.
- Excluded fields and deleted-state policy: covered by Task 9 grep check.

### Placeholder Scan

The plan was scanned for placeholder wording, vague edge-case instructions, and references to undefined classes. Every code-bearing step includes concrete file paths, class names, commands, and expected outcomes.

### Type Consistency

- API field names use camelCase: `sessionId`, `sourceOrigin`, `sourceKind`, `canonicalUrl`, `sourceCreatedAt`, `sourceUpdatedAt`.
- Database column names use snake_case: `work_session_id`, `source_origin`, `source_kind`, `canonical_url`, `source_created_at`, `source_updated_at`.
- `WorkSessionResponse`, `TaskResponse`, and `WritingBlockResponse` do not include `workspaceId`.
- `TaskService` depends on `WorkSessionService.requireSession(UUID)`, defined in Task 5 before Task 6 uses it.
- `WritingBlock` uses `sourceKind` in Kotlin and `source_kind` in the database through Spring's physical naming strategy.
