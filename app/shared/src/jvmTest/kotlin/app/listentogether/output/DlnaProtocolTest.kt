package app.listentogether.output

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DlnaProtocolTest {
    @Test fun discoversNamespacedEmbeddedRendererAndResolvesUrls() {
        val devices = DlnaProtocol.devices("""
            <root xmlns="urn:schemas-upnp-org:device-1-0"><URLBase>http://192.168.1.8:1400/base/</URLBase>
            <device><deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
              <deviceList><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
              <friendlyName>客厅 &amp; 书房</friendlyName><UDN>uuid:room-speaker</UDN>
              <serviceList><service><serviceType>${DlnaProtocol.AV_TRANSPORT}2</serviceType>
              <controlURL>../control</controlURL><SCPDURL>/avtransport.xml</SCPDURL></service></serviceList>
              </device></deviceList></device></root>
        """.trimIndent(), "http://192.168.1.8/description.xml")
        assertEquals(1, devices.size)
        assertEquals("客厅 & 书房", devices.single().name)
        assertEquals("http://192.168.1.8:1400/control", devices.single().transport.controlUrl)
        assertEquals("http://192.168.1.8:1400/avtransport.xml", devices.single().transport.descriptionUrl)
    }

    @Test fun validatesDiscoveryResponsesAndNeverUsesNonHttpLocations() {
        assertEquals("http://192.168.1.5:8000/device.xml", DlnaProtocol.discoveryLocation("HTTP/1.1 200 OK\r\nLoCaTiOn: http://192.168.1.5:8000/device.xml\r\n\r\n"))
        assertNull(DlnaProtocol.discoveryLocation("HTTP/1.1 404 Not Found\r\nLOCATION: http://192.168.1.5/x"))
        assertNull(DlnaProtocol.discoveryLocation("HTTP/1.1 200 OK\r\nLOCATION: file:///etc/passwd"))
        assertTrue(DlnaProtocol.discoveryRequest().decodeToString().endsWith("\r\n\r\n"))
    }

    @Test fun xmlRoundTripPreservesUrlsAndUnicodeWithoutInjection() {
        val url = "http://192.168.1.8:2000/audio/random?x=1&y=2"
        val metadata = DlnaProtocol.metadata(url, DlnaTrack("<歌名> & 测试", "歌手", 183000), "audio/mpeg")
        val envelope = DlnaProtocol.envelope("${DlnaProtocol.AV_TRANSPORT}1", "SetAVTransportURI", mapOf("CurrentURI" to url, "CurrentURIMetaData" to metadata))
        val parsed = DlnaProtocol.response(envelope)
        assertEquals(url, parsed["CurrentURI"])
        assertEquals(metadata, parsed["CurrentURIMetaData"])
        assertTrue(metadata.contains("&lt;歌名&gt; &amp; 测试"))
        assertTrue(metadata.contains("duration=\"00:03:03\""))
    }

    @Test fun rejectsExternalEntitiesAndReportsSoapFaults() {
        assertFailsWith<IllegalArgumentException> {
            DlnaProtocol.devices("<!DOCTYPE root [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><root>&x;</root>", "http://192.168.1.8/x")
        }
        val error = assertFailsWith<DlnaException> {
            DlnaProtocol.response("""<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault>
              <detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>710</errorCode>
              <errorDescription>Seek mode not supported</errorDescription></UPnPError></detail>
              </s:Fault></s:Body></s:Envelope>""")
        }
        assertEquals("710", error.code)
    }

    @Test fun parsesCoarseAndFractionalPositionsButRejectsUnknownValues() {
        assertEquals(3_723_250L, DlnaProtocol.position("01:02:03.250"))
        assertEquals("01:02:03", DlnaProtocol.time(3_723_999))
        assertNull(DlnaProtocol.position("NOT_IMPLEMENTED"))
        assertNull(DlnaProtocol.position("00:60:00"))
        assertNull(DlnaProtocol.position("00:00:NaN"))
    }
}
