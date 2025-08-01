package com.v2ray.ang.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.StrictMode
import android.util.Log
import androidx.annotation.RequiresApi
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.LOOPBACK
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.MyContextWrapper
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.lang.ref.SoftReference

class V2RayVpnService : VpnService(), ServiceControl {
    companion object {
        private const val VPN_MTU = 1500
        private const val TUN2SOCKS = "libtun2socks.so"
    }

    private lateinit var mInterface: ParcelFileDescriptor
    private var isRunning = false
    private lateinit var process: Process

    /**destroy
     * Unfortunately registerDefaultNetworkCallback is going to return our VPN interface: https://android.googlesource.com/platform/frameworks/base/+/dda156ab0c5d66ad82bdcf76cda07cbc0a9c8a2e
     *
     * This makes doing a requestNetwork with REQUEST necessary so that we don't get ALL possible networks that
     * satisfies default network capabilities but only THE default network. Unfortunately we need to have
     * android.permission.CHANGE_NETWORK_STATE to be able to call requestNetwork.
     *
     * Source: https://android.googlesource.com/platform/frameworks/base/+/2df4c7d/services/core/java/com/android/server/ConnectivityService.java#887
     */
    @delegate:RequiresApi(Build.VERSION_CODES.P)
    private val defaultNetworkRequest by lazy {
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
    }

    private val connectivity by lazy { getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager }

    @delegate:RequiresApi(Build.VERSION_CODES.P)
    private val defaultNetworkCallback by lazy {
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                setUnderlyingNetworks(arrayOf(network))
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                // it's a good idea to refresh capabilities
                setUnderlyingNetworks(arrayOf(network))
            }

            override fun onLost(network: Network) {
                setUnderlyingNetworks(null)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)
        V2RayServiceManager.serviceControl = SoftReference(this)
    }

    override fun onRevoke() {
        stopV2Ray()
    }

//    override fun onLowMemory() {
//        stopV2Ray()
//        super.onLowMemory()
//    }

    override fun onDestroy() {
        super.onDestroy()
        NotificationService.cancelNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (V2RayServiceManager.startCoreLoop()) {
            startService()
        }
        return START_STICKY
        //return super.onStartCommand(intent, flags, startId)
    }

    override fun getService(): Service {
        return this
    }

    override fun startService() {
        setup()
    }

    override fun stopService() {
        stopV2Ray(true)
    }

    override fun vpnProtect(socket: Int): Boolean {
        return protect(socket)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let {
            MyContextWrapper.wrap(newBase, SettingsManager.getLocale())
        }
        super.attachBaseContext(context)
    }

    /**
     * Sets up the VPN service.
     * Prepares the VPN and configures it if preparation is successful.
     */
    private fun setup() {
        val prepare = prepare(this)
        if (prepare != null) {
            return
        }

        if (setupVpnService() != true) {
            return
        }

        runTun2socks()
    }

    /**
     * Configures the VPN service.
     * @return True if the VPN service was configured successfully, false otherwise.
     */
    private fun setupVpnService(): Boolean {
        // If the old interface has exactly the same parameters, use it!
        // Configure a builder while parsing the parameters.
        val builder = Builder()
        val vpnConfig = SettingsManager.getCurrentVpnInterfaceAddressConfig()
        //val enableLocalDns = defaultDPreference.getPrefBoolean(AppConfig.PREF_LOCAL_DNS_ENABLED, false)

        builder.setMtu(VPN_MTU)
        builder.addAddress(vpnConfig.ipv4Client, 30)
        //builder.addDnsServer(PRIVATE_VLAN4_ROUTER)
        val bypassLan = SettingsManager.routingRulesetsBypassLan()
        if (bypassLan) {
            AppConfig.ROUTED_IP_LIST.forEach {
                val addr = it.split('/')
                builder.addRoute(addr[0], addr[1].toInt())
            }
        } else {
            builder.addRoute("0.0.0.0", 0)
        }

        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PREFER_IPV6) == true) {
            builder.addAddress(vpnConfig.ipv6Client, 126)
            if (bypassLan) {
                builder.addRoute("2000::", 3) //currently only 1/8 of total ipV6 is in use
                builder.addRoute("fc00::", 18) //Xray-core default FakeIPv6 Pool
            } else {
                builder.addRoute("::", 0)
            }
        }

//        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_LOCAL_DNS_ENABLED) == true) {
//            builder.addDnsServer(PRIVATE_VLAN4_ROUTER)
//        } else {
        SettingsManager.getVpnDnsServers()
            .forEach {
                if (Utils.isPureIpAddress(it)) {
                    builder.addDnsServer(it)
                }
            }
//        }

        builder.setSession(V2RayServiceManager.getRunningServerName())

        configurePerAppProxy(builder)

        // Close the old interface since the parameters have been changed.
        try {
            mInterface.close()
        } catch (ignored: Exception) {
            // ignored
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                connectivity.requestNetwork(defaultNetworkRequest, defaultNetworkCallback)
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to request default network", e)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_APPEND_HTTP_PROXY)) {
                builder.setHttpProxy(ProxyInfo.buildDirectProxy(LOOPBACK, SettingsManager.getHttpPort()))
            }
        }

        // Create a new interface using the builder and save the parameters.
        try {
            mInterface = builder.establish()!!
            isRunning = true
            return true
        } catch (e: Exception) {
            // non-nullable lateinit var
            Log.e(AppConfig.TAG, "Failed to establish VPN interface", e)
            stopV2Ray()
        }
        return false
    }

    /**
     * Configures per-app proxy rules for the VPN builder.
     *
     * - If per-app proxy is not enabled, disallow the VPN service's own package.
     * - If no apps are selected, disallow the VPN service's own package.
     * - If bypass mode is enabled, disallow all selected apps (including self).
     * - If proxy mode is enabled, only allow the selected apps (excluding self).
     *
     * @param builder The VPN Builder to configure.
     */
    private fun configurePerAppProxy(builder: Builder) {
        val selfPackageName = BuildConfig.APPLICATION_ID

        // If per-app proxy is not enabled, disallow the VPN service's own package and return
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY) == false) {
            builder.addDisallowedApplication(selfPackageName)
            return
        }

        // If no apps are selected, disallow the VPN service's own package and return
        val apps = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET)
        if (apps.isNullOrEmpty()) {
            builder.addDisallowedApplication(selfPackageName)
            return
        }

        val bypassApps = MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS)
        // Handle the VPN service's own package according to the mode
        if (bypassApps) apps.add(selfPackageName) else apps.remove(selfPackageName)

        apps.forEach {
            try {
                if (bypassApps) {
                    // In bypass mode, disallow the selected apps
                    builder.addDisallowedApplication(it)
                } else {
                    // In proxy mode, only allow the selected apps
                    builder.addAllowedApplication(it)
                }
            } catch (e: PackageManager.NameNotFoundException) {
                Log.e(AppConfig.TAG, "Failed to configure app in VPN: ${e.localizedMessage}", e)
            }
        }
    }

    /**
     * Runs the tun2socks process.
     * Starts the tun2socks process with the appropriate parameters.
     */
    private fun runTun2socks() {
        Log.i(AppConfig.TAG, "Start run $TUN2SOCKS")
        val socksPort = SettingsManager.getSocksPort()
        val vpnConfig = SettingsManager.getCurrentVpnInterfaceAddressConfig()
        val cmd = arrayListOf(
            File(applicationContext.applicationInfo.nativeLibraryDir, TUN2SOCKS).absolutePath,
            "--netif-ipaddr", vpnConfig.ipv4Router,
            "--netif-netmask", "255.255.255.252",
            "--socks-server-addr", "$LOOPBACK:${socksPort}",
            "--tunmtu", VPN_MTU.toString(),
            "--sock-path", "sock_path",//File(applicationContext.filesDir, "sock_path").absolutePath,
            "--enable-udprelay",
            "--loglevel", "notice"
        )

        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PREFER_IPV6)) {
            cmd.add("--netif-ip6addr")
            cmd.add(vpnConfig.ipv6Router)
        }
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_LOCAL_DNS_ENABLED)) {
            val localDnsPort = Utils.parseInt(MmkvManager.decodeSettingsString(AppConfig.PREF_LOCAL_DNS_PORT), AppConfig.PORT_LOCAL_DNS.toInt())
            cmd.add("--dnsgw")
            cmd.add("$LOOPBACK:${localDnsPort}")
        }
        Log.i(AppConfig.TAG, cmd.toString())

        try {
            val proBuilder = ProcessBuilder(cmd)
            proBuilder.redirectErrorStream(true)
            process = proBuilder
                .directory(applicationContext.filesDir)
                .start()
            Thread {
                Log.i(AppConfig.TAG, "$TUN2SOCKS check")
                process.waitFor()
                Log.i(AppConfig.TAG, "$TUN2SOCKS exited")
                if (isRunning) {
                    Log.i(AppConfig.TAG, "$TUN2SOCKS restart")
                    runTun2socks()
                }
            }.start()
            Log.i(AppConfig.TAG, "$TUN2SOCKS process info : ${process.toString()}")

            sendFd()
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to start $TUN2SOCKS process", e)
        }
    }

    /**
     * Sends the file descriptor to the tun2socks process.
     * Attempts to send the file descriptor multiple times if necessary.
     */
    private fun sendFd() {
        val fd = mInterface.fileDescriptor
        val path = File(applicationContext.filesDir, "sock_path").absolutePath
        Log.i(AppConfig.TAG, "LocalSocket path : $path")

        CoroutineScope(Dispatchers.IO).launch {
            var tries = 0
            while (true) try {
                Thread.sleep(50L shl tries)
                Log.i(AppConfig.TAG, "LocalSocket sendFd tries: $tries")
                LocalSocket().use { localSocket ->
                    localSocket.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
                    localSocket.setFileDescriptorsForSend(arrayOf(fd))
                    localSocket.outputStream.write(42)
                }
                break
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to send file descriptor, try: $tries", e)
                if (tries > 5) break
                tries += 1
            }
        }
    }

    /**
     * Stops the V2Ray service.
     * @param isForced Whether to force stop the service.
     */
    private fun stopV2Ray(isForced: Boolean = true) {
//        val configName = defaultDPreference.getPrefString(PREF_CURR_CONFIG_GUID, "")
//        val emptyInfo = VpnNetworkInfo()
//        val info = loadVpnNetworkInfo(configName, emptyInfo)!! + (lastNetworkInfo ?: emptyInfo)
//        saveVpnNetworkInfo(configName, info)
        isRunning = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                connectivity.unregisterNetworkCallback(defaultNetworkCallback)
            } catch (ignored: Exception) {
                // ignored
            }
        }

        try {
            Log.i(AppConfig.TAG, "$TUN2SOCKS destroy")
            process.destroy()
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to destroy $TUN2SOCKS process", e)
        }

        V2RayServiceManager.stopCoreLoop()

        if (isForced) {
            //stopSelf has to be called ahead of mInterface.close(). otherwise v2ray core cannot be stooped
            //It's strage but true.
            //This can be verified by putting stopself() behind and call stopLoop and startLoop
            //in a row for several times. You will find that later created v2ray core report port in use
            //which means the first v2ray core somehow failed to stop and release the port.
            stopSelf()

            try {
                mInterface.close()
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to close VPN interface", e)
            }
        }
    }
}
