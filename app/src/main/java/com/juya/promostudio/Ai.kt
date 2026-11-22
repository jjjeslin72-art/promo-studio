package com.juya.promostudio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 구글 Gemini로 홍보 문구·해시태그 만들기 (주야 님 API 키 사용) */
object Ai {
    const val DEFAULT_MODEL = "gemini-2.5-flash"

    fun ready(c: Context) = Store.get(c, "geminiKey").isNotEmpty()

    /**
     * lang: "pt" 포르투갈어, "ko" 한국어, "both" 둘 다
     * idea: 영상 내용 한 줄 (예: "옷 사진이 모델 착용샷으로 바뀌는 장면")
     */
    fun caption(c: Context, app: PromoApp, idea: String, lang: String): String {
        val key = Store.get(c, "geminiKey")
        val model = Store.get(c, "geminiModel").ifEmpty { DEFAULT_MODEL }
        val langRule = when (lang) {
            "ko" -> "한국어로 써 주세요."
            "both" -> "먼저 브라질 포르투갈어로 쓰고, 한 줄 띄운 다음 같은 내용을 짧은 한국어로 써 주세요."
            else -> "브라질 포르투갈어로 써 주세요."
        }
        val prompt = """
            인스타그램 릴스·틱톡·페이스북에 올릴 앱 홍보 게시물 문구를 써 주세요.
            앱 이름: ${app.name}
            앱 설명: ${app.desc}
            ${if (app.link.isNotBlank()) "다운로드 링크: ${app.link}" else ""}
            영상/사진 내용: ${idea.ifBlank { "앱 사용 화면" }}

            규칙:
            - $langRule
            - 첫 줄은 스크롤을 멈추게 하는 짧은 한 문장 (이모지 1개 가능)
            - 본문은 2~3줄, 쉬운 말로, 앱이 어떤 문제를 해결하는지
            - 마지막에 "프로필 링크에서 받기" 같은 행동 유도 한 줄
            - 맨 끝에 관련 해시태그 8~12개 (브라질에서 많이 쓰는 것 위주)
            - 사실이 아닌 약속(예: "무조건 돈 벌어요", 가짜 후기, 다른 브랜드 이름)은 쓰지 마세요
            - 설명이나 따옴표 없이 게시물 문구만 출력
        """.trimIndent()

        val body = JSONObject().put("contents", JSONArray().put(
            JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))
        ))
        val r = Http.postJson(
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent",
            body, mapOf("x-goog-api-key" to key),
        )
        val parts = r.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
        return buildString { for (i in 0 until parts.length()) append(parts.getJSONObject(i).optString("text")) }.trim()
    }
}
