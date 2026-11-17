package `in`.porter.archival.app

import `in`.porter.archival.domain.usecase.ResolveDocument
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.uri
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory

/**
 * The retrieval API. One index seek, one ranged read, one checksum.
 *
 *   GET /documents/{crn}?kind=INVOICE&format=pdf
 *   GET /health
 */
fun runServer(wiring: Wiring, port: Int = 8080) {
    val log = LoggerFactory.getLogger("retrieval")

    embeddedServer(Netty, port = port) {
        routing {
            get("/health") { call.respondText("ok") }

            get("/documents/{crn}") {
                val crn = call.parameters["crn"]!!
                val kind = call.request.queryParameters["kind"] ?: "INVOICE"
                val format = call.request.queryParameters["format"] ?: "pdf"

                when (val outcome = wiring.resolveDocument.resolve(crn, kind, format)) {
                    is ResolveDocument.Outcome.Found -> {
                        log.info("served {} from {}", crn, outcome.source)
                        call.response.headers.append("X-Archive-Source", outcome.source)
                        call.respondBytes(
                            outcome.bytes,
                            ContentType.parse(outcome.contentType),
                            HttpStatusCode.OK,
                        )
                    }

                    // Never archived. Not an error, just nothing here.
                    ResolveDocument.Outcome.NotArchived ->
                        call.respondText(
                            "no member row for $crn ($kind/$format)",
                            status = HttpStatusCode.NotFound,
                        )

                    // It exists but is not yet trustworthy.
                    is ResolveDocument.Outcome.NotReady ->
                        call.respondText(
                            "bundle is ${outcome.state}, not yet VERIFIED",
                            status = HttpStatusCode.Conflict,
                        )

                    // The bytes disagreed with the recorded checksum. Never a wrong document.
                    is ResolveDocument.Outcome.Corrupt -> {
                        log.error("corrupt read for {}: {}", crn, outcome.detail)
                        call.respondText(outcome.detail, status = HttpStatusCode.BadGateway)
                    }
                }
            }
        }
    }.start(wait = true)
}
