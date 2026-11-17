package `in`.porter.archival.app

import `in`.porter.archival.data.psql.Db

/**
 * One image, several entrypoints - the same shape the kind CronJobs use.
 */
fun main(args: Array<String>) {
    val command = args.firstOrNull() ?: "help"
    val config = Config.fromEnv()

    when (command) {
        "migrate" -> {
            Db.migrate(config)
            println("migrations applied")
        }
        else -> {
            println(
                """
                bundling-poc - usage:
                  migrate    apply the schema
                  seed       generate dummy invoices and oms_orders rows
                  lister     cron 1: build one day's worklist
                  bundler    cron 2: frame, write and verify one bundle
                  serve      retrieval API
                """.trimIndent()
            )
        }
    }
}
