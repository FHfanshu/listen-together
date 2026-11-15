package app.listentogether.net

import java.security.KeyFactory
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

actual object ServerCrypto {
    private val random = SecureRandom()

    actual val supported: Boolean = true

    actual fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    actual fun verify(publicKeyDer: ByteArray, message: ByteArray, signatureDer: ByteArray): Boolean = runCatching {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKeyDer))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(message)
            verify(signatureDer)
        }
    }.getOrDefault(false)
}
