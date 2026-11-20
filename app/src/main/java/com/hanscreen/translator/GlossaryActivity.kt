package com.hanscreen.translator

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.hanscreen.translator.core.dp
import com.hanscreen.translator.core.toast
import com.hanscreen.translator.databinding.ActivityGlossaryBinding
import com.hanscreen.translator.databinding.ItemGlossaryBinding
import com.hanscreen.translator.translate.Glossary

class GlossaryActivity : AppCompatActivity() {

    private lateinit var b: ActivityGlossaryBinding
    private val handler = Handler(Looper.getMainLooper())
    private var dirty = false

    private val saveRunnable = Runnable { persist() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityGlossaryBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnBack.setOnClickListener { finish() }
        b.btnAdd.setOnClickListener {
            addRow(Glossary.Entry("", ""), focus = true)
        }
        b.btnImport.setOnClickListener { showImportDialog() }
        b.btnReset.setOnClickListener { confirmReset() }

        reload()
    }

    override fun onPause() {
        super.onPause()
        if (dirty) {
            handler.removeCallbacks(saveRunnable)
            persist()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(saveRunnable)
        super.onDestroy()
    }

    // ---------------- 行操作 ----------------

    private fun reload() {
        b.llRows.removeAllViews()
        App.glossary.entries().forEach { addRow(it, focus = false) }
        updateCount()
    }

    private fun addRow(entry: Glossary.Entry, focus: Boolean) {
        val row = ItemGlossaryBinding.inflate(layoutInflater, b.llRows, false)
        row.etKo.setText(entry.ko)
        row.etZh.setText(entry.zh)

        row.etKo.doAfterTextChanged { markDirty() }
        row.etZh.doAfterTextChanged { markDirty() }
        row.tvDel.setOnClickListener {
            b.llRows.removeView(row.root)
            markDirty()
            handler.removeCallbacks(saveRunnable)
            persist()
        }

        b.llRows.addView(row.root)

        if (focus) {
            // 等新行完成测量和布局再要焦点，否则焦点会留在上一行；
            // requestFocus 会让外层 ScrollView 自动滚动到这一行。
            row.etKo.postDelayed({
                row.etKo.requestFocus()
                row.etKo.setSelection(row.etKo.text.length)
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(row.etKo, InputMethodManager.SHOW_IMPLICIT)
            }, 180)
        }
    }

    private fun markDirty() {
        dirty = true
        handler.removeCallbacks(saveRunnable)
        handler.postDelayed(saveRunnable, 400)
    }

    private fun persist() {
        val list = ArrayList<Glossary.Entry>()
        for (i in 0 until b.llRows.childCount) {
            val row = b.llRows.getChildAt(i)
            val ko = row.findViewById<EditText>(R.id.etKo).text.toString().trim()
            val zh = row.findViewById<EditText>(R.id.etZh).text.toString().trim()
            if (ko.isNotEmpty() && zh.isNotEmpty()) list.add(Glossary.Entry(ko, zh))
        }
        App.glossary.replaceAll(list)
        dirty = false
        updateCount()
    }

    private fun updateCount() {
        b.tvCount.text = "${App.glossary.size()} 条"
    }

    // ---------------- 批量导入 ----------------

    private fun showImportDialog() {
        val input = EditText(this).apply {
            hint = "每行一条，用 = 或 → 分隔\n例如：\n장바구니 = 购物车\n배송조회 → 物流查询\n\n以 # 开头的行会被忽略"
            minLines = 7
            gravity = Gravity.TOP
            setTextColor(Color.parseColor("#161B2E"))
            setHintTextColor(Color.parseColor("#B0B7C3"))
            textSize = 14f
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }

        AlertDialog.Builder(this)
            .setTitle("批量导入")
            .setView(input)
            .setPositiveButton("导入") { _, _ -> doImport(input.text.toString()) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doImport(raw: String) {
        val parsed = ArrayList<Glossary.Entry>()
        raw.lineSequence().forEach { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) return@forEach
            val sep = when {
                t.contains("=") -> "="
                t.contains("→") -> "→"
                t.contains("\t") -> "\t"
                else -> null
            } ?: return@forEach
            val parts = t.split(sep, limit = 2)
            if (parts.size != 2) return@forEach
            val ko = parts[0].trim()
            val zh = parts[1].trim()
            if (ko.isNotEmpty() && zh.isNotEmpty()) parsed.add(Glossary.Entry(ko, zh))
        }

        if (parsed.isEmpty()) {
            toast("没有解析到有效内容，格式：韩文=中文")
            return
        }

        // 与现有条目合并，同韩文以新导入的为准
        val merged = LinkedHashMap<String, String>()
        App.glossary.entries().forEach { merged[it.ko] = it.zh }
        parsed.forEach { merged[it.ko] = it.zh }
        App.glossary.replaceAll(merged.map { Glossary.Entry(it.key, it.value) })

        reload()
        toast("已导入 ${parsed.size} 条，共 ${App.glossary.size()} 条")
    }

    // ---------------- 恢复默认 ----------------

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("恢复默认术语表？")
            .setMessage("当前自定义的 ${App.glossary.size()} 条会被内置的 ${Glossary.DEFAULTS.size} 条替换，不能撤销。")
            .setPositiveButton("恢复") { _, _ ->
                App.glossary.restoreDefaults()
                reload()
                toast("已恢复默认")
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
