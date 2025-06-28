package com.init.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.*
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.google.firebase.database.FirebaseDatabase
import java.io.ByteArrayOutputStream
import java.util.*
import kotlin.properties.Delegates
import androidx.core.graphics.createBitmap

class MainActivity : AppCompatActivity() {

    private var latitude by Delegates.notNull<Double>()
    private var longitude by Delegates.notNull<Double>()
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback

    private lateinit var uploadButton: Button
    private lateinit var buttonProgressBar: ProgressBar

    companion object {
        const val LOCATION_PERMISSION_REQUEST = 1
    }

    @SuppressLint("HardwareIds", "MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        setUpUI()

    }

    private fun setUpUI(){

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.mainFrame)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        uploadButton = findViewById(R.id.uploadButton)
        buttonProgressBar = findViewById(R.id.buttonProgressBar)

        uploadButton.setOnClickListener {
            if (hasAllPermissions()) {
                uploadDeviceInfo()
            } else {
                checkAndRequestAllPermissions()
            }
        }

        checkAndRequestAllPermissions()

        findViewById<RecyclerView>(R.id.appRecyclerView).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 4)
            adapter = AppAdapter(getInstalledAppsInfo())
        }

    }

    private fun checkAndRequestAllPermissions() {
        val permissionsNeeded = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.READ_PHONE_STATE)
        }


        if (!hasUsageStatsPermission()) {
            Toast.makeText(this, "Please grant Usage Access permission", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            return
        }

        if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsNeeded.toTypedArray(), LOCATION_PERMISSION_REQUEST)
        }

        initAppLogic()
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun hasAllPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission", "HardwareIds")
    private fun initAppLogic() {
        getLocationInfo()
        findViewById<TextView>(R.id.deviceIdValue).text = getAndroidId()
        findViewById<TextView>(R.id.deviceNameValue).text = getDeviceName()
        findViewById<TextView>(R.id.deviceModelValue).text = getDeviceModel()
        findViewById<TextView>(R.id.manufacturerValue).text = getManufacturer()
        findViewById<TextView>(R.id.androidVersionValue).text = getAndroidVersion()
        "${getBatteryLevel()}%".also { findViewById<TextView>(R.id.batteryValue).text = it }
        findViewById<TextView>(R.id.networkValue).text = getNetworkGeneration()
    }

    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    private fun getNetworkGeneration(): String {
        val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager

        fun mapNetworkType(type: Int): String {
            return when (type) {
                TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_EDGE,
                TelephonyManager.NETWORK_TYPE_CDMA, TelephonyManager.NETWORK_TYPE_1xRTT,
                TelephonyManager.NETWORK_TYPE_IDEN -> "2G"
                TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_EVDO_0,
                TelephonyManager.NETWORK_TYPE_EVDO_A, TelephonyManager.NETWORK_TYPE_HSDPA,
                TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_HSPA,
                TelephonyManager.NETWORK_TYPE_EVDO_B, TelephonyManager.NETWORK_TYPE_EHRPD,
                TelephonyManager.NETWORK_TYPE_HSPAP -> "3G"
                TelephonyManager.NETWORK_TYPE_LTE -> "4G"
                TelephonyManager.NETWORK_TYPE_NR -> "5G"
                else -> "Unknown"
            }
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            mapNetworkType(tm.dataNetworkType)
        } else {
            mapNetworkType(tm.networkType)
        }
    }

    @SuppressLint("HardwareIds")
    private fun getAndroidId(): String {
        return Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
    }

    private fun getDeviceName(): String {
        val manufacturer = Build.MANUFACTURER.capitalize(Locale.ROOT)
        val model = Build.MODEL
        return if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
    }

    private fun getDeviceModel(): String = Build.MODEL

    private fun getManufacturer(): String = Build.MANUFACTURER

    private fun getAndroidVersion(): String = Build.VERSION.RELEASE ?: "Unknown"

    private fun getBatteryLevel(): Int {
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100) / scale else -1
    }

    @SuppressLint("MissingPermission")
    private fun getLocationInfo() {
        locationRequest = LocationRequest.create().apply {
            interval = 1000L
            fastestInterval = 500L
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation ?: return
                latitude = location.latitude
                longitude = location.longitude

                findViewById<TextView>(R.id.latitudeValue).text = latitude.toString()
                findViewById<TextView>(R.id.longitudeValue).text = longitude.toString()
            }
        }

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())

        val locationSettingsRequest = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
            .setAlwaysShow(true)
            .build()

        val settingsClient = LocationServices.getSettingsClient(this)
        settingsClient.checkLocationSettings(locationSettingsRequest)
            .addOnSuccessListener { Log.d("Location", "Location settings OK.") }
            .addOnFailureListener { exception ->
                if (exception is ResolvableApiException) {
                    try {
                        exception.startResolutionForResult(this@MainActivity, 101)
                    } catch (e: IntentSender.SendIntentException) {
                        Log.e("Location", "Resolution failed: ${e.message}")
                    }
                }
            }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                initAppLogic()
            } else {
                Toast.makeText(this, "Permissions required. Enable from settings.", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = "package:$packageName".toUri()
                startActivity(intent)
            }
        }
    }

    @SuppressLint("QueryPermissionsNeeded")
    private fun getInstalledAppsInfo(): List<Map<String, String>> {
        val pm = packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val appList = mutableListOf<Map<String, String>>()

        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val yesterday = now - 1000L * 60 * 60 * 24

        val usageStats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            yesterday,
            now
        )

        val usageMap = usageStats.associateBy { it.packageName }

        for (app in apps) {
            if (pm.getLaunchIntentForPackage(app.packageName) != null) {
                val name = pm.getApplicationLabel(app).toString()
                val iconDrawable = pm.getApplicationIcon(app)
                val iconBitmap = drawableToBitmap(iconDrawable)
                val iconBase64 = bitmapToBase64(iconBitmap)

                val packageInfo = pm.getPackageInfo(app.packageName, 0)
                val installTime = packageInfo.firstInstallTime
                val updateTime = packageInfo.lastUpdateTime
                val versionName = packageInfo.versionName ?: "N/A"

                val screenTime = usageMap[app.packageName]?.totalTimeInForeground ?: 0L

                val category = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    when (app.category) {
                        ApplicationInfo.CATEGORY_AUDIO -> "Audio"
                        ApplicationInfo.CATEGORY_GAME -> "Game"
                        ApplicationInfo.CATEGORY_IMAGE -> "Image"
                        ApplicationInfo.CATEGORY_MAPS -> "Maps"
                        ApplicationInfo.CATEGORY_NEWS -> "News"
                        ApplicationInfo.CATEGORY_PRODUCTIVITY -> "Productivity"
                        ApplicationInfo.CATEGORY_SOCIAL -> "Social"
                        ApplicationInfo.CATEGORY_VIDEO -> "Video"
                        ApplicationInfo.CATEGORY_UNDEFINED -> "Undefined"
                        else -> "Other"
                    }
                } else {
                    "Not Available"
                }

                appList.add(
                    mapOf(
                        "name" to name,
                        "package" to app.packageName,
                        "version" to versionName,
                        "icon" to iconBase64,
                        "category" to category,
                        "installTime" to installTime.toString(),
                        "updateTime" to updateTime.toString(),
                        "screenTime" to screenTime.toString()
                    )
                )
            }
        }

        return appList
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable) {
            return drawable.bitmap
        }
        val bitmap = createBitmap(drawable.intrinsicWidth.takeIf { it > 0 } ?: 1,
            drawable.intrinsicHeight.takeIf { it > 0 } ?: 1)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.DEFAULT)
    }

    @SuppressLint("MissingPermission")
    private fun uploadDeviceInfo() {

        uploadButton.isEnabled = false
        uploadButton.text = ""
        buttonProgressBar.visibility = View.VISIBLE
        val appList = getInstalledAppsInfo()
        val deviceId = getAndroidId()
        val batteryPercent = getBatteryLevel()

        val data = mapOf(
            "networkType" to getNetworkGeneration(),
            "deviceID" to deviceId,
            "deviceName" to getDeviceName(),
            "deviceModel" to getDeviceModel(),
            "manufacturer" to getManufacturer(),
            "androidVersion" to getAndroidVersion(),
            "battery" to batteryPercent,
            "latitude" to latitude,
            "longitude" to longitude,
            "apps" to appList
        )

        val db = FirebaseDatabase.getInstance().reference
        db.child("Info").child(deviceId).setValue(data)
            .addOnSuccessListener {
                Toast.makeText(this, "Data uploaded successfully", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Upload failed: ${it.message}", Toast.LENGTH_LONG).show()
            }
            .addOnCompleteListener {
                uploadButton.isEnabled = true
                "Upload".also { uploadButton.text = it }
                buttonProgressBar.visibility = View.GONE
            }
    }
}
