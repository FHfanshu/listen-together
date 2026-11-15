package app.listentogether.net

import kotlin.random.Random

// iOS 还没接 Security 框架验签：supported = false 时 App 不会凭对方自称的身份发送已有 token。
actual object ServerCrypto {
    actual val supported: Boolean = false
    actual fun randomBytes(size: Int): ByteArray = Random.nextBytes(size)
    actual fun verify(publicKeyDer: ByteArray, message: ByteArray, signatureDer: ByteArray): Boolean = false
}
