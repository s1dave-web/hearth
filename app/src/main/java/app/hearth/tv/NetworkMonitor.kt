package app.hearth.tv

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** How the TV is connected right now, for the icon in the header. */
data class NetState(val ethernet: Boolean, val connected: Boolean)

class NetworkMonitor(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _state = MutableStateFlow(read(cm.activeNetwork))
    val state: StateFlow<NetState> = _state

    init {
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { _state.value = from(caps) }
            override fun onLost(network: Network) { _state.value = _state.value.copy(connected = false) }
        })
    }

    private fun read(n: Network?): NetState = n?.let { cm.getNetworkCapabilities(it) }?.let(::from) ?: NetState(ethernet = false, connected = false)

    private fun from(caps: NetworkCapabilities) = NetState(
        ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
        connected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
    )
}
