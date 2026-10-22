package com.tan.smartcane

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.*
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.*
import android.telephony.SmsManager
import android.text.InputType
import android.widget.*
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val spp = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val sentAction = "com.tan.smartcane.SMS_SENT"
    private val ui = Handler(Looper.getMainLooper())
    private val sender = Executors.newSingleThreadExecutor()   // isang pila para hindi magkabuhol ang mga padala
    private lateinit var prefs: SharedPreferences
    private lateinit var status: TextView
    private lateinit var gpsText: TextView
    private lateinit var log: TextView
    private lateinit var contact: EditText
    private lateinit var spinner: Spinner
    private var devices = listOf<BluetoothDevice>()
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var sosCoords: String? = null      // galing sa ESP32 (kung may GPS module)
    private var lastLoc: Location? = null                // galing sa phone
    private var locStarted = false
    private var lastSosMs = 0L
    private var retries = 0
    private var isTest = false

    // Send successfully? Yes -> audio 0040 (SMS_OK). No -> audio 0041 (SMS_FAIL), 1.5 sec, retry.
    private val smsResult = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (resultCode == RESULT_OK) {
                retries = 0; send("SMS_OK"); note("SMS naipadala")
            } else {
                send("SMS_FAIL"); note("SMS pumalya")
                if (!isTest && retries++ < 3) ui.postDelayed({ sendSms() }, 1500)
            }
        }
    }

    private val locListener = object : LocationListener {
        override fun onLocationChanged(l: Location) { consider(l) }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Walang gamit sa bagong Android")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; textSize = 20f; setOnClickListener { f() } }
        status = TextView(this).apply { text = "Hindi nakakonekta"; textSize = 22f }
        gpsText = TextView(this).apply { text = "GPS: naghihintay..."; textSize = 16f }
        contact = EditText(this).apply {
            hint = "Numero ng emergency contact"; textSize = 20f
            inputType = InputType.TYPE_CLASS_PHONE; setText(prefs.getString("num", ""))
        }
        spinner = Spinner(this)
        log = TextView(this).apply { textSize = 14f }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 40)
            addView(status); addView(gpsText); addView(spinner)
            addView(btn("Kumonekta sa Smart Cane") { connect() })
            addView(contact)
            addView(btn("I-save ang contact") {
                prefs.edit().putString("num", contact.text.toString().trim()).apply(); note("Na-save ang contact")
            })
            addView(btn("Test SMS") { isTest = true; retries = 0; sendSms() })
            addView(btn("Test alert sa device") { send("TEST") })
            addView(log)
        }
        setContentView(ScrollView(this).apply { addView(root) })

        val f = IntentFilter(sentAction)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(smsResult, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(smsResult, f)

        val p = mutableListOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 31) p += Manifest.permission.BLUETOOTH_CONNECT
        requestPermissions(p.toTypedArray(), 1)
        loadDevices()
    }

    override fun onResume() { super.onResume(); startLocation() }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        loadDevices(); startLocation()
    }

    // ---------- GPS ng phone ----------
    private fun hasLocPerm() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (locStarted || !hasLocPerm()) return
        val lm = getSystemService(LocationManager::class.java) ?: return
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (!lm.allProviders.contains(p)) continue
                lm.requestLocationUpdates(p, 5000L, 0f, locListener, Looper.getMainLooper())
                lm.getLastKnownLocation(p)?.let { consider(it) }
                locStarted = true
            } catch (_: Exception) {}   // walang permission para sa provider na ito, laktawan
        }
        if (!locStarted) gpsText.text = "GPS: i-on ang Location sa phone"
    }

    // Huwag palitan ang tumpak na GPS fix ng malabong network fix
    private fun consider(l: Location) {
        val old = lastLoc
        val better = old == null ||
            l.provider == LocationManager.GPS_PROVIDER ||
            l.accuracy <= old.accuracy ||
            System.currentTimeMillis() - old.time > 60_000
        if (better) {
            lastLoc = l
            gpsText.text = String.format(Locale.US, "GPS: %.5f, %.5f (±%d m)", l.latitude, l.longitude, l.accuracy.toInt())
        }
    }

    private fun fmt(lat: Double, lng: Double) = String.format(Locale.US, "%.6f,%.6f", lat, lng)

    // Coordinates ng ESP32: "lat,lng". Walang bisa kapag sira ang format o 0,0 (walang fix)
    private fun parseCoords(s: String): String? {
        val p = s.split(',')
        if (p.size != 2) return null
        val la = p[0].trim().toDoubleOrNull() ?: return null
        val lo = p[1].trim().toDoubleOrNull() ?: return null
        if (la !in -90.0..90.0 || lo !in -180.0..180.0 || (la == 0.0 && lo == 0.0)) return null
        return fmt(la, lo)
    }

    // Text na idadagdag sa SMS: unahin ang ESP32 GPS, kung wala ang phone GPS, kung wala pa rin ay wala
    private fun locationText(): String {
        sosCoords?.let { return " https://maps.google.com/?q=$it" }
        val l = lastLoc ?: return " (Walang GPS signal)"
        val ageMin = (System.currentTimeMillis() - l.time) / 60_000
        val old = if (ageMin >= 2) " (huling lokasyon, $ageMin min na ang nakalipas)" else ""
        return " https://maps.google.com/?q=${fmt(l.latitude, l.longitude)}$old"
    }

    // ---------- Bluetooth ----------
    @SuppressLint("MissingPermission")
    private fun loadDevices() {
        try {
            val ad = getSystemService(BluetoothManager::class.java).adapter
            devices = ad?.bondedDevices?.toList() ?: emptyList()
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                devices.map { it.name ?: it.address })
        } catch (_: SecurityException) {}
    }

    @SuppressLint("MissingPermission")
    private fun connect() {
        val d = devices.getOrNull(spinner.selectedItemPosition) ?: return note("Walang napiling device (i-pair muna sa Settings)")
        thread {
            var mine: BluetoothSocket? = null
            try {
                try { socket?.close() } catch (_: Exception) {}
                try { getSystemService(BluetoothManager::class.java).adapter?.cancelDiscovery() } catch (_: Exception) {}
                val s = d.createRfcommSocketToServiceRecord(spp)
                mine = s
                s.connect(); socket = s
                say("Nakakonekta: ${d.name}")
                send("APP_CONNECTED")
                val r = s.inputStream.bufferedReader()
                while (true) handle((r.readLine() ?: break).trim())
            } catch (e: Exception) {
                if (socket === mine || socket == null) note("Error: ${e.message}")
            } finally {
                try { mine?.close() } catch (_: Exception) {}
                if (socket === mine) { socket = null; say("Nadiskonekta") }   // huwag galawin kung may mas bago nang koneksyon
            }
        }
    }

    // ESP32 -> app
    private fun handle(l: String) {
        if (l.isEmpty()) return
        note("ESP32: $l")
        if (l == "HELLO") {                         // nag-reset ang Arduino: ulitin ang handshake
            send("APP_CONNECTED")
        } else if (l.startsWith("SOS")) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastSosMs < 3000) return      // iwas dobleng SMS sa isang pindot
            lastSosMs = now
            sosCoords = parseCoords(l.substringAfter(':', ""))
            isTest = false; retries = 0
            ui.post { sendSms() }
        }
    }

    @Suppress("DEPRECATION")
    private fun sendSms() {
        val num = prefs.getString("num", "").orEmpty()
        if (num.isBlank() || checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            send("SMS_FAIL"); note("Walang contact o SMS permission"); return
        }
        val msg = (if (isTest) "[TEST] " else "") + "SOS! Kailangan ng tulong." + locationText()
        val pi = PendingIntent.getBroadcast(this, 0, Intent(sentAction).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try {
            // Android 11 pababa: walang getSystemService(SmsManager), kaya getDefault()
            val sm = if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java) else SmsManager.getDefault()
            sm.sendTextMessage(num, null, msg, pi, null)
        } catch (e: Exception) { send("SMS_FAIL"); note("Error: ${e.message}") }
    }

    private fun send(s: String) {
        sender.execute {
            try { socket?.outputStream?.apply { write((s + "\n").toByteArray()); flush() } } catch (_: Exception) {}
        }
    }

    private fun say(s: String) { ui.post { status.text = s } }
    private fun note(s: String) { ui.post { log.text = "$s\n${log.text}".take(2000) } }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(smsResult) } catch (_: Exception) {}
        try { getSystemService(LocationManager::class.java)?.removeUpdates(locListener) } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        sender.shutdown()
    }
}
