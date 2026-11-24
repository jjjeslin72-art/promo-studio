package com.juya.promostudio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import java.io.File

/**
 * 예약 시간이 되면 인스타·페북에 자동으로 올림.
 * 틱톡은 공식 심사 전에는 자동 공개 게시가 안 돼서, 알림을 띄워 틱톡 앱으로 넘겨줌.
 * (핸드폰이 켜져 있고 인터넷이 될 때 실행 — 꺼져 있으면 켜지는 대로 올라감)
 */
class PostJob : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        val id = params.extras.getString("post") ?: return false
        Thread {
            run(applicationContext, id)
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        const val CH = "posting"
        const val CH_PROG = "posting_progress"

        /** 지금 올라가는 중인 것: "게시물id:ig" → (단계 글, 퍼센트 / -1이면 끝 모름) */
        val live = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Int>>()
        fun liveOf(id: String, t: String) = live["$id:$t"]

        fun schedule(c: Context, p: Post) {
            val js = c.getSystemService(JobScheduler::class.java)!!
            val extras = PersistableBundle().apply { putString("post", p.id) }
            val delay = (p.time - System.currentTimeMillis()).coerceAtLeast(0)
            val job = JobInfo.Builder(p.id.hashCode(), ComponentName(c, PostJob::class.java))
                .setMinimumLatency(delay)
                .setOverrideDeadline(delay + 6 * 60 * 60 * 1000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setExtras(extras)
                .build()
            js.schedule(job)
        }

        fun cancel(c: Context, id: String) {
            c.getSystemService(JobScheduler::class.java)!!.cancel(id.hashCode())
        }

        /** 실제로 올리기 (예약 작업·"지금 올리기" 둘 다 여기로) */
        fun run(c: Context, id: String) {
            val p = Store.find(c, id) ?: return
            val f = File(p.mediaPath)
            if (!f.exists()) {
                p.targets().forEach { if (p.status[it] != "OK") p.status[it] = "사진·영상 파일이 없어요" }
                Store.upsert(c, p); return
            }
            for (t in listOf("ig", "fb")) {
                if (t !in p.targets() || p.status[t] == "OK") continue
                val key = "$id:$t"
                val nid = id.hashCode() + 100 + t.hashCode() % 50
                var lastNote = 0L
                val step: (String, Int) -> Unit = { msg, pct ->
                    live[key] = msg to pct
                    val now = System.currentTimeMillis()
                    if (now - lastNote > 700 || pct == 100 || pct < 0) { lastNote = now; progressNote(c, nid, "${name(t)}에 올리는 중", msg, pct) }
                }
                step("시작하는 중", -1)
                try {
                    val rid = if (t == "ig") Meta.postInstagram(c, f, p.isVideo, p.finalCaption(), step)
                    else Meta.postFacebook(c, f, p.isVideo, p.finalCaption(), step)
                    p.status[t] = "OK"; p.remoteIds[t] = rid
                } catch (e: Exception) {
                    p.status[t] = e.message ?: "알 수 없는 오류"
                } finally {
                    live.remove(key)
                    c.getSystemService(NotificationManager::class.java)?.cancel(nid)
                }
                Store.upsert(c, p)
            }
            val app = Store.apps(c).firstOrNull { it.id == p.appId }?.name ?: "홍보"
            val ok = listOf("ig", "fb").filter { it in p.targets() && p.status[it] == "OK" }
            val bad = listOf("ig", "fb").filter { it in p.targets() && p.status[it] != "OK" }
            if (ok.isNotEmpty() || bad.isNotEmpty()) {
                val msg = buildString {
                    if (ok.isNotEmpty()) append(ok.joinToString(", ") { name(it) } + " 올림 완료. ")
                    if (bad.isNotEmpty()) append(bad.joinToString(", ") { name(it) } + " 실패 — 앱에서 다시 시도하세요.")
                }
                notify(c, id.hashCode(), "$app 게시물", msg, Intent(c, MainActivity::class.java).putExtra("tab", 2))
            }
            if (p.tt && p.status["tt"] != "OK") {
                notify(c, id.hashCode() + 1, "틱톡에 올릴 시간이에요", "$app — 눌러서 틱톡으로 보내기 (문구·AI 표시 자동 복사)",
                    Intent(c, MainActivity::class.java).putExtra("tiktok", id))
            }
        }

        fun name(t: String) = when (t) { "ig" -> "인스타"; "fb" -> "페이스북"; else -> "틱톡" }

        /** 알림창에 진행 막대 (앱을 닫아도 보임) */
        private fun progressNote(c: Context, nid: Int, title: String, text: String, pct: Int) {
            val nm = c.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(NotificationChannel(CH_PROG, "올리는 중 표시", NotificationManager.IMPORTANCE_LOW))
            val pi = PendingIntent.getActivity(c, nid, Intent(c, MainActivity::class.java).putExtra("tab", 2)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(c, CH_PROG)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(title)
                .setContentText(if (pct >= 0) "$text $pct%" else "$text…")
                .setProgress(100, pct.coerceAtLeast(0), pct < 0)
                .setOngoing(true).setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .build()
            try { nm.notify(nid, n) } catch (_: SecurityException) { }
        }

        private fun notify(c: Context, nid: Int, title: String, text: String, intent: Intent) {
            val nm = c.getSystemService(NotificationManager::class.java)!!
            nm.createNotificationChannel(NotificationChannel(CH, "게시 알림", NotificationManager.IMPORTANCE_HIGH))
            val pi = PendingIntent.getActivity(c, nid, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(c, CH)
                .setSmallIcon(android.R.drawable.ic_menu_upload)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            try { nm.notify(nid, n) } catch (_: SecurityException) { }
        }
    }
}
