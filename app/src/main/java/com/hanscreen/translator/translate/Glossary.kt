package com.hanscreen.translator.translate

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 术语表：用户指定的「韩文 → 中文」固定译法，优先级高于机器翻译。
 *
 * 两个作用点：
 *  1. 整段完全匹配 → 直接返回译文，不发请求（按钮、菜单标签这种最常见）
 *  2. 段内包含术语 → 用 {n} 占位符保护后再送翻译，译文回来再还原
 *
 * 每次修改都会让 revision 自增，缓存键带上它，保证改完术语立刻生效。
 */
class Glossary(context: Context) {

    data class Entry(val ko: String, val zh: String)

    data class Prepared(val masked: String, val applied: List<Entry>)

    private val sp = context.getSharedPreferences("glossary", Context.MODE_PRIVATE)
    private val items = ArrayList<Entry>()
    private val exactIndex = HashMap<String, String>()

    var revision: Int = 1
        private set

    init {
        load()
    }

    @Synchronized
    fun entries(): List<Entry> = items.toList()

    @Synchronized
    fun size(): Int = items.size

    /** 整段精确命中（忽略首尾空白、连续空格与句尾标点） */
    @Synchronized
    fun exact(text: String): String? = exactIndex[normalize(text)]

    /** 把段内出现的术语替换成占位符 */
    @Synchronized
    fun prepare(text: String): Prepared {
        if (items.isEmpty() || text.isBlank()) return Prepared(text, emptyList())

        // 长词优先：避免「장바구니」抢先匹配掉「장바구니에 담기」这类更长的词条
        val sorted = items.sortedByDescending { it.ko.length }
        var masked = text
        val applied = ArrayList<Entry>()

        for (e in sorted) {
            if (e.ko.isBlank() || e.zh.isBlank()) continue
            val re = termPattern(e.ko)
            if (!re.containsMatchIn(masked)) continue
            applied.add(e)
            masked = re.replace(masked, "{${applied.size}}")
        }
        return Prepared(masked, applied)
    }

    /** 还原占位符；顺手清掉引擎没吃掉的残渣 */
    @Synchronized
    fun restore(text: String, applied: List<Entry>): String {
        if (applied.isEmpty()) return text
        var out = text
        applied.forEachIndexed { i, e ->
            out = out.replace("{${i + 1}}", e.zh)
        }
        out = out.replace(Regex("\\{\\s*(\\d+)\\s*\\}")) { m ->
            val idx = m.groupValues[1].toIntOrNull() ?: 0
            applied.getOrNull(idx - 1)?.zh ?: ""
        }
        return out
    }

    @Synchronized
    fun replaceAll(list: List<Entry>) {
        items.clear()
        items.addAll(list.map { Entry(it.ko.trim(), it.zh.trim()) }
            .filter { it.ko.isNotBlank() && it.zh.isNotBlank() })
        rebuildIndex()
        persist(bump = true)
    }

    @Synchronized
    fun add(ko: String, zh: String) {
        val k = ko.trim()
        val v = zh.trim()
        if (k.isEmpty() || v.isEmpty()) return
        val idx = items.indexOfFirst { normalize(it.ko) == normalize(k) }
        if (idx >= 0) items[idx] = Entry(k, v) else items.add(Entry(k, v))
        rebuildIndex()
        persist(bump = true)
    }

    @Synchronized
    fun removeAt(index: Int) {
        if (index !in items.indices) return
        items.removeAt(index)
        rebuildIndex()
        persist(bump = true)
    }

    @Synchronized
    fun restoreDefaults() {
        items.clear()
        items.addAll(DEFAULTS)
        rebuildIndex()
        persist(bump = true)
    }

    // ---------------- 内部 ----------------

    /**
     * 精确匹配用的归一化：去掉所有空白再比对，
     * 这样「배송조회」和「배송 조회」会被视为同一条。
     */
    private fun normalize(s: String): String =
        s.filterNot { it.isWhitespace() }
            .trimEnd('.', ',', '!', '?', '~', '·', ':', ';', '。', '，', '！', '？')

    /**
     * 韩文分词不稳定，同一个词在界面上可能写成「배송조회」也可能写成「배송 조회」，
     * 所以词条内部允许任意数量空白。
     */
    private fun termPattern(term: String): Regex {
        val parts = term.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return Regex("(?!)")
        return Regex(parts.joinToString("\\s*") { Regex.escape(it) })
    }

    private fun rebuildIndex() {
        exactIndex.clear()
        items.forEach { exactIndex[normalize(it.ko)] = it.zh }
    }

    private fun persist(bump: Boolean) {
        if (bump) revision += 1
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().put("ko", it.ko).put("zh", it.zh))
        }
        sp.edit()
            .putString("data", arr.toString())
            .putInt("revision", revision)
            .apply()
    }

    private fun load() {
        val raw = sp.getString("data", null)
        revision = sp.getInt("revision", 1)
        if (raw == null) {
            items.addAll(DEFAULTS)
            rebuildIndex()
            persist(bump = false)
            return
        }
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val ko = o.optString("ko")
                val zh = o.optString("zh")
                if (ko.isNotBlank() && zh.isNotBlank()) items.add(Entry(ko, zh))
            }
        }
        rebuildIndex()
    }

    companion object {
        /** 韩文 App 里出现频率最高的界面/电商词，装完就能用 */
        val DEFAULTS: List<Entry> = listOf(
            Entry("장바구니", "购物车"),
            Entry("구매하기", "立即购买"),
            Entry("바로구매", "立即购买"),
            Entry("결제하기", "去结算"),
            Entry("결제수단", "支付方式"),
            Entry("무료배송", "免运费"),
            Entry("배송조회", "物流查询"),
            Entry("배송중", "配送中"),
            Entry("배송완료", "已送达"),
            Entry("주문조회", "订单查询"),
            Entry("주문내역", "订单记录"),
            Entry("취소하기", "取消订单"),
            Entry("반품", "退货"),
            Entry("교환", "换货"),
            Entry("쿠폰", "优惠券"),
            Entry("포인트", "积分"),
            Entry("할인", "折扣"),
            Entry("품절", "售罄"),
            Entry("재고", "库存"),
            Entry("옵션", "选项"),
            Entry("수량", "数量"),
            Entry("리뷰", "评价"),
            Entry("찜", "收藏"),
            Entry("로그인", "登录"),
            Entry("로그아웃", "退出登录"),
            Entry("회원가입", "注册"),
            Entry("아이디", "账号"),
            Entry("비밀번호", "密码"),
            Entry("고객센터", "客服中心"),
            Entry("공지사항", "公告"),
            Entry("이벤트", "活动"),
            Entry("설정", "设置"),
            Entry("알림", "通知"),
            Entry("검색", "搜索"),
            Entry("더보기", "更多"),
            Entry("확인", "确认"),
            Entry("취소", "取消"),
            Entry("저장", "保存"),
            Entry("삭제", "删除"),
            Entry("수정", "修改"),
            Entry("닫기", "关闭"),
            Entry("다음", "下一步"),
            Entry("이전", "上一步"),
            Entry("완료", "完成"),
            Entry("뒤로", "返回")
        )
    }
}
