package cn.coldlake.stellar.opensource.casting

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.system.ErrnoException
import android.system.OsConstants
import java.io.IOException
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Socket

internal class CastVpnBlockedException(cause: IOException) : IOException("VPN denied LAN network binding", cause)

internal data class CastNetwork(val network: Network, val address: Inet4Address, val interfaceName: String, val prefixLength: Int) {
    fun matches(properties: LinkProperties) = properties.interfaceName == interfaceName &&
        properties.linkAddresses.any { it.address == address && it.prefixLength == prefixLength }

    fun allowsBinding(context: Context): Boolean = try { requireAccess(context); true }
        catch (_: CastVpnBlockedException) { false }

    /** No packets are sent. Check both discovery and control access before pausing local playback. */
    fun requireAccess(context: Context) {
        try {
            DatagramSocket(null).use { network.bindSocket(it) }
            Socket().use { network.bindSocket(it) }
        } catch (e: IOException) {
            // Android bindSocket may flatten errno into SocketException's message.
            val denied = generateSequence<Throwable>(e) { it.cause }.any {
                (it is ErrnoException && it.errno in setOf(OsConstants.EPERM, OsConstants.EACCES)) ||
                    it.message.orEmpty().let { text -> text.contains("EPERM") || text.contains("EACCES") }
            }
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val vpn = manager.allNetworks.any { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
            if (denied && vpn) throw CastVpnBlockedException(e)
            throw e
        }
    }

    companion object {
        fun find(context: Context): CastNetwork? {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            return (listOfNotNull(manager.activeNetwork) + manager.allNetworks).distinct().firstNotNullOfOrNull { network ->
                val caps = manager.getNetworkCapabilities(network) ?: return@firstNotNullOfOrNull null
                // A VPN can advertise WIFI as well as VPN. Internet validation is not required for a LAN.
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return@firstNotNullOfOrNull null
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return@firstNotNullOfOrNull null
                val properties = manager.getLinkProperties(network) ?: return@firstNotNullOfOrNull null
                val link = properties.linkAddresses.firstOrNull {
                    it.address is Inet4Address && !it.address.isLoopbackAddress && !it.address.isLinkLocalAddress
                } ?: return@firstNotNullOfOrNull null
                CastNetwork(network, link.address as Inet4Address, properties.interfaceName ?: return@firstNotNullOfOrNull null, link.prefixLength)
            }
        }
    }
}

/** Only the actual on-link subnet, at most 1024 addresses. Never expand a large LAN or scan TCP ports. */
internal fun castSubnetPeers(address: Inet4Address, prefixLength: Int): Sequence<Inet4Address> {
    if (prefixLength !in 22..31) return emptySequence()
    val host = address.address.fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 255) }
    val size = 1L shl (32 - prefixLength)
    val base = host and (0xffffffffL xor (size - 1))
    val range = if (prefixLength == 31) base until base + size else base + 1 until base + size - 1
    return range.asSequence().filter { it != host }.map { value ->
        Inet4Address.getByAddress(ByteArray(4) { index -> (value shr (24 - index * 8)).toByte() }) as Inet4Address
    }
}

internal fun castNetworkFailure(error: Exception, fallback: CastFailure): CastFailure =
    if (generateSequence<Throwable>(error) { it.cause }.any { it is CastVpnBlockedException }) CastFailure.VPN_BLOCKED else fallback
