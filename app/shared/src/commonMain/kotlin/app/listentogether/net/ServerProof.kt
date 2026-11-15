package app.listentogether.net

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** 与服务端 identity.ProofContext 一致：拼在随机数前面一起签名。 */
internal const val PROOF_CONTEXT = "listen-together server proof v1\n"

/** 平台加密能力：安全随机数和 ECDSA P-256 验签。不支持的平台（目前是 iOS）一律当作无法确认身份。 */
expect object ServerCrypto {
    val supported: Boolean
    fun randomBytes(size: Int): ByteArray
    /** publicKeyDer 是 SubjectPublicKeyInfo，signatureDer 是 ASN.1 DER 格式，摘要 SHA-256。 */
    fun verify(publicKeyDer: ByteArray, message: ByteArray, signatureDer: ByteArray): Boolean
}

/** 服务器身份核对的结果。 */
sealed interface ServerIdentity {
    /** 签名核对通过：这个地址上确实是持有该公钥的服务器。 */
    data class Verified(val serverId: String, val publicKey: String) : ServerIdentity
    /** 老版本服务器没有 /info，无从核对。 */
    data object Legacy : ServerIdentity
    /** 本平台不能验签，只拿到了对方自称的身份，不能据此信任。 */
    data class Unverified(val serverId: String, val publicKey: String) : ServerIdentity
}

class ServerProofException(message: String) : Exception(message)

/** 让 [api] 所在地址上的服务器证明自己的身份。网络错误照常抛出；签名不对抛 [ServerProofException]。 */
@OptIn(ExperimentalEncodingApi::class)
suspend fun proveServer(api: ServerApi): ServerIdentity {
    val nonce = ServerCrypto.randomBytes(32)
    val info = api.info(Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(nonce)) ?: return ServerIdentity.Legacy
    if (!ServerCrypto.supported) return ServerIdentity.Unverified(info.serverId, info.publicKey)
    val ok = runCatching {
        ServerCrypto.verify(
            Base64.decode(info.publicKey),
            PROOF_CONTEXT.encodeToByteArray() + nonce,
            Base64.decode(info.proof ?: return@runCatching false),
        )
    }.getOrDefault(false)
    if (!ok) throw ServerProofException("这个地址上的服务器没能证明自己的身份，为了安全没有继续")
    return ServerIdentity.Verified(info.serverId, info.publicKey)
}
