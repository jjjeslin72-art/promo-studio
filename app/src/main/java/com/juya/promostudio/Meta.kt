package com.juya.promostudio

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 인스타그램·페이스북 공식 API (Meta Graph API).
 * 주야 님 본인 계정·페이지에만 쓰는 방식(Standard Access) — 앱 심사 없이 사용.
 */
object Meta {
    const val VER = "v23.0"
    private const val G = "https://graph.facebook.com/$VER"
    private const val GV = "https://graph-video.facebook.com/$VER"

    data class Page(val id: String, val name: String, val token: String, val igId: String, val igName: String)

    fun connected(c: Context) = Store.get(c, "pageToken").isNotEmpty()
    fun igReady(c: Context) = connected(c) && Store.get(c, "igId").isNotEmpty()

    /** 짧은 토큰 → 60일 토큰 → 페이지 목록 (페이지 토큰은 만료 없음) */
    fun loadPages(appId: String, secret: String, shortToken: String): List<Page> {
        val long = Http.get("$G/oauth/access_token", mapOf(
            "grant_type" to "fb_exchange_token",
            "client_id" to appId,
            "client_secret" to secret,
            "fb_exchange_token" to shortToken,
        )).getString("access_token")
        val res = Http.get("$G/me/accounts", mapOf(
            "fields" to "id,name,access_token,instagram_business_account{id,username}",
            "limit" to "50",
            "access_token" to long,
        ))
        val arr = res.optJSONArray("data") ?: return emptyList()
        return List(arr.length()) {
            val o = arr.getJSONObject(it)
            val ig = o.optJSONObject("instagram_business_account")
            Page(o.getString("id"), o.optString("name"), o.getString("access_token"),
                ig?.optString("id") ?: "", ig?.optString("username") ?: "")
        }
    }

    fun savePage(c: Context, p: Page) {
        Store.set(c, "pageId", p.id); Store.set(c, "pageName", p.name); Store.set(c, "pageToken", p.token)
        Store.set(c, "igId", p.igId); Store.set(c, "igName", p.igName)
    }

    fun disconnect(c: Context) {
        listOf("pageId", "pageName", "pageToken", "igId", "igName").forEach { Store.set(c, it, "") }
    }

    private fun tok(c: Context) = Store.get(c, "pageToken")

    // ---------- 페이스북 페이지에 올리기 ----------
    fun postFacebook(c: Context, file: File, isVideo: Boolean, caption: String): String {
        val page = Store.get(c, "pageId")
        return if (isVideo) {
            Http.postMultipart("$GV/$page/videos",
                mapOf("description" to caption, "access_token" to tok(c)), "source", file, "video/mp4").getString("id")
        } else {
            val r = Http.postMultipart("$G/$page/photos",
                mapOf("message" to caption, "access_token" to tok(c)), "source", file, mime(file))
            r.optString("post_id").ifEmpty { r.getString("id") }
        }
    }

    // ---------- 인스타그램에 올리기 ----------
    fun postInstagram(c: Context, file: File, isVideo: Boolean, caption: String): String {
        val ig = Store.get(c, "igId")
        val container = if (isVideo) reelContainer(c, ig, file, caption) else photoContainer(c, ig, file, caption)
        waitReady(c, container)
        return Http.postForm("$G/$ig/media_publish", mapOf("creation_id" to container, "access_token" to tok(c))).getString("id")
    }

    /** 영상은 릴스로: 파일을 Meta 서버에 직접 업로드 (따로 서버 필요 없음) */
    private fun reelContainer(c: Context, ig: String, file: File, caption: String): String {
        val r = Http.postForm("$G/$ig/media", mapOf(
            "media_type" to "REELS",
            "upload_type" to "resumable",
            "caption" to caption,
            "share_to_feed" to "true",
            "access_token" to tok(c),
        ))
        val id = r.getString("id")
        val uri = r.optString("uri").ifEmpty { "https://rupload.facebook.com/ig-api-upload/$VER/$id" }
        Http.postBinary(uri, file, mapOf(
            "Authorization" to "OAuth ${tok(c)}",
            "offset" to "0",
            "file_size" to file.length().toString(),
        ))
        return id
    }

    /**
     * 인스타 사진 API는 인터넷 주소(URL)만 받음 → 페이지에 '비공개 사진'으로 먼저 올려서
     * 그 사진 주소를 인스타에 넘김 (페이스북에는 보이지 않음).
     */
    private fun photoContainer(c: Context, ig: String, file: File, caption: String): String {
        val page = Store.get(c, "pageId")
        val ph = Http.postMultipart("$G/$page/photos",
            mapOf("published" to "false", "access_token" to tok(c)), "source", file, mime(file)).getString("id")
        val imgs = Http.get("$G/$ph", mapOf("fields" to "images", "access_token" to tok(c))).getJSONArray("images")
        val url = imgs.getJSONObject(0).getString("source")
        return Http.postForm("$G/$ig/media", mapOf("image_url" to url, "caption" to caption, "access_token" to tok(c))).getString("id")
    }

    private fun waitReady(c: Context, container: String) {
        repeat(60) {
            val s = Http.get("$G/$container", mapOf("fields" to "status_code,status", "access_token" to tok(c)))
            when (s.optString("status_code")) {
                "FINISHED", "PUBLISHED" -> return
                "ERROR", "EXPIRED" -> throw ApiError("인스타 처리 실패: " + s.optString("status"))
            }
            Thread.sleep(5_000)
        }
        throw ApiError("인스타 처리 시간이 너무 오래 걸려요")
    }

    private fun mime(f: File) = if (f.name.endsWith(".png", true)) "image/png" else "image/jpeg"

    // ---------- 성과 ----------
    fun igProfile(c: Context): JSONObject =
        Http.get("$G/${Store.get(c, "igId")}", mapOf("fields" to "username,followers_count,media_count", "access_token" to tok(c)))

    fun igMedia(c: Context): org.json.JSONArray =
        Http.get("$G/${Store.get(c, "igId")}/media", mapOf(
            "fields" to "id,caption,like_count,comments_count,timestamp,permalink,media_type",
            "limit" to "30", "access_token" to tok(c),
        )).optJSONArray("data") ?: org.json.JSONArray()

    fun fbPage(c: Context): JSONObject =
        Http.get("$G/${Store.get(c, "pageId")}", mapOf("fields" to "name,followers_count,fan_count", "access_token" to tok(c)))
}
