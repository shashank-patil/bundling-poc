package `in`.porter.archival.app

import `in`.porter.archival.data.psql.Db
import `in`.porter.archival.tools.DemoOps
import `in`.porter.archival.tools.SeedTool
import java.time.LocalDate

/**
 * One image, several entrypoints - the same shape the kind CronJobs use.
 */
fun main(args: Array<String>) {
    val command = args.firstOrNull() ?: "help"
    val config = Config.load()

    if (command == "migrate") {
        Db.migrate(config)
        println("migrations applied")
        return
    }

    if (command == "help") {
        println(usage())
        return
    }

    Wiring(config).use { wiring ->
        when (command) {
            "seed" -> wiring.let {
                SeedTool(it.db, it.store, config).run(
                    SeedTool.Spec(
                        orders = args.flag("--orders")?.toInt() ?: 5_000,
                        pdfSizeBytes = (args.flag("--size-kb")?.toInt() ?: 200) * 1024,
                        cohortDate = args.flag("--cohort")?.let(LocalDate::parse)
                            ?: LocalDate.now().minusDays(config.archival.cutoffDays.toLong()),
                    )
                )
            }

            "lister" -> runLister(wiring, args.flag("--cohort"))

            "bundler" -> runBundler(wiring)

            "serve" -> runServer(wiring, args.flag("--port")?.toInt() ?: 8080)

            "status" -> DemoOps.describe(wiring, resolveCohort(config, args.flag("--cohort")))

            "demo-corrupt" -> DemoOps.corruptOneSource(wiring, resolveCohort(config, args.flag("--cohort")))
                ?: println("nothing to corrupt - is there a BUILDING bundle?")

            "delete-sources" -> DemoOps.deleteVerifiedSources(wiring, resolveCohort(config, args.flag("--cohort")))

            else -> println(usage())
        }
    }
}

private fun Array<String>.flag(name: String): String? {
    val i = indexOf(name)
    return if (i >= 0 && i + 1 < size) this[i + 1] else null
}

private fun usage() = """
    bundling-poc

      migrate                                 apply the schema
      seed [--orders N --size-kb K --cohort D] generate invoices and oms_orders rows
      lister [--cohort D]                     cron 1: plan one day into bundles
      bundler                                 cron 2: frame, write and verify
      serve [--port P]                        retrieval API
      status [--cohort D]                     member and bundle counts
      demo-corrupt [--cohort D]               truncate one source to force a re-plan
      delete-sources [--cohort D]             phase 4: delete sources of VERIFIED bundles

    Design config lives in archival.properties, tuning in engine.properties.
    Both can be overridden by environment variables (SOURCE_BUCKET, CUTOFF_DAYS, ...).
""".trimIndent()
