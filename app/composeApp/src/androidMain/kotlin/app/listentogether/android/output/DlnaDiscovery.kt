package app.listentogether.android.output

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import app.listentogether.output.DlnaDevice
import app.listentogether.output.DlnaHttp
import app.listentogether.output.DlnaProtocol
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class DlnaDiscovery(private val context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    fun wifi(): Network? = connectivity.allNetworks.firstOrNull {
        connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    fun http(network: Network): DlnaHttp = DlnaHttp { network.openConnection(it) as HttpURLConnection }
    fun address(network: Network): InetAddress? = connectivity.getLinkProperties(network)?.linkAddresses
        ?.firstOrNull { it.address.address.size == 4 }?.address

    /** A bounded M-SEARCH scan; responses are unicast, so no permanent multicast receiver is needed. */
    suspend fun scan(): List<DlnaDevice> = withContext(Dispatchers.IO) {
        val network = wifi() ?: throw IllegalStateException("请先连接与音箱相同的 Wi-Fi")
        val address = address(network)
            ?: throw IllegalStateException("当前 Wi-Fi 没有可用的 IPv4 地址")
        val lock = context.applicationContext.getSystemService(WifiManager::class.java)
            .createMulticastLock("listen-together-discovery").apply { setReferenceCounted(false) }
        val locations = linkedSetOf<String>()
        lock.acquire()
        try {
            DatagramSocket(null).use { socket ->
                network.bindSocket(socket)
                socket.bind(InetSocketAddress(address, 0))
                socket.soTimeout = 500
                val bytes = DlnaProtocol.discoveryRequest()
                val packet = DatagramPacket(bytes, bytes.size, InetAddress.getByName("239.255.255.250"), 1900)
                repeat(2) { socket.send(packet) }
                val until = android.os.SystemClock.elapsedRealtime() + 4_000
                val buffer = ByteArray(16_384)
                while (android.os.SystemClock.elapsedRealtime() < until && locations.size < 48) {
                    coroutineContext.ensureActive()
                    try {
                        val received = DatagramPacket(buffer, buffer.size)
                        socket.receive(received)
                        DlnaProtocol.discoveryLocation(String(received.data, received.offset, received.length, Charsets.UTF_8))?.let(locations::add)
                    } catch (_: SocketTimeoutException) { }
                }
            }
            val client = http(network)
            locations.flatMap { location ->
                coroutineContext.ensureActive()
                runCatching { DlnaProtocol.devices(client.get(location), location) }.getOrElse { emptyList() }
            }.distinctBy { it.id }.sortedBy { it.name }
        } finally {
            if (lock.isHeld) lock.release()
        }
    }
}
