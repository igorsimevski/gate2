package com.sentice.gate2

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

data class RoutePoint(
    val name: String,
    val latitude: Double,
    val longitude: Double
)

// Global route points - accessible from both service and activity
object RouteData {
    val routePoints = listOf(
        RoutePoint("Start Point", 41.992780, 21.418707),
        RoutePoint("Waypoint 1", 41.992598, 21.419978),
        RoutePoint("Waypoint En", 41.992491, 21.421086)
    )

    val approachDistance = 50.0 // meters
    val pointReachDistance = 20.0 // meters
}

// Background Service for location tracking
class LocationTrackingService : Service() {

    private val binder = LocalBinder()
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var isTracking = false
    private var isPaused = false

    // Use global route points
    private val routePoints = RouteData.routePoints

    private var currentPointIndex = 0
    private var hasStarted = false
    private var endPointApproached = false

    // Callback interface for UI updates
    interface LocationUpdateListener {
        fun onLocationUpdate(location: Location)
        fun onPointReached(point: RoutePoint, isStart: Boolean, isEnd: Boolean)
        fun onEndPointApproached()
        fun onProgressUpdate(currentPoint: RoutePoint, nextPoint: RoutePoint?, distanceToNext: Float)
        fun onRouteCompleted()
        fun onTrackingStateChanged(isTracking: Boolean, isPaused: Boolean)
    }

    private var listener: LocationUpdateListener? = null

    inner class LocalBinder : Binder() {
        fun getService(): LocationTrackingService = this@LocationTrackingService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("LocationService", "Service created")
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification().build())

        // Auto-start tracking when service is created
        startTracking()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("LocationService", "onStartCommand called")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d("LocationService", "onBind called")
        return binder
    }

    fun setListener(listener: LocationUpdateListener) {
        this.listener = listener
        // Notify current state
        listener.onTrackingStateChanged(isTracking, isPaused)
    }

    fun startTracking() {
        Log.d("LocationService", "startTracking called - isTracking: $isTracking, isPaused: $isPaused")
        if (isTracking && !isPaused) return

        isTracking = true
        isPaused = false

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates()
        } else {
            Log.d("LocationService", "Location permission not granted")
        }

        listener?.onTrackingStateChanged(isTracking, isPaused)
    }

    fun pauseTracking() {
        Log.d("LocationService", "pauseTracking called")
        if (isTracking && !isPaused) {
            isPaused = true
            stopLocationUpdates()
            updateNotification("Tracking Paused")
            listener?.onTrackingStateChanged(isTracking, isPaused)
        }
    }

    fun resumeTracking() {
        Log.d("LocationService", "resumeTracking called")
        if (isTracking && isPaused) {
            isPaused = false
            startLocationUpdates()
            updateNotification("Tracking Active")
            listener?.onTrackingStateChanged(isTracking, isPaused)
        }
    }

    fun stopTracking() {
        Log.d("LocationService", "stopTracking called")
        isTracking = false
        isPaused = false
        stopLocationUpdates()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        listener?.onTrackingStateChanged(isTracking, isPaused)
    }

    fun isTrackingActive(): Boolean = isTracking && !isPaused
    fun isTrackingPaused(): Boolean = isPaused
    fun getCurrentPointIndex(): Int = currentPointIndex
    fun getRoutePoints(): List<RoutePoint> = routePoints

    private fun startLocationUpdates() {
        Log.d("LocationService", "startLocationUpdates called")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            Log.d("LocationService", "Cannot start location updates - permission denied")
            return
        }

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000)
            .setMinUpdateIntervalMillis(1000)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    Log.d("LocationService", "Location update received: ${location.latitude}, ${location.longitude}")
                    checkRouteProgress(location)
                    listener?.onLocationUpdate(location)
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )

        updateNotification("Tracking Active")
        Log.d("LocationService", "Location updates started")
    }

    private fun stopLocationUpdates() {
        Log.d("LocationService", "stopLocationUpdates called")
        if (::locationCallback.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            Log.d("LocationService", "Location updates stopped")
        }
        updateNotification("Tracking Paused")
    }

    private fun checkRouteProgress(currentLocation: Location) {
        if (currentPointIndex >= routePoints.size) {
            return
        }

        val currentTarget = routePoints[currentPointIndex]
        val targetLocation = Location("").apply {
            latitude = currentTarget.latitude
            longitude = currentTarget.longitude
        }
        val distanceToTarget = currentLocation.distanceTo(targetLocation)

        // Calculate distance to next point
        val nextPoint = if (currentPointIndex + 1 < routePoints.size)
            routePoints[currentPointIndex + 1] else null
        listener?.onProgressUpdate(currentTarget, nextPoint, distanceToTarget)

        // Check if reached current point
        if (distanceToTarget < RouteData.pointReachDistance) {
            Log.d("LocationService", "Reached point: ${currentTarget.name}")
            val isStart = (currentPointIndex == 0)
            val isEnd = (currentPointIndex == routePoints.size - 1)
            onPointReached(currentTarget, isStart, isEnd)
        }

        // Check if approaching end point (last point in array)
        if (currentPointIndex == routePoints.size - 1 &&
            distanceToTarget < RouteData.approachDistance &&
            !endPointApproached) {
            endPointApproached = true
            Log.d("LocationService", "Approaching end point!")
            listener?.onEndPointApproached()
        }
    }

    private fun onPointReached(point: RoutePoint, isStart: Boolean, isEnd: Boolean) {
        listener?.onPointReached(point, isStart, isEnd)

        if (!isEnd && currentPointIndex < routePoints.size - 1) {
            currentPointIndex++
            hasStarted = true

            // Show notification for point reached
            updateNotification("Reached: ${point.name}")
        }

        if (isEnd) {
            listener?.onRouteCompleted()
            stopTracking()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Location Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Location tracking for route navigation"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): NotificationCompat.Builder {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Route Navigation")
            .setContentText("Tracking active...")
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
    }

    private fun updateNotification(message: String) {
        val notification = createNotification()
            .setContentText(message)
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("LocationService", "Service destroyed")
        stopLocationUpdates()
    }

    companion object {
        const val CHANNEL_ID = "location_tracking_channel"
        const val NOTIFICATION_ID = 1001
    }
}

// Main Activity
class MainActivity : AppCompatActivity(), LocationTrackingService.LocationUpdateListener {

    private lateinit var tvRouteInfo: TextView
    private lateinit var tvCurrentPoint: TextView
    private lateinit var tvNextPoint: TextView
    private lateinit var tvProgress: TextView
    private lateinit var tvApproachDistance: TextView
    private lateinit var signalContainer: View
    private lateinit var tvSignalMessage: TextView
    private lateinit var tvCurrentLocation: TextView
    private lateinit var tvDistanceToNext: TextView
    private lateinit var tvRouteStatus: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnPauseTracking: Button
    private lateinit var btnResumeTracking: Button
    private lateinit var tvPointsList: TextView

    private var trackingService: LocationTrackingService? = null
    private var isBound = false

    // Use global route points
    private val routePoints = RouteData.routePoints

    private val serviceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: IBinder?) {
            Log.d("MainActivity", "Service connected")
            val binder = service as LocationTrackingService.LocalBinder
            trackingService = binder.getService()
            trackingService?.setListener(this@MainActivity)
            isBound = true
            updateUIWithServiceState()

            // Force start tracking if not already tracking
            if (!trackingService!!.isTrackingActive() && !trackingService!!.isTrackingPaused()) {
                Log.d("MainActivity", "Starting tracking from service connection")
                trackingService?.startTracking()
            }
        }

        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            Log.d("MainActivity", "Service disconnected")
            trackingService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupUI()
        checkPermissions()
        startTrackingService()
    }

    private fun initViews() {
        tvRouteInfo = findViewById(R.id.tvRouteInfo)
        tvCurrentPoint = findViewById(R.id.tvCurrentPoint)
        tvNextPoint = findViewById(R.id.tvNextPoint)
        tvProgress = findViewById(R.id.tvProgress)
        tvApproachDistance = findViewById(R.id.tvApproachDistance)
        signalContainer = findViewById(R.id.signalContainer)
        tvSignalMessage = findViewById(R.id.tvSignalMessage)
        tvCurrentLocation = findViewById(R.id.tvCurrentLocation)
        tvDistanceToNext = findViewById(R.id.tvDistanceToNext)
        tvRouteStatus = findViewById(R.id.tvRouteStatus)
        tvStatus = findViewById(R.id.tvStatus)
        btnPauseTracking = findViewById(R.id.btnPauseTracking)
        btnResumeTracking = findViewById(R.id.btnResumeTracking)
        tvPointsList = findViewById(R.id.tvPointsList)
    }

    private fun setupUI() {
        displayRouteInfo()
        tvApproachDistance.text = "End Point Alert Distance: ${RouteData.approachDistance}m"

        btnPauseTracking.setOnClickListener {
            Log.d("MainActivity", "Pause button clicked")
            trackingService?.pauseTracking()
            updateUIWithServiceState()
        }

        btnResumeTracking.setOnClickListener {
            Log.d("MainActivity", "Resume button clicked")
            trackingService?.resumeTracking()
            updateUIWithServiceState()
        }

        updateUIWithServiceState()
    }

    private fun displayRouteInfo() {
        val routeString = StringBuilder()
        routeString.append("Route Points (${routePoints.size} points):\n")
        routePoints.forEachIndexed { index, point ->
            val arrow = if (index < routePoints.size - 1) " → " else ""
            val icon = when (index) {
                0 -> "🚩 "  // Start point
                routePoints.size - 1 -> "🏁 "  // End point
                else -> "📍 "  // Waypoints
            }
            routeString.append("${index + 1}. $icon${point.name} (${point.latitude}, ${point.longitude})$arrow\n")
        }
        tvPointsList.text = routeString.toString()
        tvRouteInfo.text = "Service: Running in background"

        // Calculate and display total route distance
        calculateTotalDistance()
    }

    private fun calculateTotalDistance() {
        var totalDistance = 0.0
        for (i in 0 until routePoints.size - 1) {
            val point1 = Location("").apply {
                latitude = routePoints[i].latitude
                longitude = routePoints[i].longitude
            }
            val point2 = Location("").apply {
                latitude = routePoints[i + 1].latitude
                longitude = routePoints[i + 1].longitude
            }
            totalDistance += point1.distanceTo(point2)
        }
        tvRouteInfo.text = "Total Route Distance: ${String.format("%.0f", totalDistance)}m"
    }

    private fun startTrackingService() {
        Log.d("MainActivity", "Starting tracking service")
        val intent = Intent(this, LocationTrackingService::class.java)
        startForegroundServiceCompat(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun startForegroundServiceCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun updateUIWithServiceState() {
        if (isBound && trackingService != null) {
            if (trackingService!!.isTrackingActive()) {
                tvStatus.text = "Status: Tracking Active"
                tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
                btnPauseTracking.isEnabled = true
                btnResumeTracking.isEnabled = false
            } else if (trackingService!!.isTrackingPaused()) {
                tvStatus.text = "Status: Tracking Paused"
                tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))
                btnPauseTracking.isEnabled = false
                btnResumeTracking.isEnabled = true
            } else {
                tvStatus.text = "Status: Stopped"
                tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
                btnPauseTracking.isEnabled = false
                btnResumeTracking.isEnabled = false
            }
        } else {
            tvStatus.text = "Status: Connecting..."
            tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
        }
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        }
    }

    // LocationUpdateListener implementations
    override fun onLocationUpdate(location: Location) {
        runOnUiThread {
            tvCurrentLocation.text = String.format(
                "Current Location: %.6f, %.6f",
                location.latitude,
                location.longitude
            )
        }
    }

    override fun onPointReached(point: RoutePoint, isStart: Boolean, isEnd: Boolean) {
        runOnUiThread {
            val message = when {
                isStart -> "✓ Started route! Proceed to next point."
                isEnd -> "🏁 Reached final destination! Route complete!"
                else -> "✓ Reached ${point.name}! Continuing..."
            }

            tvRouteStatus.text = message
            tvRouteStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

            // Flash visual feedback (except for end point)
            if (!isEnd) {
                signalContainer.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    signalContainer.setBackgroundColor(ContextCompat.getColor(this, android.R.color.transparent))
                }, 500)
            }

            updateUIWithServiceState()
        }
    }

    override fun onEndPointApproached() {
        runOnUiThread {
            showEndPointApproachSignal()
        }
    }

    override fun onProgressUpdate(currentPoint: RoutePoint, nextPoint: RoutePoint?, distanceToNext: Float) {
        runOnUiThread {
            tvCurrentPoint.text = "Current Target: ${currentPoint.name}"
            tvNextPoint.text = "Next: ${nextPoint?.name ?: "End of Route"}"
            tvDistanceToNext.text = String.format("Distance to ${currentPoint.name}: %.1f m", distanceToNext)

            if (trackingService != null) {
                val currentIndex = trackingService!!.getCurrentPointIndex()
                val totalPoints = trackingService!!.getRoutePoints().size
                tvProgress.text = "Progress: $currentIndex/${totalPoints - 1} points completed"
            }
        }
    }

    override fun onRouteCompleted() {
        runOnUiThread {
            tvRouteStatus.text = "✅ Route completed successfully!"
            tvCurrentPoint.text = "Current Target: Completed!"
            tvNextPoint.text = "Next: Route Finished"
            tvProgress.text = "Progress: Complete!"
            updateUIWithServiceState()
            Toast.makeText(this, "Route completed! Tracking stopped.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onTrackingStateChanged(isTracking: Boolean, isPaused: Boolean) {
        runOnUiThread {
            updateUIWithServiceState()
        }
    }

    private fun showEndPointApproachSignal() {
        signalContainer.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_orange_light))

        tvSignalMessage.visibility = View.VISIBLE
        tvSignalMessage.text = "⚠️ APPROACHING END POINT! ⚠️"
        tvSignalMessage.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))

        tvSignalMessage.animate()
            .alpha(0f)
            .setDuration(500)
            .withEndAction {
                tvSignalMessage.animate()
                    .alpha(1f)
                    .setDuration(500)
                    .withEndAction {
                        if (trackingService?.getCurrentPointIndex() == trackingService?.getRoutePoints()?.size?.minus(1)) {
                            tvSignalMessage.animate().start()
                        }
                    }
                    .start()
            }
            .start()

        Toast.makeText(this, "Approaching end point!", Toast.LENGTH_SHORT).show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            LOCATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d("MainActivity", "Location permission granted")
                    // Start tracking if service is bound
                    if (isBound && trackingService != null) {
                        trackingService?.startTracking()
                    }
                } else {
                    Toast.makeText(this, "Location permission denied", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1
    }
}