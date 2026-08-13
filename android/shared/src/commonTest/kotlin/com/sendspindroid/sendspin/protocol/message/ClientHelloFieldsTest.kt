package com.sendspindroid.sendspin.protocol.message

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The `client/hello` fields added for the encrypted dialect (item 1.8).
 *
 * `unpaired_access` is the load-bearing one. The spec permits `['playback']` on
 * a Sentinel-keyed session "only when the client has unpaired access enabled",
 * and aiosendspin's `_playback_capable` requires
 * `unpaired_access.enabled and trusted_unpaired`. A client that never advertises
 * the field leaves an unpaired server no choice but to send empty activities,
 * so it can never play audio before pairing - which is exactly the empty
 * `server/activate` observed while this field was missing.
 */
class ClientHelloFieldsTest {

    private val formats = listOf(MessageBuilder.FormatEntry("pcm", 48000, 2, 16))

    private fun hello(
        clientId: String? = null,
        trustLevel: String = MessageBuilder.TRUST_NONE,
        unpairedAccess: Boolean = true,
    ) = Json.parseToJsonElement(
        MessageBuilder.buildClientHello(
            clientId = clientId,
            deviceName = "Tablet",
            bufferCapacity = 1_680_000,
            manufacturer = "test",
            supportedFormats = formats,
            trustLevel = trustLevel,
            unpairedAccessEnabled = unpairedAccess,
        )
    ).jsonObject["payload"]!!.jsonObject

    @Test
    fun advertisesUnpairedAccessSoAnUnpairedServerCanGrantPlayback() {
        assertEquals(
            true,
            hello(unpairedAccess = true)["unpaired_access"]!!
                .jsonObject["enabled"]!!.jsonPrimitive.booleanOrNull,
        )
        assertEquals(
            false,
            hello(unpairedAccess = false)["unpaired_access"]!!
                .jsonObject["enabled"]!!.jsonPrimitive.booleanOrNull,
        )
    }

    @Test
    fun carriesTrustLevel() {
        assertEquals("none", hello()["trust_level"]!!.jsonPrimitive.content)
        assertEquals(
            "user",
            hello(trustLevel = MessageBuilder.TRUST_USER)["trust_level"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun offersThePairingPskMethodWhichEveryClientMustImplement() {
        val methods = hello()["supported_pair_methods"]!!.jsonArray
        assertEquals(1, methods.size)
        val only = methods[0].jsonObject
        assertEquals("pairing_psk", only["method"]!!.jsonPrimitive.content)
        // Informational hint. This client generates its own Pairing PSK and
        // shows the resulting token on screen, which is what "device" describes.
        assertEquals("device", only["locations"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun omitsClientIdAndVersionOnAnEncryptedSession() {
        // Both moved to client/init, and messaging.md forbids sending fields the
        // spec does not define for a message.
        val encrypted = hello(clientId = null)
        assertFalse(encrypted.containsKey("client_id"))
        assertFalse(encrypted.containsKey("version"))
    }

    @Test
    fun keepsClientIdAndVersionOnTheLegacyDialect() {
        val legacy = hello(clientId = "legacy-uuid")
        assertTrue(legacy.containsKey("client_id"))
        assertEquals(1, legacy["version"]!!.jsonPrimitive.content.toInt())
    }
}
