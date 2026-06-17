package com.agus.adsdetector

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var rgMode: RadioGroup
    private lateinit var rbMonitor: RadioButton
    private lateinit var rbReceiver: RadioButton
    private lateinit var btnScan: Button
    private lateinit var lvDevices: ListView
    private lateinit var tvSelectedDevice: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var etKeywords: EditText
    private lateinit var cardKeywords: android.view.View
    private lateinit var cardAccessibility: android.view.View
    private lateinit var btnOpenAccessibility: Button
    private lateinit var btnStartStop: Button

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var selectedDevice: BluetoothDevice? = null
    private val deviceList = mutableListOf<BluetoothDevice>()
    private var isRunning = false

    companion object {
        var instance: MainActivity? = null
        const val PREFS = "agus_ads_prefs"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        instance = this

        initViews()
        setupBluetooth()
        checkAccessibility()
        setupListeners()
        loadPrefs()
    }

    private fun initViews() {
        rgMode = findViewById(R.id.rgMode)
        rbMonitor = findViewById(R.id.rbMonitor)
        rbReceiver = findViewById(R.id.rbReceiver)
        btnScan = findViewById(R.id.btnScan)
        lvDevices = findViewById(R.id.lvDevices)
        tvSelectedDevice = findViewById(R.id.tvSelectedDevice)
        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        etKeywords = findViewById(R.id.etKeywords)
        cardKeywords = findViewById(R.id.cardKeywords)
        cardAccessibility = findViewById(R.id.cardAccessibility)
        btnOpenAccessibility = findViewById(R.id.btnOpenAccessibility)
        btnStartStop = findViewById(R.id.btnStartStop)
    }

    private fun setupBluetooth() {
        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bm.adapter
    }

    private fun checkAccessibility() {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val isEnabled = enabledServices.any { it.id.contains("com.agus.adsdetector") }
        cardAccessibility.visibility = if (!isEnabled) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun setupListeners() {
        rgMode.setOnCheckedChangeListener { _, checkedId ->
            cardKeywords.visibility = if (checkedId == R.id.rbMonitor) android.view.View.VISIBLE else android.view.View.GONE
            checkAccessibility()
        }

        btnOpenAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnScan.setOnClickListener {
            scanPairedDevices()
        }

        btnStartStop.setOnClickListener {
            if (isRunning) stopService() else startServiceAction()
        }
    }

    private fun loadPrefs() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val keywords = prefs.getString("keywords", etKeywords.text.toString())
        etKeywords.setText(keywords)
    }

    private fun scanPairedDevices() {
        if (!hasBluetoothPermission()) {
            requestBluetoothPermissions()
            return
        }
        deviceList.clear()
        val paired = bluetoothAdapter?.bondedDevices ?: emptySet()
        deviceList.addAll(paired)
        if (deviceList.isEmpty()) {
            Toast.makeText(this, "Tidak ada device yang sudah paired!", Toast.LENGTH_SHORT).show()
            return
        }
        val names = deviceList.map { "${it.name}\n${it.address}" }.toTypedArray()
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
        // Customize list text color
        lvDevices.adapter = adapter
        lvDevices.setOnItemClickListener { _, _, position, _ ->
            selectedDevice = deviceList[position]
            tvSelectedDevice.text = "✅ ${selectedDevice!!.name} (${selectedDevice!!.address})"
            tvSelectedDevice.setTextColor(0xFF4CAF50.toInt())
            addLog("Device dipilih: ${selectedDevice!!.name}")
        }
    }

    private fun startServiceAction() {
        if (selectedDevice == null) {
            Toast.makeText(this, "Pilih device Bluetooth dulu!", Toast.LENGTH_SHORT).show()
            return
        }

        val isMonitor = rbMonitor.isChecked

        // Save keywords
        val keywords = etKeywords.text.toString()
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("keywords", keywords).apply()

        val intent = Intent(this, BluetoothService::class.java).apply {
            putExtra("mode", if (isMonitor) "monitor" else "receiver")
            putExtra("device_address", selectedDevice!!.address)
            putExtra("keywords", keywords)
        }
        ContextCompat.startForegroundService(this, intent)

        isRunning = true
        btnStartStop.text = "⏹ STOP"
        btnStartStop.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFE53935.toInt())
        updateStatus("Running sebagai ${if (isMonitor) "Monitor 📡" else "Receiver 🔔"}...")
    }

    private fun stopService() {
        stopService(Intent(this, BluetoothService::class.java))
        isRunning = false
        btnStartStop.text = "▶ START"
        btnStartStop.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFFF6B35.toInt())
        updateStatus("Stopped")
        tvLog.text = ""
    }

    fun updateStatus(status: String) {
        runOnUiThread { tvStatus.text = status }
    }

    fun addLog(msg: String) {
        runOnUiThread {
            val current = tvLog.text.toString()
            val lines = current.split("\n").takeLast(10)
            tvLog.text = (lines + msg).joinToString("\n")
        }
    }

    private fun hasBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN), 100)
        }
    }

    override fun onResume() {
        super.onResume()
        checkAccessibility()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
}
