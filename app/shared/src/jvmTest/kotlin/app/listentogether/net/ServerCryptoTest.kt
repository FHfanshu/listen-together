package app.listentogether.net

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 用 Go 服务端 identity.Prove 生成的签名，确认两边的签名格式和拼接方式一致。 */
@OptIn(ExperimentalEncodingApi::class)
class ServerCryptoTest {
    private val pub = Base64.decode("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAESFgQz//upfdti7lGy1d+hfwbnqB4z3t29l6RcCd7U5UEKxHzolpfu433vIwGu+Zst9wrhPHO2/NDyM8HgmLzhw==")
    private val sig = Base64.decode("MEUCICe5KxyiMrFc///Jt8ZQX3vVqrUO+ahB/IgaQkENfrnOAiEAlnJT7lb0DqUS8to1FEFE3CuM3uHskXe89sg/HVncTGs=")
    private val nonce = "fixture nonce for kotlin test 32".encodeToByteArray()

    @Test
    fun verifiesGoSignature() {
        assertTrue(ServerCrypto.verify(pub, PROOF_CONTEXT.encodeToByteArray() + nonce, sig))
    }

    @Test
    fun rejectsOtherNonceOrGarbage() {
        assertFalse(ServerCrypto.verify(pub, PROOF_CONTEXT.encodeToByteArray() + "another nonce".encodeToByteArray(), sig))
        assertFalse(ServerCrypto.verify(pub, nonce, sig)) // 少了上下文前缀
        assertFalse(ServerCrypto.verify(byteArrayOf(1, 2, 3), nonce, sig))
    }
}
