package com.example.phonecontrol

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.max

class MainActivity : Activity() {

    private var dadb: Dadb? = null
    @Volatile private var running = false
    private val cmdExec = Executors.newSingleThreadExecutor()

    private lateinit var ipEdit: EditText
    private lateinit var portEdit: EditText
    private lateinit var textEdit: EditText
    private lateinit var status: TextView
    private lateinit var screen: ImageView

    private var downX = 0f
    private var downY = 0f
    private var downT = 0L

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun btn(t: String, f: () -> Unit) = Button(this).apply {
        text = t
        textSize = 12f
        isAllCaps = false
        setOnClickListener { f() }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun row(vararg v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        v.forEach { addView(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("p", MODE_PRIVATE)

        ipEdit = EditText(this).apply {
            hint = "IP  مثال 192.168.1.20"
            setText(prefs.getString("ip", ""))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f)
        }
        portEdit = EditText(this).apply {
            setText(prefs.getString("port", "5555"))
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f)
        }
        status = TextView(this).apply { text = "غير متصل" }

        screen = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFF000000.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setOnTouchListener { _, ev -> onScreenTouch(ev); true }
        }

        textEdit = EditText(this).apply {
            hint = "نص (إنجليزي/أرقام)"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(28), dp(6), dp(6))
            addView(row(ipEdit, portEdit, btn("اتصال") {
                prefs.edit()
                    .putString("ip", ipEdit.text.toString())
                    .putString("port", portEdit.text.toString()).apply()
                connect()
            }))
            addView(row(
                btn("استيراد مفتاح ADB") { pickKeys() },
                btn("قطع") { stop(); setStatus("غير متصل") }
            ))
            addView(status)
            addView(screen)
            addView(row(
                btn("◀") { key(4) },
                btn("●") { key(3) },
                btn("▣") { key(187) },
                btn("⏻") { key(26) },
                btn("🔊+") { key(24) },
                btn("🔉-") { key(25) }
            ))
            addView(row(
                textEdit,
                btn("إرسال") { sendText() },
                btn("⌫") { key(67) },
                btn("↵") { key(66) }
            ))
            addView(btn("فتح القفل (سحب للأعلى)") { unlock() }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            })
        }
        setContentView(root)
    }

    // ---------- الاتصال ----------
    private fun loadKeys(): AdbKeyPair {
        val priv = File(filesDir, "adbkey")
        val pub = File(filesDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        return AdbKeyPair.read(priv, pub)
    }

    private fun connect() {
        val host = ipEdit.text.toString().trim()
        val port = portEdit.text.toString().toIntOrNull() ?: 5555
        if (host.isEmpty()) {
            Toast.makeText(this, "اكتب IP الموبايل", Toast.LENGTH_SHORT).show()
            return
        }
        stop()
        setStatus("جارِ الاتصال... (إذا ظهرت نافذة السماح بالموبايل المكسور اضغط موافق)")
        Thread {
            try {
                val d = Dadb.create(host, port, loadKeys())
                val model = d.shell("getprop ro.product.model").output.trim()
                dadb = d
                running = true
                setStatus("متصل: $model")
                captureLoop(d)
            } catch (e: Exception) {
                setStatus("فشل الاتصال: ${e.message}")
            }
        }.start()
    }

    private fun stop() {
        running = false
        try { dadb?.close() } catch (_: Exception) {}
        dadb = null
    }

    private fun captureLoop(d: Dadb) {
        while (running && dadb === d) {
            try {
                val bytes = d.open("exec:screencap -p").use { it.source.readByteArray() }
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) runOnUiThread { screen.setImageBitmap(bmp) }
                else Thread.sleep(500)
            } catch (e: Exception) {
                setStatus("انقطع الاتصال: ${e.message}")
                running = false
            }
        }
    }

    // ---------- استيراد مفتاح ADB ----------
    private fun pickKeys() {
        Toast.makeText(
            this, "اختر الملفين adbkey و adbkey.pub معاً", Toast.LENGTH_LONG
        ).show()
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(i, 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 1 || resultCode != RESULT_OK || data == null) return
        val uris = mutableListOf<android.net.Uri>()
        data.clipData?.let { c -> for (k in 0 until c.itemCount) uris.add(c.getItemAt(k).uri) }
        data.data?.let { uris.add(it) }
        var n = 0
        for (u in uris) {
            var name = ""
            contentResolver.query(u, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = c.getString(idx) ?: ""
                }
            }
            val target = if (name.endsWith(".pub")) "adbkey.pub" else "adbkey"
            contentResolver.openInputStream(u)?.use { input ->
                File(filesDir, target).outputStream().use { input.copyTo(it) }
                n++
            }
        }
        setStatus("تم استيراد $n ملف. اضغط اتصال.")
    }

    // ---------- التحكم ----------
    private fun setStatus(s: String) = runOnUiThread { status.text = s }

    private fun sh(cmd: String) {
        val d = dadb ?: return
        cmdExec.execute {
            try { d.shell(cmd) } catch (e: Exception) { setStatus("خطأ: ${e.message}") }
        }
    }

    private fun key(code: Int) = sh("input keyevent $code")

    private fun sendText() {
        val t = textEdit.text.toString()
        if (t.isEmpty()) return
        val safe = t.replace("'", "").replace(" ", "%s")
        sh("input text '$safe'")
        textEdit.setText("")
    }

    private fun unlock() {
        val w = screen.drawable?.intrinsicWidth ?: 1080
        val h = screen.drawable?.intrinsicHeight ?: 2400
        sh("input keyevent 224")
        sh("sleep 0.5")
        sh("input swipe ${w / 2} ${(h * 0.85).toInt()} ${w / 2} ${(h * 0.25).toInt()} 300")
    }

    private fun toDev(x: Float, y: Float): Pair<Int, Int>? {
        val dr = screen.drawable ?: return null
        val m = FloatArray(9)
        screen.imageMatrix.getValues(m)
        val s = m[Matrix.MSCALE_X]
        if (s == 0f) return null
        val px = ((x - m[Matrix.MTRANS_X]) / s).toInt()
        val py = ((y - m[Matrix.MTRANS_Y]) / s).toInt()
        if (px < 0 || py < 0 || px >= dr.intrinsicWidth || py >= dr.intrinsicHeight) return null
        return px to py
    }

    private fun onScreenTouch(ev: MotionEvent) {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; downT = ev.eventTime
            }
            MotionEvent.ACTION_UP -> {
                val a = toDev(downX, downY)
                val b = toDev(ev.x, ev.y)
                if (a == null || b == null) return
                val dur = ev.eventTime - downT
                val moved = hypot(ev.x - downX, ev.y - downY) > dp(10)
                if (!moved) {
                    if (dur > 600) sh("input swipe ${a.first} ${a.second} ${a.first} ${a.second} $dur")
                    else sh("input tap ${a.first} ${a.second}")
                } else {
                    sh("input swipe ${a.first} ${a.second} ${b.first} ${b.second} ${max(150L, dur)}")
                }
            }
        }
    }

    override fun onDestroy() {
        stop()
        super.onDestroy()
    }
}
