import com.ustadmobile.door.*
import com.ustadmobile.door.ext.asRepository
import com.ustadmobile.door.ext.rootDatabase
import io.github.aakira.napier.DebugAntilog
import io.github.aakira.napier.Napier
import io.ktor.client.*
import io.ktor.client.engine.js.*
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import repdb.RepDb
import repdb.RepDbJsImplementations
import repdb.RepEntity
import kotlin.js.Promise
import kotlin.random.Random


private lateinit var repDb: RepDb

private lateinit var repRepo: RepDb

private lateinit var httpClient: HttpClient

private suspend fun openRepoDb() {
    Napier.base(DebugAntilog())
    val res = (window.fetch("""
            https://raw.githubusercontent.com/UstadMobile/door/dev-js-2/app-testdb/src/main/resources/worker.sql-asm.js
        """.trimIndent()) as Promise<dynamic>).await()
    val data = (res.blob() as Promise<dynamic>).await()
    val workerBlobUrl = URL.createObjectURL(data as Blob)

    val builderOptions = DatabaseBuilderOptions(
        RepDb::class, RepDbJsImplementations, "resDb", workerBlobUrl)
    httpClient = HttpClient(Js) { }

    Napier.d("Creating db and repo")
    repDb = DatabaseBuilder.databaseBuilder(builderOptions).build().also {
        it.clearAllTablesAsync()
    }
    Napier.d("Created db")
    Napier.d("Db name = ${(repDb.rootDatabase as DoorDatabaseJdbc).dbName}\n")
    Napier.d("Replicate wrapper name = ${(repDb as DoorDatabaseReplicateWrapper).dbName}\n")
    Napier.d("repDb toString = $repDb\n")
    val nodeId = Random.nextLong(0, Long.MAX_VALUE)
    repRepo = repDb.asRepository(
        RepositoryConfig.repositoryConfig(Any(), "http://localhost:8098/RepDb/",
        "secret", nodeId, httpClient, Json { encodeDefaults = true }) {
    })
    Napier.d("Created repo")
}

fun main() {
    window.onload = {
//        render(document.getElementById("root")!!) {
//            renderApp()
//        }
        GlobalScope.launch {
            openRepoDb()

            val completable = CompletableDeferred<RepEntity>()
            repDb.repDao.findByUidLive(42L).observeForever {
                if(it != null)
                    completable.complete(it)
            }

            Napier.i("Inserting into repDb")
            repDb.repDao.insertAsync(RepEntity().apply {
                this.reNumField = 500
            })

            Napier.i("Inserted into repDb")


            delay(5000)
            val entityAsyncQueryResult = repDb.repDao.findByUidAsync(42L)

            console.log("GOT ENTITY FROM SERVER:? $entityAsyncQueryResult")

        }

        Unit

    }
}