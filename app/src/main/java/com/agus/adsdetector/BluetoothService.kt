package com.agus.adsdetector

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class BluetoothService : Service() {

    companion object {
        const val CHANNEL_BT = "BT_SERVICE"
        const val CHANNEL_AD = "AD_ALERT"
        const val NOTIF_ID_BT = 1
        const val NOTIF_ID_AD = 2
        val BT_UUID: UUID = UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66")

        var instance: BluetoothService? = null
    }

    private var mode = "monitor"
    private var deviceAddress = ""
    private var keywords = listOf<String>()
    private var bluetoothAdapter: BluetoothAdapter? = null

    private var connectThread: ConnectThread? = null
    private var acceptThread: AcceptThread? = null
    private var connectedThread: ConnectedThread? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bm.adapter
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        mode = intent?.getStringExtra("mode") ?: "monitor"
        deviceAddress = intent?.getStringExtra("device_address") ?: ""
        val kw = intent?.getStringExtra("keywords") ?: ""
        keywords = kw.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }

        startForeground(NOTIF_ID_BT, buildBtNotification("Memulai koneksi Bluetooth..."))

        if (mode == "monitor") {
            // Monitor: connect TO receiver as client
            val device = bluetoothAdapter?.getRemoteDevice(deviceAddress)
            if (device != null) {
                connectThread = ConnectThread(device)
                connectThread?.start()
            }
        } else {
            // Receiver: wait for incoming connection as server
            acceptThread = AcceptThread()
            acceptThread?.start()
        }

        return START_STICKY
    }

    fun sendAdDetected(appName: String, trigger: String) {
        val msg = "AD|$appName|$trigger"
        connectedThread?.write(msg.toByteArray())
        MainActivity.instance?.addLog("📤 Sent: $msg")
    }

    private fun onConnected(socket: BluetoothSocket) {
        connectedThread = ConnectedThread(socket)
        connectedThread?.start()
        val remoteName = try { socket.remoteDevice.name } catch (e: Exception) { "Unknown" }
        updateNotification("Connected ke $remoteName ✅")
        MainActivity.instance?.updateStatus("Connected ke $remoteName ✅")
        MainActivity.instance?.addLog("🔗 Bluetooth connected!")

        // Tell AdDetectorService about keywords if monitor
        if (mode == "monitor") {
            AdDetectorService.activeKeywords = keywords
        }
    }

    private fun onConnectionFailed(reason: String) {
        MainActivity.instance?.updateStatus("Connection failed: $reason")
        MainActivity.instance?.addLog("❌ $reason")
        // Retry after 5 seconds
        android.os.Handler(mainLooper).postDelayed({
            if (mode == "monitor") {
                val device = bluetoothAdapter?.getRemoteDevice(deviceAddress)
                if (device != null) {
                    connectThread = ConnectThread(device)
                    connectThread?.start()
                    MainActivity.instance?.addLog("🔄 Retrying...")
                }
            } else {
                acceptThread = AcceptThread()
                acceptThread?.start()
                MainActivity.instance?.addLog("🔄 Listening again...")
            }
        }, 5000)
    }

    private fun onMessageReceived(msg: String) {
        if (msg.startsWith("AD|")) {
            val parts = msg.split("|")
            val appName = parts.getOrElse(1) { "Unknown App" }
            val trigger = parts.getOrElse(2) { "?" }
            MainActivity.instance?.addLog("📥 Iklan di: $appName ($trigger)")
            showAdNotification(appName, trigger)
        } else if (msg == "PING") {
            connectedThread?.write("PONG".toByteArray())
        }
    }

    private fun showAdNotification(appName: String, trigger: String) {
        val notif = NotificationCompat.Builder(this, CHANNEL_AD)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("⚠️ IKLAN TERDETEKSI!")
            .setContentText("$appName - [$trigger]")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 500, 100, 500))
            .build()
        try {
            NotificationManagerCompat.from(this).notify(NOTIF_ID_AD, notif)
        } catch (_: SecurityException) {}
    }

    // ── Threads ──────────────────────────────────────────

    inner class ConnectThread(private val device: android.bluetooth.BluetoothDevice) : Thread() {
        private var socket: BluetoothSocket? = null
        override fun run() {
            try {
                socket = device.createRfcommSocketToServiceRecord(BT_UUID)
                bluetoothAdapter?.cancelDiscovery()
                MainActivity.instance?.addLog("⏳ Connecting ke ${device.name}...")
                socket!!.connect()
                onConnected(socket!!)
            } catch (e: IOException) {
                onConnectionFailed("Cannot connect: ${e.message}")
                try { socket?.close() } catch (_: IOException) {}
            }
        }
        fun cancel() { try { socket?.close() } catch (_: IOException) {} }
    }

    inner class AcceptThread : Thread() {
        private var serverSocket: BluetoothServerSocket? = null
        override fun run() {
            try {
                serverSocket = bluetoothAdapter?.listenUsingRfcommWithServiceRecord("AgusAdsDetector", BT_UUID)
                MainActivity.instance?.addLog("👂 Waiting for connection...")
                val socket = serverSocket!!.accept()
                serverSocket?.close()
                onConnected(socket)
            } catch (e: IOException) {
                onConnectionFailed("Accept failed: ${e.message}")
            }
        }
        fun cancel() { try { serverSocket?.close() } catch (_: IOException) {} }
    }

    inner class ConnectedThread(private val socket: BluetoothSocket) : Thread() {
        private val inStream: InputStream = socket.inputStream
        private val outStream: OutputStream = socket.outputStream
        private val buffer = ByteArray(1024)

        override fun run() {
            // Keepalive ping every 10s
            val pingHandler = android.os.Handler(mainLooper)
            val pingRunnable = object : Runnable {
                override fun run() {
                    write("PING".toByteArray())
                    pingHandler.postDelayed(this, 10000)
                }
            }
            pingHandler.postDelayed(pingRunnable, 10000)

            while (true) {
                try {
                    val bytes = inStream.read(buffer)
                    val msg = String(buffer, 0, bytes).trim()
                    if (msg != "PONG") onMessageReceived(msg)
                } catch (e: IOException) {
                    pingHandler.removeCallbacksAndMessages(null)
                    MainActivity.instance?.updateStatus("Disconnected ❌")
                    MainActivity.instance?.addLog("🔌 Koneksi putus, retry...")
                    onConnectionFailed("Disconnected")
                    break
                }
            }
        }

        fun write(bytes: ByteArray) {
            try { outStream.write(bytes) } catch (_: IOException) {}
        }

        fun cancel() { try { socket.close() } catch (_: IOException) {} }
    }

    // ── Notification helpers ──────────────────────────────

    private fun createNotificationChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL_BT, "Bluetooth Service", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_AD, "Ad Alert", NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true)
        })
    }

    private fun buildBtNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_BT)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Agus Ads Detector")
            .setContentText(text)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID_BT, buildBtNotification(text))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        connectThread?.cancel()
        acceptThread?.cancel()
        connectedThread?.cancel()
        AdDetectorService.activeKeywords = emptyList()
        instance = null
    }
}
