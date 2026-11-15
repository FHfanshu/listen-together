package app.listentogether.ui

import androidx.compose.runtime.Composable

/**
 * 剪贴板里的文字。App 回到前台（窗口拿到焦点）时读一次，只在剪贴板内容换了之后才真的去读，
 * 免得 Android 12 以后每次切回来都弹「已粘贴」提示。读不到返回 null。
 */
@Composable
expect fun rememberClipboardText(): String?

/** 朋友发来的服务器邀请，比如：来一起听歌！\n服务器地址：https://…\n邀请码：2JN87QP8 */
data class InviteClip(val server: String?, val invite: String?)

private val UrlRegex = Regex("""https?://[^\s，。,；;"'<>）)]+""")
// 邀请码去掉了 0/O/1/I，和服务器 inviteAlphabet 一致。
private val InviteAfterLabel = Regex("""邀请码\s*[:：]?\s*([A-HJ-NP-Z2-9]{8})(?![A-Za-z0-9])""", RegexOption.IGNORE_CASE)
private val BareInvite = Regex("""(?<![A-Za-z0-9])[A-HJ-NP-Z2-9]{8}(?![A-Za-z0-9])""", RegexOption.IGNORE_CASE)

fun parseInvite(text: String): InviteClip? {
    val url = UrlRegex.find(text)?.value?.trimEnd('/', '.')
        // B 站、YouTube 链接是点视频用的，不是服务器地址。
        ?.takeUnless { videoLinkSource(it) != null }
    val code = InviteAfterLabel.find(text)?.groupValues?.get(1)
        // 没写「邀请码」时，只有同时带了服务器地址才把 8 位码当邀请码，避免把别的东西误认。
        ?: if (url != null) BareInvite.findAll(text.replace(url, " ")).map { it.value }.firstOrNull { c -> c.any { it.isDigit() } && c.any { it.isLetter() } } else null
    if (url == null && code == null) return null
    return InviteClip(url, code?.uppercase())
}

/** 同一段剪贴板只自动填一次，用户清掉以后不会又被填回来。 */
internal object ClipMemory {
    private val used = mutableSetOf<String>()
    fun takeOnce(text: String): Boolean = used.add(text)
}
