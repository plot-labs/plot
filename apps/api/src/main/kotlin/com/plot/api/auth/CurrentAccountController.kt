package com.plot.api.auth

import com.plot.api.workspace.UserRepository
import com.plot.api.workspace.WorkspaceMemberRepository
import com.plot.api.workspace.WorkspaceRepository
import java.util.UUID
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class CurrentAccountUser(val id: UUID, val email: String, val displayName: String)
data class CurrentAccountWorkspace(val id: UUID, val name: String, val slug: String, val role: String)
data class CurrentAccountResponse(
	val user: CurrentAccountUser,
	val workspaces: List<CurrentAccountWorkspace>,
	val defaultWorkspaceId: UUID,
)

@RestController
@RequestMapping("/api/me")
class CurrentAccountController(
	private val actorResolver: RequestActorResolver,
	private val userRepository: UserRepository,
	private val memberRepository: WorkspaceMemberRepository,
	private val workspaceRepository: WorkspaceRepository,
) {
	@GetMapping
	fun me(): ResponseEntity<CurrentAccountResponse> {
		val actor = actorResolver.requireActor()
		val user = userRepository.findById(actor.userId).orElseThrow { IllegalStateException("Authenticated user disappeared") }
		val memberships = memberRepository.findAllByUserIdAndStatusOrderByCreatedAtAsc(actor.userId, "ACTIVE")
		val workspaces = workspaceRepository.findAllByIdInAndStatus(memberships.map { it.workspaceId }, "ACTIVE")
			.associateBy { it.id }
		val response = memberships.mapNotNull { member ->
			workspaces[member.workspaceId]?.let { workspace ->
				CurrentAccountWorkspace(workspace.id, workspace.name, workspace.slug, member.role)
			}
		}
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
			CurrentAccountResponse(
				CurrentAccountUser(user.id, user.email, user.displayName),
				response,
				response.firstOrNull()?.id ?: throw com.plot.api.common.ApiException(
					org.springframework.http.HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Access denied",
				),
			),
		)
	}
}
