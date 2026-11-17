package `in`.porter.archival.app

import `in`.porter.archival.data.fs.LocalFsObjectStore
import `in`.porter.archival.data.psql.Db
import `in`.porter.archival.data.psql.OmsWorklistSource
import `in`.porter.archival.data.psql.PsqlArchiveBundleRepo
import `in`.porter.archival.data.psql.PsqlArchiveMemberRepo
import `in`.porter.archival.domain.usecase.BuildWorklist
import `in`.porter.archival.domain.usecase.FrameBundle
import `in`.porter.archival.domain.usecase.ResolveDocument
import `in`.porter.archival.domain.usecase.VerifyBundle
import java.nio.file.Files

/**
 * Manual wiring. Production uses Dagger; at this size a constructor graph is
 * clearer than a generated one, and it keeps the object store swap obvious.
 */
class Wiring(val config: Config) : AutoCloseable {

    val db = Db(config)

    val store = LocalFsObjectStore(
        mapOf(
            config.archival.sourceBucket to config.runtime.sourceRoot,
            config.archival.archiveBucket to config.runtime.archiveRoot,
        ).onEach { (_, path) -> Files.createDirectories(path) }
    )

    val bundles = PsqlArchiveBundleRepo(db)
    val members = PsqlArchiveMemberRepo(db)
    val worklist = OmsWorklistSource(db)

    val buildWorklist = BuildWorklist(worklist, store, bundles, members, config)
    val frameBundle = FrameBundle(store, bundles, members, config)
    val verifyBundle = VerifyBundle(store, bundles, members, config)
    val resolveDocument = ResolveDocument(store, bundles, members)

    override fun close() = db.close()
}
