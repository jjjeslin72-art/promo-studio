package com.juya.promostudio

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets

import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    // ---------- 색 ----------
    private val bg = Color.parseColor("#12101C")
    private val panel = Color.parseColor("#1F1B2E")
    private val line = Color.parseColor("#2E2942")
    private val accent = Color.parseColor("#FF5E7E")
    private val violet = Color.parseColor("#7B3FF2")
    private val ink = Color.parseColor("#F4F1FA")
    private val muted = Color.parseColor("#A39DB8")
    private val green = Color.parseColor("#3DDC97")
    private val red = Color.parseColor("#FF6B6B")

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var body: LinearLayout
    private lateinit var tabBar: LinearLayout
    private var tab = 0
    private val tabs = listOf("내 앱", "새 게시물", "예약", "성과", "설정")

    // ---------- 새 게시물 작성 중인 내용 ----------
    private var dId: String? = null
    private var dApp = ""
    private var dMedia = ""
    private var dVideo = false
    private var dCaption = ""
    private var dIdea = ""
    private var dLang = "pt"
    private var dIg = true
    private var dFb = true
    private var dTt = true
    private var dTime = 0L
    private var dAi = true

    private val fmt = SimpleDateFormat("M월 d일 (E) HH:mm", Locale.KOREAN)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = panel

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(20), dp(16), dp(32)) }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        tabBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(panel) }
        root.addView(tabBar, LinearLayout.LayoutParams(-1, dp(64)))
        setContentView(root)

        // 안드로이드 15+는 화면이 상태바·하단 버튼 밑까지 그려짐 → 그만큼 비워서 탭이 가려지지 않게
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9)
        }
        resetDraft()
        show(0)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        if (i == null) return
        i.getStringExtra("tiktok")?.let { shareTikTok(it); i.removeExtra("tiktok") }
        if (i.hasExtra("tab")) { show(i.getIntExtra("tab", 0)); i.removeExtra("tab") }
        if (i.action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            val u = i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (u != null) { resetDraft(); importMedia(u); show(1) }
            i.action = null
        }
    }

    override fun onResume() {
        super.onResume()
        if (tab == 2) show(2)
    }

    // =====================================================================
    // 화면 전환
    // =====================================================================
    private fun show(t: Int) {
        tab = t
        body.removeAllViews()
        when (t) {
            0 -> homeScreen()
            1 -> editorScreen()
            2 -> scheduleScreen()
            3 -> statsScreen()
            4 -> settingsScreen()
        }
        tabBar.removeAllViews()
        tabs.forEachIndexed { i, name ->
            val b = TextView(this).apply {
                text = name; gravity = Gravity.CENTER; textSize = 13f
                setTextColor(if (i == t) accent else muted)
                if (i == t) setTypeface(typeface, Typeface.BOLD)
                setOnClickListener { show(i) }
            }
            tabBar.addView(b, LinearLayout.LayoutParams(0, -1, 1f))
        }
    }

    // =====================================================================
    // 0. 내 앱 (앱별 홍보 관리)
    // =====================================================================
    private fun homeScreen() {
        title("내 앱 홍보", "앱마다 홍보 게시물을 만들고 관리해요")
        val posts = Store.posts(this)
        if (!Meta.connected(this) || !Ai.ready(this)) {
            notice(buildString {
                append("처음 한 번 '설정'에서 연결해 주세요:\n")
                if (!Meta.connected(this@MainActivity)) append("• 인스타·페이스북 연결 (자동 올리기)\n")
                if (!Ai.ready(this@MainActivity)) append("• AI 문구용 Gemini 키\n")
                append("틱톡은 연결 없이 바로 쓸 수 있어요.")
            }) { show(4) }
        }
        Store.apps(this).forEach { app ->
            val mine = posts.filter { it.appId == app.id }
            val done = mine.count { it.isDone() }
            val waiting = mine.count { !it.isDone() && !it.hasError() }
            val c = card()
            c.addView(text(app.name, 18f, ink, true))
            if (app.desc.isNotBlank()) c.addView(text(app.desc, 13f, muted))
            c.addView(text("올린 게시물 $done · 예약 대기 $waiting", 12f, if (done > 0) green else muted).also { top(it, 6) })
            val btns = row()
            btns.addView(button("홍보 만들기", accent) { resetDraft(); dApp = app.id; show(1) }, weight())
            btns.addView(space(8))
            btns.addView(button("수정", line) { editApp(app) }, weight())
            top(btns, 10)
            c.addView(btns)
        }
        body.addView(button("+ 앱 추가", violet) { editApp(null) }.also { top(it, 8) })
    }

    private fun editApp(app: PromoApp?) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val n = dlgInput(box, "앱 이름", app?.name ?: "")
        val d = dlgInput(box, "한 줄 설명 (AI 문구에 쓰여요)", app?.desc ?: "")
        val l = dlgInput(box, "플레이스토어 링크 (없으면 비워 두기)", app?.link ?: "")
        val dlg = AlertDialog.Builder(this).setTitle(if (app == null) "앱 추가" else "앱 수정").setView(box)
            .setPositiveButton("저장") { _, _ ->
                val list = Store.apps(this)
                if (n.text.isBlank()) return@setPositiveButton
                if (app == null) list.add(PromoApp(Store.newAppId(), n.text.toString().trim(), d.text.toString().trim(), l.text.toString().trim()))
                else list.firstOrNull { it.id == app.id }?.apply { name = n.text.toString().trim(); desc = d.text.toString().trim(); link = l.text.toString().trim() }
                Store.saveApps(this, list); show(0)
            }
            .setNegativeButton("취소", null)
        if (app != null) dlg.setNeutralButton("삭제") { _, _ ->
            Store.saveApps(this, Store.apps(this).filter { it.id != app.id }); show(0)
        }
        dlg.show()
    }

    // =====================================================================
    // 1. 새 게시물
    // =====================================================================
    private fun resetDraft() {
        dId = null; dMedia = ""; dVideo = false; dCaption = ""; dIdea = ""; dAi = true
        dIg = Meta.igReady(this); dFb = Meta.connected(this); dTt = true
        dApp = Store.apps(this).firstOrNull()?.id ?: ""
        // 기본 예약: 오늘 저녁 7시 (지났으면 내일)
        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 19); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }
        if (cal.timeInMillis < System.currentTimeMillis() + 10 * 60_000) cal.add(Calendar.DAY_OF_MONTH, 1)
        dTime = cal.timeInMillis
    }

    private fun editorScreen() {
        title(if (dId == null) "새 게시물" else "게시물 수정", "사진·영상 1개 → 문구 → 올릴 곳 → 시간")
        val apps = Store.apps(this)
        val app = apps.firstOrNull { it.id == dApp } ?: apps.firstOrNull()

        // 1) 어떤 앱 홍보?
        label("1. 홍보할 앱")
        body.addView(button(app?.name ?: "앱을 먼저 추가하세요", panel) {
            if (apps.isEmpty()) { show(0); return@button }
            AlertDialog.Builder(this).setItems(apps.map { it.name }.toTypedArray()) { _, i -> dApp = apps[i].id; saveFields(); show(1) }.show()
        })

        // 2) 사진·영상
        label("2. 사진 또는 영상")
        if (dMedia.isNotEmpty() && File(dMedia).exists()) {
            val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(panel) }
            thumb(File(dMedia), dVideo)?.let { iv.setImageBitmap(it) }
            body.addView(iv, LinearLayout.LayoutParams(-1, dp(220)))
            body.addView(text((if (dVideo) "영상" else "사진") + " · " + (File(dMedia).length() / 1024 / 1024) + "MB", 12f, muted))
        }
        body.addView(button(if (dMedia.isEmpty()) "갤러리에서 고르기" else "다른 파일로 바꾸기", violet) { saveFields(); pick() })

        // 3) 문구
        label("3. 문구 · 해시태그")
        val idea = input("영상 내용 한 줄 (예: 옷 사진이 모델 착용샷으로 바뀌는 장면)", dIdea, false)
        body.addView(idea)
        val langRow = row()
        listOf("pt" to "포르투갈어", "ko" to "한국어", "both" to "둘 다").forEach { (k, v) ->
            langRow.addView(button(v, if (dLang == k) accent else panel) { dLang = k; saveFieldsFrom(idea, null); show(1) }, weight())
            langRow.addView(space(6))
        }
        top(langRow, 8); body.addView(langRow)
        val cap = input("문구를 직접 쓰거나 AI로 만들어 보세요", dCaption, true)
        body.addView(button("✨ AI 문구 만들기", violet) {
            if (!Ai.ready(this)) { toast("설정에서 Gemini 키를 먼저 넣어 주세요"); return@button }
            if (app == null) return@button
            saveFieldsFrom(idea, cap)
            toast("AI가 문구를 쓰고 있어요…")
            Thread {
                try {
                    val r = Ai.caption(this, app, dIdea, dLang)
                    ui.post { dCaption = r; show(1) }
                } catch (e: Exception) { ui.post { toast("AI 오류: " + e.message) } }
            }.start()
        }.also { top(it, 8) })
        body.addView(cap)
        val counter = text("", 12f, muted)
        body.addView(counter)
        // AI 표시
        val aiBox = check("🤖 AI로 만든 사진·영상이에요 (문구 끝에 '$AI_TAG' 자동으로 붙임)", dAi, true)
        body.addView(aiBox)
        val aiNote = text("", 12f, muted)
        body.addView(aiNote)
        fun refresh() {
            val txt = cap.text.toString()
            val full = if (aiBox.isChecked && !txt.contains(AI_TAG_CHECK, true) && txt.isNotBlank()) txt.trim() + "\n\n" + AI_TAG else txt
            val tags = Regex("#\\S+").findAll(full).count()
            val over = tags > 30 || full.length > 2200
            counter.text = "${full.length}/2200자 · 해시태그 ${tags}/30개" + if (over) "  ⚠ 인스타 제한 넘음" else ""
            counter.setTextColor(if (over) red else muted)
            aiNote.text = if (aiBox.isChecked) "틱톡·인스타 규칙상 AI 영상은 꼭 표시해야 해요. 문구에 들어가니 틱톡에서 따로 안 켜도 돼요." else "직접 찍은 사진·영상일 때만 꺼 주세요."
        }
        cap.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { refresh() }
        })
        aiBox.setOnCheckedChangeListener { _, on -> dAi = on; refresh() }
        refresh()

        // 4) 올릴 곳
        label("4. 올릴 곳")
        val ig = check("인스타그램 (자동)" + if (!Meta.igReady(this)) " — 설정에서 연결 필요" else "", dIg && Meta.igReady(this), Meta.igReady(this))
        val fb = check("페이스북 페이지 (자동)" + if (!Meta.connected(this)) " — 설정에서 연결 필요" else "", dFb && Meta.connected(this), Meta.connected(this))
        val tt = check("틱톡 (시간 되면 알림 → 눌러서 올리기)", dTt, true)
        body.addView(ig); body.addView(fb); body.addView(tt)
        if (!dVideo && dMedia.isNotEmpty()) body.addView(text("틱톡은 영상 위주예요. 사진은 틱톡에서 '사진' 게시로 올라가요.", 12f, muted))

        // 5) 시간
        label("5. 올릴 시간")
        body.addView(button("🗓 " + fmt.format(Date(dTime)), panel) {
            saveFieldsFrom(idea, cap); dIg = ig.isChecked; dFb = fb.isChecked; dTt = tt.isChecked
            pickTime()
        })
        body.addView(text("팁: 브라질 손님 대상은 보통 점심(12~13시)·저녁(19~21시) 반응이 좋아요. 성과 탭에서 내 결과를 보고 맞춰 가세요.", 12f, muted))

        val actions = row()
        actions.addView(button("지금 올리기", line) { saveFieldsFrom(idea, cap); finishDraft(ig, fb, tt, now = true) }, weight())
        actions.addView(space(8))
        actions.addView(button("예약하기", accent) { saveFieldsFrom(idea, cap); finishDraft(ig, fb, tt, now = false) }, weight())
        top(actions, 16)
        body.addView(actions)

        body.addView(text("※ 다른 사람 음악·영상·브랜드 로고가 들어간 파일은 저작권 신고로 삭제·정지될 수 있어요. 직접 찍은 화면이나 상업용 무료 음원을 쓰세요.", 11f, muted).also { top(it, 14) })

        idea.tag = "idea"; cap.tag = "cap"
    }

    private fun saveFields() {
        (body.findViewWithTag<EditText>("idea"))?.let { dIdea = it.text.toString() }
        (body.findViewWithTag<EditText>("cap"))?.let { dCaption = it.text.toString() }
    }

    private fun saveFieldsFrom(idea: EditText?, cap: EditText?) {
        idea?.let { dIdea = it.text.toString() }
        cap?.let { dCaption = it.text.toString() }
    }

    private fun finishDraft(ig: CheckBox, fb: CheckBox, tt: CheckBox, now: Boolean) {
        dIg = ig.isChecked; dFb = fb.isChecked; dTt = tt.isChecked
        when {
            dApp.isEmpty() -> { toast("홍보할 앱을 골라 주세요"); return }
            dMedia.isEmpty() -> { toast("사진이나 영상을 골라 주세요"); return }
            !dIg && !dFb && !dTt -> { toast("올릴 곳을 하나 이상 골라 주세요"); return }
            dCaption.isBlank() -> { toast("문구를 써 주세요"); return }
            (if (dAi) dCaption.trim().length + AI_TAG.length + 2 else dCaption.length) > 2200 && dIg -> { toast("인스타 문구는 2200자까지예요"); return }
            !now && dTime < System.currentTimeMillis() -> { toast("예약 시간이 이미 지났어요"); return }
        }
        val old = dId?.let { Store.find(this, it) }
        val p = Post(dId ?: Store.newPostId(), dApp, dCaption.trim(), dMedia, dVideo, dIg, dFb, dTt,
            if (now) System.currentTimeMillis() else dTime, dAi,
            old?.status ?: mutableMapOf(), old?.remoteIds ?: mutableMapOf())
        // 수정한 SNS는 다시 대기 상태로
        p.targets().forEach { if (p.status[it] != "OK") p.status[it] = "" }
        Store.upsert(this, p)
        if (now) {
            toast("올리는 중… 완료되면 알림이 와요")
            Thread { PostJob.run(applicationContext, p.id); ui.post { if (tab == 2) show(2) } }.start()
            if (p.tt) shareTikTok(p.id)
        } else {
            PostJob.schedule(this, p)
            toast("예약 완료: " + fmt.format(Date(p.time)))
        }
        resetDraft()
        show(2)
    }

    private fun pickTime() {
        val cal = Calendar.getInstance().apply { timeInMillis = dTime }
        DatePickerDialog(this, { _, y, m, d ->
            TimePickerDialog(this, { _, h, min ->
                cal.set(y, m, d, h, min, 0); dTime = cal.timeInMillis; show(1)
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).apply {
            datePicker.minDate = System.currentTimeMillis() - 1000
        }.show()
    }

    // ---------- 파일 고르기 ----------
    private fun pick() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK) data?.data?.let { importMedia(it); show(1) }
    }

    /** 고른 파일을 앱 안으로 복사 (예약 시간에 갤러리에서 지워져도 올라가게) */
    private fun importMedia(u: Uri) {
        try {
            val mime = contentResolver.getType(u) ?: ""
            var name = "file"
            contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0) ?: name
            }
            val video = mime.startsWith("video") || name.endsWith(".mp4", true) || name.endsWith(".mov", true)
            val ext = when {
                video && (mime.contains("quicktime") || name.endsWith(".mov", true)) -> "mov"
                video -> "mp4"
                mime.contains("png") || name.endsWith(".png", true) -> "png"
                else -> "jpg"
            }
            val dir = File(filesDir, "media").apply { mkdirs() }
            val out = File(dir, "m_" + System.currentTimeMillis() + "." + ext)
            contentResolver.openInputStream(u)?.use { inp -> out.outputStream().use { inp.copyTo(it) } }
            if (!video && ext == "png") {
                // 인스타는 JPG를 가장 잘 받음 → 변환
                BitmapFactory.decodeFile(out.path)?.let { bm ->
                    val jpg = File(dir, out.nameWithoutExtension + ".jpg")
                    jpg.outputStream().use { bm.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                    out.delete(); dMedia = jpg.path; dVideo = false; return
                }
            }
            dMedia = out.path; dVideo = video
            if (video && ext == "mov") toast("MOV 영상은 인스타에서 안 될 수 있어요. 가능하면 MP4로 올려 주세요.")
        } catch (e: Exception) {
            toast("파일을 못 가져왔어요: " + e.message)
        }
    }

    private fun thumb(f: File, video: Boolean): Bitmap? = try {
        if (video) {
            val r = MediaMetadataRetriever()
            r.setDataSource(f.path)
            val b = r.getFrameAtTime(1_000_000)
            r.release(); b
        } else {
            val o = BitmapFactory.Options().apply { inSampleSize = 4 }
            BitmapFactory.decodeFile(f.path, o)
        }
    } catch (e: Exception) { null }

    // ---------- 틱톡으로 보내기 ----------
    private fun tiktokPkg(): String? = listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill").firstOrNull {
        try { packageManager.getPackageInfo(it, 0); true } catch (e: Exception) { false }
    }

    private fun shareTikTok(id: String) {
        val p = Store.find(this, id) ?: return
        val f = File(p.mediaPath)
        if (!f.exists()) { toast("파일이 없어요"); return }
        val pkg = tiktokPkg()
        if (pkg == null) { toast("틱톡 앱이 설치돼 있지 않아요"); return }
        getSystemService(ClipboardManager::class.java)!!.setPrimaryClip(ClipData.newPlainText("caption", p.finalCaption()))
        val share = Intent(Intent.ACTION_SEND).apply {
            type = if (p.isVideo) "video/*" else "image/*"
            putExtra(Intent.EXTRA_STREAM, MediaProvider.uriFor(f))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setPackage(pkg)
        }
        try {
            startActivity(share)
            p.status["tt"] = "OK"; Store.upsert(this, p)
            tiktokHelp(p.ai)
        } catch (e: Exception) {
            toast("틱톡을 못 열었어요: " + e.message)
        }
    }

    /** 틱톡으로 넘어간 뒤 할 일 — 길게 남는 안내 */
    private fun tiktokHelp(ai: Boolean) {
        val msg = "틱톡에서 이렇게 하세요:\n1. '다음' 누르기\n2. 설명 칸을 길게 눌러 '붙여넣기'" +
            (if (ai) "\n   (AI 표시 '$AI_TAG'도 같이 들어가요)" else "") + "\n3. '게시'"
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        ui.postDelayed({ Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }, 3600)
    }

    // =====================================================================
    // 2. 예약 목록
    // =====================================================================
    private fun scheduleScreen() {
        title("예약 · 올린 기록", "예약된 게시물과 결과")
        val apps = Store.apps(this).associateBy { it.id }
        val list = Store.posts(this).sortedByDescending { it.time }
        if (list.isEmpty()) { body.addView(text("아직 게시물이 없어요. '새 게시물'에서 만들어 보세요.", 14f, muted)); return }
        val now = System.currentTimeMillis()
        list.forEach { p ->
            val c = card()
            val head = row()
            head.addView(text(apps[p.appId]?.name ?: "앱", 16f, ink, true), weight())
            head.addView(text(fmt.format(Date(p.time)), 12f, if (p.time > now && !p.isDone()) accent else muted))
            c.addView(head)
            c.addView(text(p.caption.lines().firstOrNull()?.take(60) ?: "", 13f, muted).also { top(it, 4) })
            p.targets().forEach { t ->
                val s = p.status[t] ?: ""
                val (msg, col) = when {
                    s == "OK" -> "✓ 완료" to green
                    s.isNotEmpty() -> "✗ $s" to red
                    t == "tt" && p.time <= now -> "알림 눌러서 올리기" to accent
                    else -> "대기 중" to muted
                }
                c.addView(text("${PostJob.name(t)}: $msg", 13f, col).also { top(it, 2) })
            }
            val r = row()
            if (p.tt && p.status["tt"] != "OK") { r.addView(button("틱톡 올리기", accent) { shareTikTok(p.id); show(2) }, weight()); r.addView(space(6)) }
            if (p.hasError()) {
                r.addView(button("다시 시도", violet) {
                    p.targets().forEach { if (p.status[it] != "OK") p.status[it] = "" }
                    Store.upsert(this, p); toast("다시 올리는 중…")
                    Thread { PostJob.run(applicationContext, p.id); ui.post { if (tab == 2) show(2) } }.start()
                }, weight()); r.addView(space(6))
            }
            if (!p.isDone()) {
                r.addView(button("수정", line) {
                    dId = p.id; dApp = p.appId; dMedia = p.mediaPath; dVideo = p.isVideo; dCaption = p.caption
                    dAi = p.ai; dIg = p.ig; dFb = p.fb; dTt = p.tt; dTime = maxOf(p.time, now + 10 * 60_000); dIdea = ""
                    PostJob.cancel(this, p.id); show(1)
                }, weight()); r.addView(space(6))
            }
            r.addView(button("삭제", line) {
                AlertDialog.Builder(this).setMessage("이 게시물을 앱에서 지울까요?\n(이미 SNS에 올라간 글은 지워지지 않아요)")
                    .setPositiveButton("지우기") { _, _ -> PostJob.cancel(this, p.id); Store.delete(this, p.id); show(2) }
                    .setNegativeButton("취소", null).show()
            }, weight())
            top(r, 10); c.addView(r)
        }
    }

    // =====================================================================
    // 3. 성과
    // =====================================================================
    private fun statsScreen() {
        title("성과", "팔로워 변화와 게시물 반응")
        if (!Meta.connected(this)) {
            notice("인스타·페이스북을 연결하면 팔로워와 좋아요를 볼 수 있어요.") { show(4) }
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(box)
        drawSnapshots(box)

        // 앱별 집계 (앱에서 올린 게시물 기준)
        val posts = Store.posts(this)
        val apps = Store.apps(this)
        label("앱별 올린 게시물")
        val c = card()
        apps.forEach { a ->
            val n = posts.count { it.appId == a.id && it.isDone() }
            c.addView(text("${a.name}: ${n}개", 14f, if (n > 0) ink else muted))
        }

        val media = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(media)
        body.addView(text("틱톡 조회수는 틱톡 앱 → 프로필 → ☰ → 크리에이터 도구 → 분석에서 확인하세요 (틱톡은 심사 전 성과 API를 못 써요).", 12f, muted).also { top(it, 12) })

        if (!Meta.connected(this)) return
        media.addView(text("불러오는 중…", 13f, muted))
        Thread {
            try {
                val igP = if (Meta.igReady(this)) Meta.igProfile(this) else null
                val fbP = Meta.fbPage(this)
                val igMedia = if (Meta.igReady(this)) Meta.igMedia(this) else null
                val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                Store.addSnapshot(this, Snapshot(day, igP?.optInt("followers_count", -1) ?: -1, fbP.optInt("followers_count", fbP.optInt("fan_count", -1))))
                ui.post {
                    if (tab != 3) return@post
                    box.removeAllViews(); drawSnapshots(box)
                    media.removeAllViews()
                    if (igMedia != null) {
                        // 앱에서 올린 인스타 게시물 → 어느 앱 홍보인지 표시
                        val byRemote = posts.filter { it.remoteIds["ig"] != null }.associateBy { it.remoteIds["ig"] }
                        val appName = apps.associateBy { it.id }
                        label("인스타 최근 게시물 반응")
                        val rows = (0 until igMedia.length()).map { igMedia.getJSONObject(it) }
                        val perApp = HashMap<String, Int>()
                        rows.forEach { o ->
                            val mine = byRemote[o.optString("id")]
                            val likes = o.optInt("like_count"); val com = o.optInt("comments_count")
                            if (mine != null) perApp[mine.appId] = (perApp[mine.appId] ?: 0) + likes + com
                            val cc = cardIn(media)
                            cc.addView(text((mine?.let { "[" + (appName[it.appId]?.name ?: "") + "] " } ?: "") +
                                o.optString("caption").lines().firstOrNull()?.take(50).orEmpty(), 13f, ink))
                            cc.addView(text("❤ $likes  💬 $com  · " + o.optString("timestamp").take(10), 12f, muted))
                            cc.setOnClickListener { o.optString("permalink").takeIf { it.isNotEmpty() }?.let { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } }
                        }
                        if (perApp.isNotEmpty()) {
                            val best = perApp.maxByOrNull { it.value }!!
                            media.addView(text("👉 반응이 가장 좋은 앱 홍보: ${appName[best.key]?.name} (좋아요+댓글 ${best.value})", 14f, accent, true).also { top(it, 8) }, 0)
                        }
                    }
                }
            } catch (e: Exception) {
                ui.post { if (tab == 3) { media.removeAllViews(); media.addView(text("불러오기 실패: " + e.message, 13f, red)) } }
            }
        }.start()
    }

    private fun drawSnapshots(box: LinearLayout) {
        val s = Store.snapshots(this)
        val c = cardIn(box)
        if (s.isEmpty()) { c.addView(text("팔로워 기록이 아직 없어요. 성과 탭을 열 때마다 하루 1번 기록돼요.", 13f, muted)); return }
        val last = s.last()
        fun diff(get: (Snapshot) -> Int, days: Int): String {
            val cur = get(last); if (cur < 0) return ""
            val target = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(System.currentTimeMillis() - days * 86_400_000L))
            val base = s.lastOrNull { it.day <= target && get(it) >= 0 } ?: s.firstOrNull { get(it) >= 0 } ?: return ""
            val d = cur - get(base)
            return "  (${base.day.substring(5)} 대비 ${if (d >= 0) "+" else ""}$d)"
        }
        c.addView(text("팔로워 (${last.day})", 13f, muted))
        if (last.ig >= 0) c.addView(text("인스타 ${last.ig}명" + diff({ it.ig }, 7), 20f, ink, true))
        if (last.fb >= 0) c.addView(text("페이스북 ${last.fb}명" + diff({ it.fb }, 7), 16f, ink))
    }

    // =====================================================================
    // 4. 설정
    // =====================================================================
    private fun settingsScreen() {
        title("설정", "한 번만 해 두면 돼요")

        label("인스타그램 · 페이스북")
        val mc = card()
        if (Meta.connected(this)) {
            mc.addView(text("✓ 연결됨: 페이지 '${Store.get(this, "pageName")}'", 14f, green))
            mc.addView(text(if (Meta.igReady(this)) "인스타 @${Store.get(this, "igName")}" else "⚠ 이 페이지에 연결된 인스타 비즈니스 계정이 없어요", 13f, if (Meta.igReady(this)) ink else red))
            mc.addView(button("연결 끊기", line) { Meta.disconnect(this); show(4) }.also { top(it, 8) })
        } else {
            mc.addView(text(
                "연결 방법 (PC에서 하면 편해요):\n" +
                "1) developers.facebook.com → 내 앱 → 앱 만들기 (비즈니스 유형)\n" +
                "2) 앱 설정 → 기본 설정에서 '앱 ID'와 '앱 시크릿 코드' 복사\n" +
                "3) 도구 → Graph API 탐색기 → 내 앱 선택 → 권한 추가:\n" +
                "   pages_show_list, pages_read_engagement, pages_manage_posts,\n" +
                "   instagram_basic, instagram_content_publish,\n" +
                "   instagram_manage_insights, business_management\n" +
                "4) 'Generate Access Token' → 나온 토큰 복사\n" +
                "5) 아래 칸에 붙여넣고 연결", 12f, muted))
            val id = input("앱 ID", Store.get(this, "metaAppId"), false)
            val sec = input("앱 시크릿 코드", Store.get(this, "metaSecret"), false).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
            val tk = input("액세스 토큰", "", false)
            mc.addView(id); mc.addView(sec); mc.addView(tk)
            mc.addView(button("연결하기", accent) {
                val a = id.text.toString().trim(); val s = sec.text.toString().trim(); val t = tk.text.toString().trim()
                if (a.isEmpty() || s.isEmpty() || t.isEmpty()) { toast("세 칸을 모두 채워 주세요"); return@button }
                Store.set(this, "metaAppId", a); Store.set(this, "metaSecret", s)
                toast("연결 중…")
                Thread {
                    try {
                        val pages = Meta.loadPages(a, s, t)
                        ui.post {
                            when {
                                pages.isEmpty() -> toast("관리하는 페이스북 페이지를 못 찾았어요. 권한을 다시 확인해 주세요.")
                                pages.size == 1 -> { Meta.savePage(this, pages[0]); show(4) }
                                else -> AlertDialog.Builder(this).setTitle("어느 페이지로 올릴까요?")
                                    .setItems(pages.map { it.name + if (it.igName.isNotEmpty()) " (@${it.igName})" else "" }.toTypedArray()) { _, i ->
                                        Meta.savePage(this, pages[i]); show(4)
                                    }.show()
                            }
                        }
                    } catch (e: Exception) { ui.post { toast("연결 실패: " + e.message) } }
                }.start()
            }.also { top(it, 8) })
        }

        label("AI 문구 (Google Gemini)")
        val gc = card()
        gc.addView(text("aistudio.google.com → Get API key → 키 만들기 → 복사해서 아래에 붙여넣기", 12f, muted))
        val key = input("Gemini API 키", Store.get(this, "geminiKey"), false).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val model = input("모델 (비워 두면 ${Ai.DEFAULT_MODEL})", Store.get(this, "geminiModel"), false)
        gc.addView(key); gc.addView(model)
        gc.addView(button("저장", accent) {
            Store.set(this, "geminiKey", key.text.toString().trim()); Store.set(this, "geminiModel", model.text.toString().trim())
            toast("저장했어요")
        }.also { top(it, 8) })

        label("꼭 알아 두세요")
        val lc = card()
        lc.addView(text(
            "• 이 앱은 주야 님 본인 계정 전용이에요. 앱 시크릿 코드가 핸드폰에 저장되므로 다른 사람에게 이 APK를 주거나 판매하지 마세요. 판매하려면 Meta 앱 심사·서버가 따로 필요해요.\n" +
            "• 인스타 API로는 하루 최대 약 25~100개까지만 올릴 수 있어요 (계정마다 다름).\n" +
            "• 예약 게시는 핸드폰이 켜져 있고 인터넷이 될 때 올라가요. 배터리 절약 모드에서 늦어질 수 있어요.\n" +
            "• 자동 팔로우·좋아요·팔로워 구매 기능은 약관 위반이라 넣지 않았어요.\n" +
            "• Gemini 무료 사용량을 넘으면 구글 요금이 생길 수 있어요.", 12f, muted))
    }

    // =====================================================================
    // 작은 화면 도우미
    // =====================================================================
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun round(color: Int, r: Int = 14) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); setLineSpacing(0f, 1.15f)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun title(t: String, sub: String) {
        body.addView(text(t, 26f, ink, true))
        body.addView(text(sub, 13f, muted).also { it.setPadding(0, 0, 0, dp(12)) })
    }

    private fun label(t: String) {
        body.addView(text(t, 14f, accent, true).also { it.setPadding(0, dp(16), 0, dp(6)) })
    }

    private fun notice(msg: String, onClick: () -> Unit) {
        val c = card()
        c.background = round(Color.parseColor("#3A2140"))
        c.addView(text(msg, 13f, ink))
        c.setOnClickListener { onClick() }
    }

    private fun card(): LinearLayout = cardIn(body)

    private fun cardIn(parent: LinearLayout): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(panel)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        parent.addView(c, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        return c
    }

    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    private fun weight() = LinearLayout.LayoutParams(0, -2, 1f)

    private fun space(w: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(w), 1) }

    private fun top(v: View, m: Int) {
        val lp = (v.layoutParams as? LinearLayout.LayoutParams) ?: LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = dp(m); v.layoutParams = lp
    }

    private fun button(label: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 14f; setTextColor(ink)
        background = round(color, 12)
        minHeight = dp(46); stateListAnimator = null
        setPadding(dp(10), 0, dp(10), 0)
        setOnClickListener { onClick() }
    }

    private fun input(hint: String, value: String, multi: Boolean) = EditText(this).apply {
        this.hint = hint; setText(value); textSize = 14f
        setTextColor(ink); setHintTextColor(muted)
        background = round(panel, 12)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        if (multi) { minLines = 6; gravity = Gravity.TOP; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES }
        else { isSingleLine = true }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
    }

    private fun dlgInput(box: LinearLayout, hint: String, value: String): EditText {
        val e = EditText(this).apply { this.hint = hint; setText(value) }
        box.addView(e); return e
    }

    private fun check(label: String, on: Boolean, enabled: Boolean) = CheckBox(this).apply {
        text = label; isChecked = on; isEnabled = enabled; textSize = 14f
        setTextColor(if (enabled) ink else muted)
        buttonTintList = android.content.res.ColorStateList.valueOf(accent)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
