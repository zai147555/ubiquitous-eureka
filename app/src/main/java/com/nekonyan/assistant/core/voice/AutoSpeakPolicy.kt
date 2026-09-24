package com.nekonyan.assistant.core.voice

/**
 * 「自动朗读」该不该念这一条 —— 判定与界面解耦，便于测试。
 *
 * 这三条规则都是用户能立刻感知、却很容易在重构中悄悄写坏的：
 *   ① **首次只记基准**：进 App 时最后一条 AI 回复是历史，不能念
 *      （否则一启动就把上次的回复念一遍，非常像 bug）；
 *   ② **关闭时也要推进基准**：关着自动朗读时收到的回复，等用户打开开关后
 *      **不应该补念**（补念同样像 bug）；
 *   ③ **同一条只念一次**：Room 的 Flow 会重复发同一份列表，不能重复朗读。
 */
sealed interface AutoSpeakDecision {
    /** 念这条 */
    data class Speak(val id: String, val text: String) : AutoSpeakDecision

    /** 不念，但要把 id 记为已处理（建立基准 / 关闭状态下的推进） */
    data class Remember(val id: String) : AutoSpeakDecision

    /** 什么都不做（同一条已处理过） */
    data object Ignore : AutoSpeakDecision
}

object AutoSpeakPolicy {

    /**
     * @param baselineReady 是否已经建立过基准（首次为 false）
     * @param seenId 上一条已处理的 AI 消息 id
     * @param newId 当前最后一条 AI 消息 id
     * @param newText 当前最后一条 AI 消息正文
     * @param enabled 自动朗读开关
     */
    fun decide(
        baselineReady: Boolean,
        seenId: String?,
        newId: String,
        newText: String,
        enabled: Boolean
    ): AutoSpeakDecision {
        if (!baselineReady) return AutoSpeakDecision.Remember(newId)
        if (newId == seenId) return AutoSpeakDecision.Ignore
        if (newText.isBlank()) return AutoSpeakDecision.Remember(newId)
        if (!enabled) return AutoSpeakDecision.Remember(newId)
        return AutoSpeakDecision.Speak(newId, newText)
    }
}
