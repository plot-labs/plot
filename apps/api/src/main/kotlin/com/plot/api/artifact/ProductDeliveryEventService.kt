package com.plot.api.artifact

import com.plot.api.artifact.dto.ProductDeliveryEventKind
import com.plot.api.artifact.dto.ProductDeliveryEventResponse
import com.plot.api.artifact.dto.RecordProductDeliveryEventRequest
import com.plot.api.common.ApiException
import com.plot.api.common.UuidGenerator
import com.plot.api.dev.DevContext
import com.plot.api.persistence.SqlExecutor
import com.plot.api.persistence.TransactionExecutor
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service

@Service
class ProductDeliveryEventService(
	private val sqlExecutor: SqlExecutor,
	private val transactionExecutor: TransactionExecutor,
	private val devContext: DevContext,
	private val uuidGenerator: UuidGenerator,
	private val clock: Clock = Clock.systemUTC(),
) {
	fun recordClientEvent(variantId: UUID, request: RecordProductDeliveryEventRequest): ProductDeliveryEventResponse {
		val kind = requireNotNull(request.kind)
		return when (kind) {
			ProductDeliveryEventKind.CLIPBOARD_WRITE_SUCCEEDED,
			ProductDeliveryEventKind.CLIPBOARD_WRITE_FAILED,
			ProductDeliveryEventKind.DOWNLOAD_STARTED,
			-> {
				val exportId = request.exportId
					?: throw ApiException(HttpStatus.BAD_REQUEST, "EXPORT_ID_REQUIRED", "exportId is required")
				recordExportLinkedEvent(variantId, kind, exportId, request.clientEventId)
			}
			ProductDeliveryEventKind.EXTERNAL_DELIVERY_CONFIRMED -> {
				val entryId = request.entryId
					?: throw ApiException(HttpStatus.BAD_REQUEST, "ENTRY_ID_REQUIRED", "entryId is required")
				recordPublishedLinkedEvent(variantId, kind, entryId, request.clientEventId)
			}
		}
	}

	fun recordPublished(entryId: UUID, variantId: UUID, artifactRevisionId: UUID) {
		val id = uuidGenerator.next()
		sqlExecutor.update(
			"""
			insert into product_delivery_events (
			  id, workspace_id, kind, generation_export_event_id, published_changelog_entry_id,
			  content_variant_id, artifact_revision_id, created_by_user_id, client_event_id, created_at
			) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			""".trimIndent(),
			id,
			devContext.devWorkspaceId,
			"PUBLISHED",
			null,
			entryId,
			variantId,
			artifactRevisionId,
			devContext.devUserId,
			null,
			Timestamp.from(clock.instant()),
		)
	}

	private fun recordExportLinkedEvent(
		variantId: UUID,
		kind: ProductDeliveryEventKind,
		exportId: UUID,
		clientEventId: UUID?,
	): ProductDeliveryEventResponse {
		val export = loadSuccessfulExport(exportId, variantId)
			?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Export event not found")
		if (export.status != "SUCCEEDED") {
			throw ApiException(HttpStatus.CONFLICT, "EXPORT_NOT_SUCCEEDED", "Export was not rendered successfully")
		}
		return insertOrExisting(
			kind = kind.name,
			exportId = exportId,
			entryId = null,
			variantId = variantId,
			artifactRevisionId = export.artifactRevisionId,
			clientEventId = clientEventId,
		) {
			findExistingExportEvent(exportId, kind.name)
		}
	}

	private fun recordPublishedLinkedEvent(
		variantId: UUID,
		kind: ProductDeliveryEventKind,
		entryId: UUID,
		clientEventId: UUID?,
	): ProductDeliveryEventResponse {
		val entry = loadPublishedEntry(entryId, variantId)
			?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Published changelog entry not found")
		return insertOrExisting(
			kind = kind.name,
			exportId = null,
			entryId = entryId,
			variantId = variantId,
			artifactRevisionId = entry.artifactRevisionId,
			clientEventId = clientEventId,
		) {
			findExistingPublishedEvent(entryId, kind.name)
		}
	}

	private fun insertOrExisting(
		kind: String,
		exportId: UUID?,
		entryId: UUID?,
		variantId: UUID,
		artifactRevisionId: UUID,
		clientEventId: UUID?,
		existing: () -> ProductDeliveryEventResponse?,
	): ProductDeliveryEventResponse {
		existing()?.let { return it.copy(duplicate = true) }
		return try {
			val id = insertEvent(kind, exportId, entryId, variantId, artifactRevisionId, clientEventId)
			ProductDeliveryEventResponse(id = id, kind = ProductDeliveryEventKind.valueOf(kind), duplicate = false)
		} catch (_: DataIntegrityViolationException) {
			existing()?.copy(duplicate = true)
				?: throw ApiException(HttpStatus.CONFLICT, "DELIVERY_EVENT_CONFLICT", "Delivery event already recorded")
		}
	}

	private fun insertEvent(
		kind: String,
		exportId: UUID?,
		entryId: UUID?,
		variantId: UUID,
		artifactRevisionId: UUID,
		clientEventId: UUID?,
	): UUID = transactionExecutor.execute {
		val id = uuidGenerator.next()
		sqlExecutor.update(
			"""
			insert into product_delivery_events (
			  id, workspace_id, kind, generation_export_event_id, published_changelog_entry_id,
			  content_variant_id, artifact_revision_id, created_by_user_id, client_event_id, created_at
			) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			""".trimIndent(),
			id,
			devContext.devWorkspaceId,
			kind,
			exportId,
			entryId,
			variantId,
			artifactRevisionId,
			devContext.devUserId,
			clientEventId,
			Timestamp.from(clock.instant()),
		)
		id
	}

	private fun loadSuccessfulExport(exportId: UUID, variantId: UUID): ExportRef? = sqlExecutor.query(
		"""
		select id, content_variant_id, artifact_revision_id, status
		from generation_export_events
		where workspace_id = ? and id = ? and content_variant_id = ?
		limit 1
		""".trimIndent(),
		{ rs, _ ->
			ExportRef(
				id = requireNotNull(rs.getObject(1, UUID::class.java)),
				variantId = requireNotNull(rs.getObject(2, UUID::class.java)),
				artifactRevisionId = requireNotNull(rs.getObject(3, UUID::class.java)),
				status = requireNotNull(rs.getString(4)),
			)
		},
		devContext.devWorkspaceId,
		exportId,
		variantId,
	).firstOrNull()

	private fun loadPublishedEntry(entryId: UUID, variantId: UUID): PublishedRef? = sqlExecutor.query(
		"""
		select id, content_variant_id, artifact_revision_id
		from published_changelog_entries
		where workspace_id = ? and id = ? and content_variant_id = ?
		limit 1
		""".trimIndent(),
		{ rs, _ ->
			PublishedRef(
				id = requireNotNull(rs.getObject(1, UUID::class.java)),
				variantId = requireNotNull(rs.getObject(2, UUID::class.java)),
				artifactRevisionId = requireNotNull(rs.getObject(3, UUID::class.java)),
			)
		},
		devContext.devWorkspaceId,
		entryId,
		variantId,
	).firstOrNull()

	private fun findExistingExportEvent(exportId: UUID, kind: String): ProductDeliveryEventResponse? =
		sqlExecutor.query(
			"""
			select id, kind from product_delivery_events
			where workspace_id = ? and generation_export_event_id = ? and kind = ? and created_by_user_id = ?
			limit 1
			""".trimIndent(),
			{ rs, _ ->
				ProductDeliveryEventResponse(
					id = requireNotNull(rs.getObject(1, UUID::class.java)),
					kind = ProductDeliveryEventKind.valueOf(requireNotNull(rs.getString(2))),
					duplicate = true,
				)
			},
			devContext.devWorkspaceId,
			exportId,
			kind,
			devContext.devUserId,
		).firstOrNull()

	private fun findExistingPublishedEvent(entryId: UUID, kind: String): ProductDeliveryEventResponse? =
		sqlExecutor.query(
			"""
			select id, kind from product_delivery_events
			where workspace_id = ? and published_changelog_entry_id = ? and kind = ? and created_by_user_id = ?
			limit 1
			""".trimIndent(),
			{ rs, _ ->
				ProductDeliveryEventResponse(
					id = requireNotNull(rs.getObject(1, UUID::class.java)),
					kind = ProductDeliveryEventKind.valueOf(requireNotNull(rs.getString(2))),
					duplicate = true,
				)
			},
			devContext.devWorkspaceId,
			entryId,
			kind,
			devContext.devUserId,
		).firstOrNull()

	private data class ExportRef(
		val id: UUID,
		val variantId: UUID,
		val artifactRevisionId: UUID,
		val status: String,
	)

	private data class PublishedRef(
		val id: UUID,
		val variantId: UUID,
		val artifactRevisionId: UUID,
	)
}
