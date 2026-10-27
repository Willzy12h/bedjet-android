package io.github.willzy12h.nocturne.platform

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import io.github.willzy12h.nocturne.R
import java.time.Duration
import java.time.Instant

class AndroidSleepSource(context: Context) : SleepSource {
    private val client=HealthConnectClient.getOrCreate(context)
    override suspend fun permitted()=client.permissionController.getGrantedPermissions().containsAll(PERMISSIONS)
    override suspend fun sessions(start: Instant,end: Instant): List<SleepStage> {
        val stages=mutableListOf<SleepStage>();var token:String?=null
        do {
            // Include sessions crossing the card's boundary; the reader clips stages to the saved night's instants.
            val page=client.readRecords(ReadRecordsRequest(SleepSessionRecord::class,
                TimeRangeFilter.between(start.minus(Duration.ofDays(1)),end.plus(Duration.ofDays(1))),pageToken=token))
            page.records.forEach { session -> session.stages.forEach { stages += SleepStage(it.startTime,it.endTime,it.stage) } }
            token=page.pageToken
        } while (token != null)
        return stages
    }
    companion object {
        val PERMISSIONS=setOf(HealthPermission.getReadPermission(SleepSessionRecord::class))
        fun reader(context: Context)=SleepOverlayReader({HealthConnectClient.getSdkStatus(context)==HealthConnectClient.SDK_AVAILABLE}) {AndroidSleepSource(context)}
    }
}

class AndroidSleepWriter(context: Context) : SleepSessionWriter {
    private val client by lazy { HealthConnectClient.getOrCreate(context) }
    override suspend fun permitted() = client.permissionController.getGrantedPermissions().containsAll(PERMISSIONS)
    override suspend fun upsert(record: SleepSessionRecord) {
        // clientRecordId/clientRecordVersion make a retry an upsert, including an already accepted equal version.
        client.insertRecords(listOf(record))
    }
    companion object {
        val PERMISSIONS = setOf(HealthPermission.getWritePermission(SleepSessionRecord::class))
    }
}

/** Actual data-use rationale required by both the legacy provider and Android 14+ permission UI. */
class HealthRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlertDialog.Builder(this).setTitle(R.string.feature_health_title).setMessage(R.string.health_data_rationale)
            .setPositiveButton(android.R.string.ok){_,_->finish()}.setOnCancelListener{finish()}.show()
    }
}
