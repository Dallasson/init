package com.init.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.google.firebase.database.FirebaseDatabase
import java.util.Locale
import kotlin.properties.Delegates

class MainActivity : AppCompatActivity() {

    private var latitude by Delegates.notNull<Double>()
    private var longitude by Delegates.notNull<Double>()
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback

    companion object {
        const val LOCATION_PERMISSION_REQUEST = 1
    }

    @SuppressLint("HardwareIds", "MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        grantLocationInfo()

        // Assign static values to UI
        findViewById<TextView>(R.id.deviceIdValue).text = getAndroidId()
        findViewById<TextView>(R.id.deviceNameValue).text = getDeviceName()
        findViewById<TextView>(R.id.batteryValue).text = "${getBatteryLevel()}%"
        findViewById<TextView>(R.id.networkValue).text = getNetworkGeneration()

        findViewById<Button>(R.id.uploadButton).setOnClickListener {
            uploadDeviceInfo()
        }
    }




    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    private fun getNetworkGeneration(): String {
        val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager

        fun mapNetworkType(type: Int): String {
            return when (type) {
                TelephonyManager.NETWORK_TYPE_GPRS,
                TelephonyManager.NETWORK_TYPE_EDGE,
                TelephonyManager.NETWORK_TYPE_CDMA,
                TelephonyManager.NETWORK_TYPE_1xRTT,
                TelephonyManager.NETWORK_TYPE_IDEN -> "2G"

                TelephonyManager.NETWORK_TYPE_UMTS,
                TelephonyManager.NETWORK_TYPE_EVDO_0,
                TelephonyManager.NETWORK_TYPE_EVDO_A,
                TelephonyManager.NETWORK_TYPE_HSDPA,
                TelephonyManager.NETWORK_TYPE_HSUPA,
                TelephonyManager.NETWORK_TYPE_HSPA,
                TelephonyManager.NETWORK_TYPE_EVDO_B,
                TelephonyManager.NETWORK_TYPE_EHRPD,
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
        return if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            "$manufacturer $model"
        }
    }

    private fun getBatteryLevel(): Int {
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) {
            (level * 100) / scale
        } else {
            -1
        }
    }

    private fun grantLocationInfo() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            getLocationInfo()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST
            )
        }
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
                super.onLocationResult(locationResult)
                val location = locationResult.lastLocation
                location?.let {
                    latitude = it.latitude
                    longitude = it.longitude

                    Log.d("Location", "Lat: $latitude, Lon: $longitude")

                    // Update UI
                    findViewById<TextView>(R.id.latitudeValue).text = latitude.toString()
                    findViewById<TextView>(R.id.longitudeValue).text = longitude.toString()

                }
            }

            override fun onLocationAvailability(p0: LocationAvailability) {
                super.onLocationAvailability(p0)
                Log.d("Location", "Location available: ${p0.isLocationAvailable}")
            }
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )

        val locationSettingsRequest = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
            .setAlwaysShow(true)
            .build()

        val settingsClient = LocationServices.getSettingsClient(this)
        settingsClient.checkLocationSettings(locationSettingsRequest)
            .addOnSuccessListener {
                Log.d("Location", "Location settings are satisfied.")
            }
            .addOnFailureListener { exception ->
                if (exception is ResolvableApiException) {
                    try {
                        exception.startResolutionForResult(this@MainActivity, 101)
                    } catch (sendEx: IntentSender.SendIntentException) {
                        Log.e("Location", "Could not resolve location settings: ${sendEx.message}")
                    }
                } else {
                    Log.e("Location", "Location settings are not satisfied: ${exception.message}")
                }
            }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
        deviceId: Int
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults, deviceId)

        if (requestCode == LOCATION_PERMISSION_REQUEST && grantResults[0] > 0) {
            getLocationInfo()
        } else {
            requestPermission()
        }
    }

    private fun requestPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            LOCATION_PERMISSION_REQUEST
        )
    }

    @SuppressLint("MissingPermission")
    fun uploadDeviceInfo(latitude: Double, longitude: Double) {
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPercent = if (level >= 0 && scale > 0) (level * 100) / scale else -1

        val data = mapOf(
            "networkType" to getNetworkGeneration(),
            "deviceID" to getAndroidId(),
            "deviceName" to getDeviceName(),
            "battery" to batteryPercent,
            "latitude" to latitude,
            "longitude" to longitude
        )

        val db = FirebaseDatabase.getInstance().reference
        db.child("Info").child(getAndroidId()).setValue(data)
            .addOnSuccessListener {
                Toast.makeText(this, "Data uploaded successfully", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Upload failed: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }

}
