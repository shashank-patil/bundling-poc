package `in`.porter.archival.app

import `in`.porter.archival.domain.model.CohortDate
import `in`.porter.archival.domain.usecase.FrameBundle
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.time.LocalDate

private val log = LoggerFactory.getLogger("cron")

/** The cohort is T minus the configured cutoff, unless a day is named explicitly. */
fun resolveCohort(config: Config, arg: String?): CohortDate =
    CohortDate(
        arg?.let(LocalDate::parse)
            ?: LocalDate.now().minusDays(config.archival.cutoffDays.toLong())
    )

/**
 * Cron 1. Reads metadata only; writes one member row per file with its position
 * already decided. Nothing is read, nothing is uploaded.
 */
fun runLister(wiring: Wiring, cohortArg: String?) {
    val cohort = resolveCohort(wiring.config, cohortArg)
    log.info("lister starting for cohort {}", cohort)
    val result = wiring.buildWorklist.run(cohort)
    log.info(
        "lister done: {} bundles planned, {} members planned, {} objects skipped",
        result.bundlesPlanned, result.membersPlanned, result.objectsSkipped,
    )
}

/**
 * Cron 2. Claims one bundle at a time with a lease, frames it, then runs the
 * verification checks. Keeps going until there is nothing left to claim.
 */
fun runBundler(wiring: Wiring) {
    val owner = "${InetAddress.getLocalHost().hostName}-${ProcessHandle.current().pid()}"
    var written = 0
    var replanned = 0

    while (true) {
        when (val outcome = wiring.frameBundle.runOnce(owner)) {
            is FrameBundle.Outcome.Nothing -> break

            is FrameBundle.Outcome.Written -> {
                written++
                val report = wiring.verifyBundle.verify(outcome.bundleId)
                report.checks.forEach { c ->
                    log.info("  bundle {} check {} {} - {}", outcome.bundleId, c.name, if (c.passed) "PASS" else "FAIL", c.detail)
                }
            }

            is FrameBundle.Outcome.Replanned -> {
                replanned++
                log.warn(
                    "bundle {} failed and was re-planned into {} (dropped {})",
                    outcome.failedBundleId, outcome.newBundleId, outcome.dropped,
                )
            }
        }
    }
    log.info("bundler done: {} bundles written, {} re-planned", written, replanned)
}
