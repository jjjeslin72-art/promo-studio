package com.juya.promostudio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 💰 광고 실험실 — "가장 싸게 가장 많이 설치"를 찾는 곳.
 * 1) 테스트: 채널마다 하루 R$10씩 3일 (최대 R$90)
 * 2) 비교: 설치 1건 비용(CPI)이 제일 싼 채널을 찾음
 * 3) 키우기: 번 돈 > 쓴 돈이면 이긴 채널만 하루 30%씩 늘리고, 비싼 채널은 멈춤
 * 광고비 결제·숫자 확인은 각 광고 앱에서 주야 님이 직접 (외부 앱이 결제할 수 없음)
 */
data class AdDay(val ch: String, val day: String, val spend: Double, val result: Int, val buys: Int)

data class Advice(val ch: String, val title: String, val detail: String, val level: Int) // level: 2 키우기 1 유지 0 멈춤 -1 테스트

object AdLab {
    val CH = listOf("google", "tiktok", "meta")
    fun name(ch: String) = when (ch) { "google" -> "구글 광고"; "tiktok" -> "틱톡 광고(Promote)"; else -> "인스타·페북 광고" }
    /** 구글은 '설치'가 바로 나오고, 틱톡·메타는 '링크 클릭'만 나옴 → 스토어 전환율로 설치 수를 추정 */
    fun resultName(ch: String) = if (ch == "google") "설치 수" else "링크 클릭 수"

    const val TEST_DAILY = 10.0
    const val TEST_DAYS = 3
    // Google 수수료 15% 뺀 뒤 평균 판매 1건 순수익 (영구권 R$29,90 → 25,42 / 월 R$9,90 → 8,42 섞어서 대략)
    fun netPerSale(c: Context) = Store.get(c, "netSale").toDoubleOrNull() ?: 20.0
    fun storeConv(c: Context) = (Store.get(c, "storeConv").toDoubleOrNull() ?: 50.0) / 100.0

    fun all(c: Context): MutableList<AdDay> {
        val raw = Store.get(c, "ads"); if (raw.isEmpty()) return mutableListOf()
        val a = JSONArray(raw)
        return MutableList(a.length()) { val o = a.getJSONObject(it); AdDay(o.getString("ch"), o.getString("d"), o.getDouble("s"), o.getInt("r"), o.optInt("b")) }
    }

    fun add(c: Context, d: AdDay) {
        val list = all(c).filter { !(it.ch == d.ch && it.day == d.day) }.toMutableList(); list.add(d)
        val a = JSONArray(); list.sortedBy { it.day }.forEach { a.put(JSONObject().put("ch", it.ch).put("d", it.day).put("s", it.spend).put("r", it.result).put("b", it.buys)) }
        Store.set(c, "ads", a.toString())
    }

    data class Sum(val days: Int, val spend: Double, val installs: Double, val buys: Int) {
        val cpi get() = if (installs > 0) spend / installs else Double.MAX_VALUE
    }

    fun sum(c: Context, ch: String): Sum {
        val l = all(c).filter { it.ch == ch }
        val inst = l.sumOf { if (ch == "google") it.result.toDouble() else it.result * storeConv(c) }
        return Sum(l.size, l.sumOf { it.spend }, inst, l.sumOf { it.buys })
    }

    /** 설치 1명이 평균 벌어 주는 돈 (구매가 3건 미만이면 '2%가 산다'고 가정 — 추정이라고 표시) */
    fun valuePerInstall(c: Context): Pair<Double, Boolean> {
        val s = CH.map { sum(c, it) }
        val inst = s.sumOf { it.installs }; val buys = s.sumOf { it.buys }
        return if (buys >= 3 && inst > 0) (buys / inst * netPerSale(c)) to false else (0.02 * netPerSale(c)) to true
    }

    fun advice(c: Context): List<Advice> {
        val (vpi, guess) = valuePerInstall(c)
        val sums = CH.associateWith { sum(c, it) }
        val tested = sums.filter { it.value.days >= TEST_DAYS && it.value.installs >= 3 }
        val best = tested.minByOrNull { it.value.cpi }
        return CH.map { ch ->
            val s = sums[ch]!!
            val last = all(c).filter { it.ch == ch }.maxByOrNull { it.day }?.spend ?: TEST_DAILY
            when {
                s.days < TEST_DAYS -> Advice(ch, "🧪 테스트: 하루 R$ %.0f".format(TEST_DAILY),
                    "${TEST_DAYS - s.days}일 더 돌려서 비교해요. 영상은 반응 제일 좋았던 걸로.", -1)
                s.installs < 3 -> Advice(ch, "⛔ 멈추세요", "R$ %.2f 썼는데 설치가 거의 없어요.".format(s.spend), 0)
                best != null && ch == best.key && s.cpi <= vpi -> Advice(ch, "🚀 키우세요: 하루 R$ %.0f → R$ %.0f".format(last, last * 1.3),
                    "설치 1건 R$ %.2f < 1명이 버는 돈 R$ %.2f%s. 이 채널에 돈을 몰아요. 설치 1건 비용이 R$ %.2f 넘으면 다시 줄여요."
                        .format(s.cpi, vpi, if (guess) "(추정)" else "", vpi), 2)
                best != null && ch == best.key -> Advice(ch, "🏆 제일 싸요 — 하루 R$ %.0f 유지".format(TEST_DAILY),
                    "설치 1건 R$ %.2f. 아직 1명이 버는 돈(R$ %.2f%s)보다 비싸요 → 금액은 늘리지 말고 영상·문구를 바꿔서 비용을 낮춰요."
                        .format(s.cpi, vpi, if (guess) ", 추정" else ""), 1)
                best != null && s.cpi > best.value.cpi * 1.5 -> Advice(ch, "⛔ 멈추세요",
                    "설치 1건 R$ %.2f — ${name(best.key)}(R$ %.2f)보다 1.5배 이상 비싸요. 이 돈을 ${name(best.key)}로 옮겨요.".format(s.cpi, best.value.cpi), 0)
                else -> Advice(ch, "↔ 하루 R$ 5로 줄여서 지켜보기", "설치 1건 R$ %.2f. 1등과 비슷해요.".format(s.cpi), 1)
            }
        }
    }
}
