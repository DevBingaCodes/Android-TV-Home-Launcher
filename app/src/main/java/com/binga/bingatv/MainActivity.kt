package com.binga.bingatv

import android.Manifest
import java.util.Calendar
import android.os.Bundle
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Network
import android.net.ConnectivityManager.NetworkCallback
import android.provider.Settings
import android.widget.ImageButton
import android.widget.ImageView

import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.view.LayoutInflater
import android.view.View
import android.widget.GridLayout
import android.view.KeyEvent
import android.widget.LinearLayout

import android.graphics.Color
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.Build
import android.provider.ContactsContract.CommonDataKinds.Website.URL
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt



class MainActivity : AppCompatActivity() {
    @RequiresApi(Build.VERSION_CODES.N)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        val homeTab = findViewById<TextView>(R.id.tabHome)
        val appsTab = findViewById<TextView>(R.id.tabApps)

        homeTab.setOnClickListener {
            showApps(true)
            homeTab.requestFocus()
        }

        appsTab.setOnClickListener {
            showApps(false)
            appsTab.requestFocus()
        }

        discoverApps()
        showApps(true)

        homeTab.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) {
                return@setOnKeyListener false
            }

            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> appsTab.requestFocus()
                KeyEvent.KEYCODE_DPAD_DOWN ->
                    findViewById<GridLayout>(R.id.appGrid)
                        .getChildAt(0)?.requestFocus() ?: false
                else -> false
            }
        }

        appsTab.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) {
                return@setOnKeyListener false
            }

            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> homeTab.requestFocus()
                KeyEvent.KEYCODE_DPAD_DOWN ->
                    findViewById<GridLayout>(R.id.appGrid)
                        .getChildAt(0)?.requestFocus() ?: false
                else -> false
            }
        }

        updateWifiStatus()

        findViewById<ImageButton>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val weatherContainer =
            findViewById<LinearLayout>(R.id.weatherContainer)

        weatherContainer.setOnClickListener {
            requestWeatherPermission()
        }

    }

    override fun onResume() {
        super.onResume()

        val permissionGranted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        if (
            permissionGranted &&
            System.currentTimeMillis() - lastWeatherAttempt >
            TimeUnit.MINUTES.toMillis(30)
        ) {
            getDeviceLocation()
        }
    }

    private var lastWeatherAttempt = 0L

    private val weatherPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->

            if (granted) {
                getDeviceLocation()
            } else {
                updateWeatherUI("--", "☁", "Location permission denied")
            }
        }

    private fun requestWeatherPermission() {

        val permission = Manifest.permission.ACCESS_COARSE_LOCATION

        if (
            ContextCompat.checkSelfPermission(
                this,
                permission
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            getDeviceLocation()
        } else {
            weatherPermissionLauncher.launch(permission)
        }
    }

    private fun getDeviceLocation() {

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val locationManager =
            getSystemService(LOCATION_SERVICE) as LocationManager

        val provider = listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER
        ).firstOrNull { provider ->
            try {
                locationManager.isProviderEnabled(provider)
            } catch (e: Exception) {
                false
            }
        }

        if (provider == null) {
            updateWeatherUI("--", "☁", "Location services off")
            return
        }

        try {

            val cachedLocation = locationManager
                .getLastKnownLocation(provider)

            val locationIsFresh = cachedLocation != null &&
                    System.currentTimeMillis() - cachedLocation.time <
                    TimeUnit.MINUTES.toMillis(30)

            if (locationIsFresh) {
                fetchWeather(
                    cachedLocation!!.latitude,
                    cachedLocation.longitude
                )
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {

                locationManager.getCurrentLocation(
                    provider,
                    CancellationSignal(),
                    mainExecutor
                ) { location ->

                    if (location != null) {
                        fetchWeather(
                            location.latitude,
                            location.longitude
                        )
                    } else {
                        updateWeatherUI(
                            "--",
                            "☁",
                            "Location unavailable"
                        )
                    }
                }

            } else {
                updateWeatherUI("--", "☁", "Location unavailable")
            }

        } catch (e: SecurityException) {
            Log.e("BingaTV", "Location permission error", e)
        }
    }

    private fun fetchWeather(
        latitude: Double,
        longitude: Double
    ) {

        Thread {

            var connection: HttpURLConnection? = null

            try {

                val apiUrl =
                    "https://api.open-meteo.com/v1/forecast" +
                            "?latitude=$latitude" +
                            "&longitude=$longitude" +
                            "&current=temperature_2m,weather_code,is_day" +
                            "&timezone=auto"

                connection = URL(apiUrl)
                    .openConnection() as HttpURLConnection

                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                val response = connection.inputStream
                    .bufferedReader()
                    .use { it.readText() }

                val json = JSONObject(response)
                val current = json.getJSONObject("current")

                val temperature = current
                    .getDouble("temperature_2m")
                    .roundToInt()

                val weatherCode = current.getInt("weather_code")
                val isDay = current.getInt("is_day") == 1

                val description = getWeatherDescription(weatherCode)
                val icon = getWeatherIcon(weatherCode, isDay)

                lastWeatherAttempt = System.currentTimeMillis()

                runOnUiThread {

                    updateWeatherUI(
                        "$temperature°",
                        icon,
                        description
                    )
                }

            } catch (e: Exception) {

                Log.e("BingaTV", "Weather request failed", e)

                runOnUiThread {
                    updateWeatherUI("--", "☁", "Weather unavailable")
                }

            } finally {
                connection?.disconnect()
            }

        }.start()
    }

    private fun updateWeatherUI(
        temperature: String,
        icon: String,
        description: String
    ) {

        findViewById<TextView>(R.id.weatherTemperature)
            .text = temperature

        findViewById<TextView>(R.id.weatherIcon)
            .text = icon

        findViewById<TextView>(R.id.weatherDescription)
            .text = description
    }

    private fun getWeatherDescription(code: Int): String {

        return when (code) {
            0 -> "Clear sky"
            1 -> "Mainly clear"
            2 -> "Partly cloudy"
            3 -> "Overcast"
            45, 48 -> "Foggy"
            51, 53, 55 -> "Drizzle"
            56, 57 -> "Freezing drizzle"
            61, 63, 65 -> "Rain"
            66, 67 -> "Freezing rain"
            71, 73, 75 -> "Snowfall"
            77 -> "Snow grains"
            80, 81, 82 -> "Rain showers"
            85, 86 -> "Snow showers"
            95, 96, 99 -> "Thunderstorm"
            else -> "Unknown conditions"
        }
    }

    private fun getWeatherIcon(
        code: Int,
        isDay: Boolean
    ): String {

        return when (code) {
            0 -> if (isDay) "☀" else "☾"
            1, 2 -> if (isDay) "🌤" else "☁"
            3 -> "☁"
            45, 48 -> "🌫"
            51, 53, 55, 56, 57 -> "🌦"
            61, 63, 65, 66, 67 -> "🌧"
            71, 73, 75, 77 -> "❄"
            80, 81, 82, 85, 86 -> "🌧"
            95, 96, 99 -> "⛈"
            else -> "☁"
        }
    }


    private var discoveredApps: List<ResolveInfo> = emptyList()
    private var showingHome = true

    @RequiresApi(Build.VERSION_CODES.N)
    override fun onStart() {
        super.onStart()

        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        connectivityManager.registerDefaultNetworkCallback(networkCallback)
    }

    override fun onStop() {
        super.onStop()

        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        connectivityManager.unregisterNetworkCallback(networkCallback)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun updateWifiStatus() {

        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE)
                    as ConnectivityManager

        val network = connectivityManager.activeNetwork

        val capabilities =
            connectivityManager.getNetworkCapabilities(network)

        val isWifiConnected =
            capabilities?.hasTransport(
                NetworkCapabilities.TRANSPORT_WIFI
            ) == true

        val hasInternet =
            capabilities?.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_VALIDATED
            ) == true

        val wifiIcon = findViewById<ImageView>(R.id.wifiIcon)

        when {
            !isWifiConnected -> {
                wifiIcon.setImageResource(R.drawable.ic_wifi)
                wifiIcon.alpha = 0.3f
                wifiIcon.contentDescription = "Wi-Fi disconnected"
            }

            !hasInternet -> {
                wifiIcon.setImageResource(R.drawable.ic_wifi_alert)
                wifiIcon.alpha = 1f
                wifiIcon.contentDescription = "Wi-Fi connected, no internet"
            }

            else -> {
                wifiIcon.setImageResource(R.drawable.ic_wifi)
                wifiIcon.alpha = 1f
                wifiIcon.contentDescription = "Wi-Fi connected"
            }
        }
    }


    private val networkCallback = object : NetworkCallback() {

        override fun onAvailable(network: Network) {
            runOnUiThread {
                updateWifiStatus()
            }
        }

        override fun onLost(network: Network) {
            runOnUiThread {
                updateWifiStatus()
            }
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            runOnUiThread {
                updateWifiStatus()
            }
        }
    }


    @RequiresApi(Build.VERSION_CODES.N)
    private fun discoverApps() {

        val pm = packageManager

        val tvIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        }

        val regularIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val tvApps = pm.queryIntentActivities(tvIntent, 0)
        val regularApps = pm.queryIntentActivities(regularIntent, 0)

        val apps = LinkedHashMap<String, ResolveInfo>()

        tvApps.forEach { app ->
            apps[app.activityInfo.packageName] = app
        }

        regularApps.forEach { app ->
            apps.putIfAbsent(app.activityInfo.packageName, app)
        }

        discoveredApps = apps.values.sortedBy {
            it.loadLabel(pm).toString().lowercase()
        }
    }

    private fun showApps(isHome: Boolean) {

        showingHome = isHome

        val grid = findViewById<GridLayout>(R.id.appGrid)
        val homeTab = findViewById<TextView>(R.id.tabHome)
        val appsTab = findViewById<TextView>(R.id.tabApps)

        // Update active tab.
        homeTab.isSelected = isHome
        appsTab.isSelected = !isHome

        homeTab.setTextColor(
            if (isHome) Color.WHITE else Color.parseColor("#9CA3AF")
        )

        appsTab.setTextColor(
            if (!isHome) Color.WHITE else Color.parseColor("#9CA3AF")
        )

        // Clear the previous collection.
        grid.removeAllViews()

        // Define favourite app order.
        val favourites = listOf(
            "youtube",
            "netflix",
            "spotify",
            "streamflix",
            "youcine",
            "playfy"
        )

        val appsToDisplay = if (isHome) {

            discoveredApps
                .filter { app ->
                    val name = app.loadLabel(packageManager)
                        .toString()
                        .lowercase()

                    favourites.any { name.contains(it) }
                }
                .sortedBy { app ->
                    val name = app.loadLabel(packageManager)
                        .toString()
                        .lowercase()

                    favourites.indexOfFirst { name.contains(it) }
                }

        } else {
            discoveredApps
        }

        appsToDisplay.forEachIndexed { index, app ->

            val packageName = app.activityInfo.packageName

            val card = LayoutInflater.from(this)
                .inflate(R.layout.item_app, grid, false)

            val icon = card.findViewById<ImageView>(R.id.appIcon)
            val name = card.findViewById<TextView>(R.id.appName)

            icon.setImageDrawable(app.loadIcon(packageManager))
            name.text = app.loadLabel(packageManager)

            card.setOnClickListener {

                val launchIntent =
                    packageManager.getLeanbackLaunchIntentForPackage(packageName)
                        ?: packageManager.getLaunchIntentForPackage(packageName)

                if (launchIntent != null) {
                    startActivity(launchIntent)
                }
            }

            // Custom D-pad navigation.
            card.setOnKeyListener { _, keyCode, event ->

                if (event.action != KeyEvent.ACTION_DOWN) {
                    return@setOnKeyListener false
                }

                when (keyCode) {

                    KeyEvent.KEYCODE_DPAD_LEFT -> {

                        if (isHome && index % 3 == 0) {

                            showApps(false)
                            appsTab.requestFocus()

                            true
                        } else {
                            false
                        }
                    }

                    KeyEvent.KEYCODE_DPAD_UP -> {

                        if (index < 3) {

                            if (isHome) {
                                homeTab.requestFocus()
                            } else {
                                appsTab.requestFocus()
                            }

                            true
                        } else {
                            false
                        }
                    }

                    else -> false
                }
            }

            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = dp(112)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(8), dp(8), dp(8), dp(8))
            }

            grid.addView(card, params)
        }

        // Focus the first app when the collection is displayed.
        if (grid.childCount > 0) {
            grid.getChildAt(0).requestFocus()
        }
    }
}