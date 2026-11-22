package com.unclema123.evilshao

/**
 * 「这一句先找谁要」。纯逻辑，不碰 Android，方便单测。
 *
 * 顺序固定是：**用户自己填的接口** → **免 Key 的公开接口**。
 * 用户填了 Key 就优先用他的；主接口挂了（限流、超时、404）自动往后轮，
 * 轮到的接口记住，下次先从它开始，别每次都从坏的那个撞一遍。
 *
 * 免 Key 列表默认两条：同一个地址换模型也算一条，一条挂了还能试下一条。
 */
object LlmProviders {

    class P(
        val label: String,
        val base: String,
        val model: String,
        val key: String,
        val keyless: Boolean
    ) {
        /** 去重、冷却、记「上次成功」都用这个 */
        val id: String get() = "$base|$model"
    }

    const val KEYLESS_BASE = "https://text.pollinations.ai/openai"
    const val KEYLESS_MODEL = "openai"

    /** 免 Key 的公开接口；挂了就换下一条 */
    val FREE = listOf(
        P("Pollinations · openai", KEYLESS_BASE, "openai", "", true),
        P("Pollinations · mistral", KEYLESS_BASE, "mistral", "", true)
    )

    /**
     * 排出这一轮的尝试顺序。
     *
     * @param user     用户当前设置（没填全就传 null）
     * @param lastGood 上一次成功的 id，排到最前面
     */
    fun plan(user: P?, lastGood: String? = null, free: List<P> = FREE): List<P> {
        val out = ArrayList<P>()
        if (user != null) out.add(user)
        for (f in free) if (out.none { it.id == f.id }) out.add(f)
        if (lastGood == null) return out
        val idx = out.indexOfFirst { it.id == lastGood }
        if (idx > 0) {
            val hit = out.removeAt(idx)
            out.add(0, hit)
        }
        return out
    }
}