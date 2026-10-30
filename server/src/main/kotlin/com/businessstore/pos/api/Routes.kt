package com.businessstore.pos.api

import com.businessstore.pos.config.ServerConfig
import com.businessstore.pos.db.Database
import com.businessstore.pos.domain.DailySalesDto
import com.businessstore.pos.domain.ErrorDto
import com.businessstore.pos.domain.PosJson
import com.businessstore.pos.domain.RegisterDeviceRequest
import com.businessstore.pos.domain.StockLevelDto
import com.businessstore.pos.domain.PullResponse
import com.businessstore.pos.domain.SyncBatchRequest
import com.businessstore.pos.domain.SyncBatchResponse
import com.businessstore.pos.payments.InvalidSignatureException
import com.businessstore.pos.payments.MobileMoneyInitiateRequest
import com.businessstore.pos.payments.MobileMoneyService
import com.businessstore.pos.payments.PaymentConflictException
import com.businessstore.pos.payments.PaymentNotFoundException
import com.businessstore.pos.payments.WebhookNotConfiguredException
import com.businessstore.pos.reports.Reports
import com.businessstore.pos.security.DeviceService
import com.businessstore.pos.sync.EventApplier
import com.businessstore.pos.sync.EventPull
import com.businessstore.pos.sync.InvalidEventPayloadException
import com.businessstore.pos.sync.UnknownProductException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.serializer
import java.security.MessageDigest

class AuthenticationException(message: String) : RuntimeException(message)

@Serializable
data class HealthDto(val status: String, val service: String)

/**
 * HTTP surface.
 *
 * Every route except enrollment and health requires a valid device bearer token,
 * and a device may only submit events carrying its OWN deviceId — one compromised
 * till cannot forge movements for another.
 */
fun Application.posModule(
    db: Database,
    config: ServerConfig,
    applier: EventApplier = EventApplier(db),
    devices: DeviceService = DeviceService(db),
    reports: Reports = Reports(db),
    pulls: EventPull = EventPull(db),
    payments: MobileMoneyService = MobileMoneyService(db, config.momoWebhookSecret, config.momoProvider),
) {
    install(ContentNegotiation) { json(PosJson) }

    install(StatusPages) {
        exception<AuthenticationException> { call, cause ->
            call.respond(HttpStatusCode.Unauthorized, ErrorDto(cause.message ?: "unauthorized"))
        }
        exception<UnknownProductException> { call, cause ->
            // 422 so the client retries after the catalog syncs; the event is NOT
            // marked processed, because the whole apply transaction rolled back.
            call.respond(
                HttpStatusCode.UnprocessableEntity,
                ErrorDto(cause.message ?: "unknown product", cause.eventId),
            )
        }
        exception<InvalidEventPayloadException> { call, cause ->
            // Same transactional guarantee: the event was not claimed, so a
            // corrected payload can be re-sent; a deliberately bad one (a rogue
            // till shipping a plaintext secret) stays failing on that device.
            call.respond(
                HttpStatusCode.UnprocessableEntity,
                ErrorDto(cause.message ?: "invalid event payload", cause.eventId),
            )
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorDto(cause.message ?: "bad request"))
        }
        exception<WebhookNotConfiguredException> { call, cause ->
            // Refusing is safer than accepting an unsigned callback.
            call.respond(HttpStatusCode.ServiceUnavailable, ErrorDto(cause.message ?: "webhook disabled"))
        }
        exception<InvalidSignatureException> { call, cause ->
            call.respond(HttpStatusCode.Unauthorized, ErrorDto(cause.message ?: "invalid signature"))
        }
        exception<PaymentConflictException> { call, cause ->
            call.respond(HttpStatusCode.Conflict, ErrorDto(cause.message ?: "payment conflict"))
        }
        exception<PaymentNotFoundException> { call, cause ->
            call.respond(HttpStatusCode.NotFound, ErrorDto(cause.message ?: "payment not found"))
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("unhandled error", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorDto("internal error"))
        }
    }

    routing {
        get("/health") {
            call.respond(HealthDto(status = "ok", service = "business-store-server"))
        }

        post("/devices") {
            if (config.enrollmentSecret != null) {
                val provided = call.request.headers["X-Enrollment-Secret"] ?: ""
                if (!constantTimeEquals(provided, config.enrollmentSecret)) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorDto("invalid enrollment secret"))
                    return@post
                }
            }
            val request = call.receive<RegisterDeviceRequest>()
            val response = devices.register(
                storeId = request.storeId,
                name = request.name,
                model = request.model,
                requestedDeviceId = request.deviceId,
            )
            call.respond(HttpStatusCode.Created, response)
        }

        post("/sync") {
            val auth = call.authenticate(devices)
            val batch = call.receive<SyncBatchRequest>()

            val foreign = batch.events.firstOrNull { it.deviceId != auth.deviceId }
            if (foreign != null) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    ErrorDto("event ${foreign.eventId} claims device ${foreign.deviceId}", foreign.eventId),
                )
                return@post
            }

            val results = applier.applyBatch(batch.events)
            devices.touch(auth.deviceId)
            call.respond(SyncBatchResponse(results))
        }

        get("/sync/pull") {
            val auth = call.authenticate(devices)
            // `since` = { sourceDeviceId: lastSeqDelivered } per source device.
            // Absent key -> no cursor for that device -> deliver from seq 1.
            val since: Map<String, Long> = call.request.queryParameters["since"]?.let { raw ->
                runCatching {
                    PosJson.decodeFromString(MapSerializer(String.serializer(), Long.serializer()), raw)
                }.getOrNull()
                    ?: throw IllegalArgumentException("'since' must be a JSON object of deviceId -> seq")
            } ?: emptyMap()
            val limit = when (val requested = call.request.queryParameters["limit"]) {
                null -> 200
                else -> requested.toIntOrNull()?.coerceIn(1, 1000)
                    ?: throw IllegalArgumentException("'limit' must be an integer between 1 and 1000")
            }
            val events = pulls.pull(auth.storeId, auth.deviceId, since, limit)
            devices.touch(auth.deviceId)
            call.respond(PullResponse(events))
        }

        get("/reports/daily") {
            call.authenticate(devices)
            val from = call.request.queryParameters["from"]
                ?: throw IllegalArgumentException("'from' query parameter is required (YYYY-MM-DD)")
            val to = call.request.queryParameters["to"] ?: from
            val deviceId = call.request.queryParameters["deviceId"]
            val rows: List<DailySalesDto> = reports.dailySales(from, to, deviceId)
            call.respond(rows)
        }

        get("/reports/stock") {
            call.authenticate(devices)
            val rows: List<StockLevelDto> = reports.stockLevels()
            call.respond(rows)
        }

        get("/reports/review-queue") {
            call.authenticate(devices)
            call.respond(reports.stockReviewQueue())
        }

        get("/reports/discrepancies") {
            call.authenticate(devices)
            call.respond(reports.priceDiscrepancies())
        }

        // ---- Mobile money -------------------------------------------------
        // Initiation and status are till-facing (device bearer token). The
        // webhook is provider-facing: it has no device token and is authenticated
        // by the HMAC signature over the exact request body instead.

        post("/payments/mobile-money/initiate") {
            call.authenticate(devices)
            val request = call.receive<MobileMoneyInitiateRequest>()
            val response = payments.initiate(request)
            call.respond(
                if (response.created) HttpStatusCode.Created else HttpStatusCode.OK,
                response,
            )
        }

        post("/payments/mobile-money/webhook") {
            val signature = call.request.headers["X-Momo-Signature"]
            call.respond(payments.handleWebhook(call.receiveText(), signature))
        }

        get("/payments/mobile-money/{paymentId}") {
            call.authenticate(devices)
            val paymentId = call.parameters["paymentId"]
                ?: throw IllegalArgumentException("paymentId is required")
            call.respond(payments.status(paymentId))
        }
    }
}

private fun ApplicationCall.bearerToken(): String? {
    val header = request.headers[HttpHeaders.Authorization] ?: return null
    if (!header.startsWith("Bearer ", ignoreCase = true)) return null
    return header.substring(7).trim().ifEmpty { null }
}

private fun ApplicationCall.authenticate(devices: DeviceService): DeviceService.AuthResult {
    val token = bearerToken() ?: throw AuthenticationException("missing bearer token")
    return devices.authenticate(token) ?: throw AuthenticationException("invalid or expired device token")
}

private fun constantTimeEquals(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
