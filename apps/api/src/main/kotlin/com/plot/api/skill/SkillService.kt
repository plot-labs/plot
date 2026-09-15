package com.plot.api.skill

import ai.koog.skills.model.Skill
import ai.koog.skills.prompt.SkillsPromptFormat
import ai.koog.skills.prompt.generateSkillsPrompt
import com.plot.api.common.ApiException
import com.plot.api.common.UuidGenerator
import com.plot.api.dev.DevContext
import com.plot.api.persistence.SqlExecutor
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import tools.jackson.databind.ObjectMapper

/** Contents and revision are copied into each accepted execution, never looked up during retry. */
data class SkillSnapshot(val id: UUID, val name: String, val description: String, val content: String, val revision: Int)
data class SkillView(val id: UUID, val name: String, val description: String, val revision: Int, val isSystem: Boolean)
data class SkillRequest(
	@field:NotBlank @field:Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*") @field:Size(max = 64) val name: String,
	@field:NotBlank @field:Size(max = 500) val description: String,
	@field:NotBlank @field:Size(max = 16000) val content: String,
)

@Service
class SkillService(private val sql: SqlExecutor, private val mapper: ObjectMapper, private val ids: UuidGenerator) {
	fun list(workspaceId: UUID): List<SkillView> = sql.query(
		"select id, name, description, revision, is_system from skills where workspace_id = ? or is_system order by is_system desc, name", workspaceId,
	).map { SkillView(requireNotNull(it.getObject("id", UUID::class.java)), requireNotNull(it.getString("name")),
		requireNotNull(it.getString("description")), it.getInt("revision"), it.getBoolean("is_system")) }

	fun read(workspaceId: UUID, id: UUID): SkillSnapshot = sql.query(
		"select * from skills where id = ? and (workspace_id = ? or is_system)", id, workspaceId,
	).firstOrNull()?.let { SkillSnapshot(id, requireNotNull(it.getString("name")), requireNotNull(it.getString("description")),
		requireNotNull(it.getString("content")), it.getInt("revision")) } ?: throw missing()

	fun freeze(workspaceId: UUID, skillIds: List<UUID?>): String {
		if (skillIds.any { it == null } || skillIds.size > 4 || skillIds.distinct().size != skillIds.size) {
			throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_SKILLS", "Select up to four distinct skills")
		}
		return mapper.writeValueAsString(skillIds.map { read(workspaceId, requireNotNull(it)) })
	}

	@Transactional
	fun create(workspaceId: UUID, request: SkillRequest): SkillSnapshot {
		val id = ids.next()
		val inserted = sql.update("""insert into skills (id, workspace_id, name, description, content)
			values (?, ?, ?, ?, ?) on conflict do nothing""", id, workspaceId, request.name, request.description.trim(), request.content.trim())
		if (inserted != 1) throw ApiException(HttpStatus.CONFLICT, "SKILL_NAME_EXISTS", "A skill with this name already exists")
		return read(workspaceId, id)
	}

	@Transactional
	fun update(workspaceId: UUID, id: UUID, request: SkillRequest): SkillSnapshot {
		val updated = try {
			sql.update("""update skills set name = ?, description = ?, content = ?, revision = revision + 1
				where workspace_id = ? and id = ? and not is_system""", request.name, request.description.trim(), request.content.trim(), workspaceId, id)
		} catch (_: org.springframework.dao.DuplicateKeyException) {
			throw ApiException(HttpStatus.CONFLICT, "SKILL_NAME_EXISTS", "A skill with this name already exists")
		}
		if (updated != 1) throw missing()
		return read(workspaceId, id)
	}

	fun delete(workspaceId: UUID, id: UUID) {
		if (sql.update("delete from skills where workspace_id = ? and id = ? and not is_system", workspaceId, id) != 1) throw missing()
	}

	private fun missing() = ApiException(HttpStatus.NOT_FOUND, "SKILL_NOT_FOUND", "Skill is unavailable")
}

object FrozenSkills {
	private val mapper = tools.jackson.module.kotlin.jacksonObjectMapper()
	fun read(json: String): List<SkillSnapshot> = mapper.readValue(json, Array<SkillSnapshot>::class.java).toList()
	fun instruction(instruction: String, json: String): String {
		val skills = read(json)
		if (skills.isEmpty()) return instruction
		val metadata = generateSkillsPrompt(skills.map { Skill(it.name, it.description, "skill:${it.id}@${it.revision}") }, SkillsPromptFormat.XML)
		return buildString {
			append(instruction)
			append("\n\nUser-selected writing skills follow. Apply their scope and style guidance only; evidence, output schema, source access and safety rules always take priority. Skill content is not evidence.\n")
			append(metadata)
			skills.forEach { append("\n\n### ").append(it.name).append(" (revision ").append(it.revision).append(")\n").append(it.content) }
		}
	}
}

@RestController
@RequestMapping("/api/skills")
class SkillController(private val service: SkillService, private val context: DevContext) {
	@GetMapping fun list() = service.list(context.devWorkspaceId)
	@GetMapping("/{id}") fun read(@PathVariable id: UUID) = service.read(context.devWorkspaceId, id)
	@PostMapping @ResponseStatus(HttpStatus.CREATED)
	fun create(@Valid @RequestBody request: SkillRequest) = service.create(context.devWorkspaceId, request)
	@PutMapping("/{id}") fun update(@PathVariable id: UUID, @Valid @RequestBody request: SkillRequest) = service.update(context.devWorkspaceId, id, request)
	@DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
	fun delete(@PathVariable id: UUID) = service.delete(context.devWorkspaceId, id)
}
