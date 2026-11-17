package cl.detector.gas

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val SYNC_NOW = 4201
private const val SYNC_PERIODIC = 4202

internal fun scheduleHistorySync(context: Context) {
    val scheduler = context.getSystemService(JobScheduler::class.java)
    val service = ComponentName(context, HistorySyncJob::class.java)
    if (scheduler.getPendingJob(SYNC_PERIODIC) == null) {
        scheduler.schedule(JobInfo.Builder(SYNC_PERIODIC, service)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setPeriodic(15 * 60 * 1_000L)
            .build())
    }
    if (scheduler.getPendingJob(SYNC_NOW) == null) {
        scheduler.schedule(JobInfo.Builder(SYNC_NOW, service)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .build())
    }
}

class HistorySyncJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val jobs = mutableMapOf<Int, Job>()
    private val uploadLock = Mutex()

    override fun onStartJob(params: JobParameters): Boolean {
        jobs[params.jobId] = scope.launch {
            val retry = withTimeoutOrNull(45_000) {
                uploadLock.withLock { uploadCurrentAccount() }
            } ?: true
            jobs.remove(params.jobId)
            jobFinished(params, retry)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        jobs.remove(params.jobId)?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun uploadCurrentAccount(): Boolean {
        val app = FirebaseApp.getApps(applicationContext).firstOrNull() ?: return false
        val project = app.options.projectId ?: return false
        val auth = FirebaseAuth.getInstance(app)
        val user = auth.currentUser ?: return false
        val uid = user.uid
        val store = HistoryStore(applicationContext)
        try {
            var rows = store.pending(uid)
            if (rows.isEmpty()) return false
            val token = user.getIdToken(false).await().token ?: return false
            while (rows.isNotEmpty() && auth.currentUser?.uid == uid) {
                val sent = uploadBatch(rows, send = { batch ->
                    val response = firestore.commit(project, "Bearer $token", firestoreCommit(project, uid, batch))
                    response.errorBody()?.close()
                    if (!response.isSuccessful && (response.code() == 429 || response.code() >= 500)) {
                        throw IOException("Firestore temporalmente no disponible")
                    }
                    response.isSuccessful
                }, acknowledge = { store.acknowledge(uid, it) })
                if (!sent) return false
                rows = store.pending(uid)
            }
            return false
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            return true
        } finally {
            store.close()
        }
    }

    private interface Firestore {
        @POST("v1/projects/{project}/databases/(default)/documents:commit")
        suspend fun commit(
            @Path("project") project: String,
            @Header("Authorization") authorization: String,
            @Body request: JsonObject
        ): Response<Unit>
    }

    companion object {
        private val firestore: Firestore by lazy {
            Retrofit.Builder()
                .baseUrl("https://firestore.googleapis.com/")
                .client(OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).followRedirects(false).build())
                .addConverterFactory(GsonConverterFactory.create())
                .build().create(Firestore::class.java)
        }
    }
}
