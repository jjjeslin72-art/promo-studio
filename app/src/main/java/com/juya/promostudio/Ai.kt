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
    /** 지금 시기에 맞는 브라질 행사 (문구에 자연스럽게 녹이도록) */
    fun season(now: java.util.Calendar = java.util.Calendar.getInstance()): String {
        val m = now.get(java.util.Calendar.MONTH) + 1; val d = now.get(java.util.Calendar.DAY_OF_MONTH)
        return when {
            (m == 11 && d >= 15) || (m == 12 && d <= 25) -> "크리스마스(Natal) 시즌 — 가족 모임, ceia de Natal"
            (m == 12 && d >= 26) || (m == 1 && d <= 1) -> "새해(Réveillon) 시즌 — 친구·가족과 새해 맞이 파티"
            m == 2 || (m == 3 && d <= 10) -> "카니발(Carnaval) 시즌"
            m == 6 || (m == 7 && d <= 15) -> "페스타 주니나(Festa Junina) 시즌"
            m == 5 && d <= 14 -> "어머니의 날(Dia das Mães) 시즌"
            m == 8 && d <= 14 -> "아버지의 날(Dia dos Pais) 시즌"
            else -> "특별한 행사 없음 — 주말·친구 모임 분위기"
        }
    }

    /**
     * lang: "pt" 포르투갈어, "ko" 한국어, "both" 둘 다
     * idea: 영상 내용 한 줄 (비워도 됨 — frames(영상 장면 사진)를 보고 AI가 알아서 파악)
     */
    fun caption(c: Context, app: PromoApp, idea: String, lang: String, frames: List<String> = emptyList()): String {
        val key = Store.get(c, "geminiKey")
        val model = Store.get(c, "geminiModel").ifEmpty { DEFAULT_MODEL }
        val langRule = when (lang) {
            "ko" -> "한국어로 써 주세요."
            "both" -> "먼저 브라질 포르투갈어로 쓰고, 한 줄 띄운 다음 같은 내용을 짧은 한국어로 써 주세요."
            else -> "브라질 포르투갈어로 써 주세요."
        }
        val prompt = """
            틱톡·인스타그램 릴스·페이스북에 올릴 앱 홍보 게시물 문구를 써 주세요. 목표는 앱 다운로드를 최대한 많이 받는 것.
            앱 이름: ${app.name}
            앱 설명: ${app.desc}
            ${if (app.link.isNotBlank()) "다운로드 링크: ${app.link}" else ""}
            영상/사진 내용: ${idea.ifBlank { if (frames.isNotEmpty()) "첨부한 장면 사진을 보고 직접 파악하세요" else "앱 사용 화면" }}
            지금 시기: ${season()}

            규칙:
            - $langRule
            - 첫 줄: 스크롤을 멈추게 하는 짧고 강한 한 문장 (이모지 1~2개). 시기 행사가 있으면 자연스럽게 연결
            - 본문 2줄: 쉬운 말로, 앱이 어떤 재미·문제 해결을 주는지
            - 마지막 줄: 행동 유도 (예: "Baixe grátis no Google Play 👉 link na bio")
            - 맨 끝에 해시태그 5~8개: 브라질에서 많이 쓰는 큰 태그 2~3개 + 이 앱에 딱 맞는 작은 태그 + 시기 태그
            - 전체 400자 이내
            - 절대 쓰지 말 것: 사실이 아닌 약속, 가짜 후기, 다른 브랜드·가수·노래 이름, "o melhor", "nº 1", "100%",
              "grátis para sempre", "vitalício", "ilimitado", 앱 설명에 없는 가격·기능
            - 설명이나 따옴표 없이 게시물 문구만 출력
        """.trimIndent()

        val parts = JSONArray()
        frames.forEach { b64 -> parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64))) }
        parts.put(JSONObject().put("text", prompt))
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", parts)))
        val r = Http.postJson(
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent",
            body, mapOf("x-goog-api-key" to key),
        )
        val out = r.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
        return buildString { for (i in 0 until out.length()) append(out.getJSONObject(i).optString("text")) }.trim()
    }
}
