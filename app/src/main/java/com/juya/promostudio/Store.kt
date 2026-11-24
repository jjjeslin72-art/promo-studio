package com.juya.promostudio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 홍보할 내 앱 하나 */
data class PromoApp(
    val id: String,
    var name: String,
    var desc: String,
    var link: String,
)

/** 게시물 하나 (사진 또는 영상 1개 + 문구 + 올릴 SNS + 예약 시간) */
data class Post(
    val id: String,
    var appId: String,
    var caption: String,
    var mediaPath: String,
    var isVideo: Boolean,
    var ig: Boolean,
    var fb: Boolean,
    var tt: Boolean,
    var time: Long,
    // AI로 만든 사진·영상이면 문구 끝에 AI 표시를 자동으로 붙임 (틱톡·인스타 규칙)
    var ai: Boolean = true,
    // SNS별 상태: "" = 대기, "OK" = 완료, 그 밖 = 실패 이유
    val status: MutableMap<String, String> = mutableMapOf(),
    // 올라간 게시물 id (성과 보기용)
    val remoteIds: MutableMap<String, String> = mutableMapOf(),
) {
    /** 실제로 올라가는 문구 (AI 표시 포함) */
    fun finalCaption(): String {
        val c = caption.trim()
        if (!ai || c.contains(AI_TAG_CHECK, ignoreCase = true)) return c
        return c + "\n\n" + AI_TAG
    }
    fun targets(): List<String> = buildList {
        if (ig) add("ig"); if (fb) add("fb"); if (tt) add("tt")
    }
    fun isDone(): Boolean = targets().all { status[it] == "OK" }
    // 자동으로 올리는 건 인스타뿐 → 인스타 실패만 '다시 시도' 대상 (페이스북·틱톡은 알림 눌러서)
    fun hasError(): Boolean = ig && status["ig"].let { it != null && it != "" && it != "OK" }
}

const val AI_TAG = "🤖 Conteúdo gerado por IA #IA"
const val AI_TAG_CHECK = "gerado por IA"

data class Snapshot(val day: String, val ig: Int, val fb: Int)

/** 핸드폰 안에만 저장 (서버 없음) */
object Store {
    private const val P = "promo"

    private fun sp(c: Context) = c.getSharedPreferences(P, Context.MODE_PRIVATE)

    fun get(c: Context, k: String): String = sp(c).getString(k, "") ?: ""
    fun set(c: Context, k: String, v: String) = sp(c).edit().putString(k, v).apply()

    // ---------- 내 앱 목록 ----------
    private val defaultApps = listOf(
        PromoApp("noraebang", "Noraebang K", "핸드폰을 노래방 마이크로! 유튜브 틀고 바로 노래방", ""),
        PromoApp("clickmoda", "Click.moda AI", "옷 사진 한 장으로 AI 모델 착용샷 — 옷가게·도매 사장님용", ""),
        PromoApp("rotacerta", "Rota Certa", "배달·배송 기사님용 여러 곳 돌기 최적 경로", ""),
        PromoApp("jaego", "재고 정리판", "생산·판매·재고 정리와 매출·수익 계산", ""),
        PromoApp("emoticon", "이모티콘 공방", "카카오톡·왓츠앱 이모티콘을 규격대로 쉽게 제작", ""),
        PromoApp("hangul", "한글 첫걸음", "포르투갈어로 배우는 한글 — 소리·쓰기·발음 교정", ""),
    )

    fun apps(c: Context): MutableList<PromoApp> {
        val raw = get(c, "apps")
        if (raw.isEmpty()) return defaultApps.map { it.copy() }.toMutableList().also { saveApps(c, it) }
        val a = JSONArray(raw)
        return MutableList(a.length()) {
            val o = a.getJSONObject(it)
            PromoApp(o.getString("id"), o.optString("name"), o.optString("desc"), o.optString("link"))
        }
    }

    fun saveApps(c: Context, list: List<PromoApp>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("desc", it.desc).put("link", it.link)) }
        set(c, "apps", a.toString())
    }

    fun newAppId(): String = UUID.randomUUID().toString().take(8)

    // ---------- 게시물 ----------
    @Synchronized
    fun posts(c: Context): MutableList<Post> {
        val raw = get(c, "posts")
        if (raw.isEmpty()) return mutableListOf()
        val a = JSONArray(raw)
        return MutableList(a.length()) { fromJson(a.getJSONObject(it)) }
    }

    @Synchronized
    fun savePosts(c: Context, list: List<Post>) {
        val a = JSONArray()
        list.forEach { a.put(toJson(it)) }
        sp(c).edit().putString("posts", a.toString()).commit()
    }

    @Synchronized
    fun upsert(c: Context, p: Post) {
        val list = posts(c)
        val i = list.indexOfFirst { it.id == p.id }
        if (i >= 0) list[i] = p else list.add(p)
        savePosts(c, list)
    }

    @Synchronized
    fun find(c: Context, id: String): Post? = posts(c).firstOrNull { it.id == id }

    @Synchronized
    fun delete(c: Context, id: String) {
        val list = posts(c)
        list.firstOrNull { it.id == id }?.let { p ->
            // 다른 게시물이 같은 파일을 안 쓰면 파일도 지움
            if (list.none { it.id != id && it.mediaPath == p.mediaPath }) java.io.File(p.mediaPath).delete()
        }
        savePosts(c, list.filter { it.id != id })
    }

    fun newPostId(): String = UUID.randomUUID().toString()

    private fun toJson(p: Post) = JSONObject()
        .put("id", p.id).put("app", p.appId).put("cap", p.caption)
        .put("media", p.mediaPath).put("video", p.isVideo)
        .put("ig", p.ig).put("fb", p.fb).put("tt", p.tt).put("time", p.time).put("ai", p.ai)
        .put("status", JSONObject(p.status as Map<*, *>))
        .put("remote", JSONObject(p.remoteIds as Map<*, *>))

    private fun fromJson(o: JSONObject): Post {
        val st = mutableMapOf<String, String>()
        o.optJSONObject("status")?.let { s -> s.keys().forEach { st[it] = s.getString(it) } }
        val rm = mutableMapOf<String, String>()
        o.optJSONObject("remote")?.let { s -> s.keys().forEach { rm[it] = s.getString(it) } }
        return Post(
            o.getString("id"), o.optString("app"), o.optString("cap"),
            o.optString("media"), o.optBoolean("video"),
            o.optBoolean("ig"), o.optBoolean("fb"), o.optBoolean("tt"),
            o.optLong("time"), o.optBoolean("ai", true), st, rm,
        )
    }

    // ---------- 팔로워 기록 (하루 1번) ----------
    fun snapshots(c: Context): List<Snapshot> {
        val raw = get(c, "snaps")
        if (raw.isEmpty()) return emptyList()
        val a = JSONArray(raw)
        return List(a.length()) { val o = a.getJSONObject(it); Snapshot(o.getString("d"), o.optInt("ig", -1), o.optInt("fb", -1)) }
    }

    fun addSnapshot(c: Context, s: Snapshot) {
        val list = snapshots(c).filter { it.day != s.day }.toMutableList()
        list.add(s)
        val a = JSONArray()
        list.sortedBy { it.day }.takeLast(120).forEach { a.put(JSONObject().put("d", it.day).put("ig", it.ig).put("fb", it.fb)) }
        set(c, "snaps", a.toString())
    }
}
