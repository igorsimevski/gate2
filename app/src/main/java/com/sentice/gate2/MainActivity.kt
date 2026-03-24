package com.sentice.gate2

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

class MainActivity : AppCompatActivity() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    // UI Elements
    private lateinit var tvStartPoint: TextView
    private lateinit var tvTurnPoint: TextView
    private lateinit var tvEndPoint: TextView
    private lateinit var tvApproachDistance: TextView
    private lateinit var signalContainer: View
    private lateinit var tvSignalMessage: TextView
    private lateinit var tvCurrentLocation: TextView
    private lateinit var tvDistanceToStart: TextView
    private lateinit var tvDistanceToTurn: TextView
    private lateinit var tvDistanceToEnd: TextView
    private lateinit var tvRouteStatus: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnStartTracking: Button
    private lateinit var btnStopTracking: Button

    // Pre-determined points (example coordinates - replace with your actual coordinates)
    private val startPoint = Location("").apply {
        latitude = 37.7749
        longitude = -122.4194
    }

    private val turnPoint = Location("").apply {
        latitude = 37.7755
        longitude = -122.4180
    }

    private val endPoint = Location("").apply {
        latitude = 37.7760
        longitude = -122.4165
    }

    private var hasReachedStart = false
    private var hasReachedTurn = false
    private var endPointApproached = false
    private val approachDistance = 50.0 // meters - signal will show when within this distance

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI elements
        initViews()

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        setupUI()
        checkPermissions()
    }

    private fun initViews() {
        tvStartPoint = findViewById(R.id.tvStartPoint)
        tvTurnPoint = findViewById(R.id.tvTurnPoint)
        tvEndPoint = findViewById(R.id.tvEndPoint)
        tvApproachDistance = findViewById(R.id.tvApproachDistance)
        signalContainer = findViewById(R.id.signalContainer)
        tvSignalMessage = findViewById(R.id.tvSignalMessage)
        tvCurrentLocation = findViewById(R.id.tvCurrentLocation)
        tvDistanceToStart = findViewById(R.id.tvDistanceToStart)
        tvDistanceToTurn = findViewById(R.id.tvDistanceToTurn)
        tvDistanceToEnd = findViewById(R.id.tvDistanceToEnd)
        tvRouteStatus = findViewById(R.id.tvRouteStatus)
        tvStatus = findViewById(R.id.tvStatus)
        btnStartTracking = findViewById(R.id.btnStartTracking)
        btnStopTracking = findViewById(R.id.btnStopTracking)
    }

    private fun setupUI() {
        // Display pre-determined points
        tvStartPoint.text = "Start Point: ${startPoint.latitude}, ${startPoint.longitude}"
        tvTurnPoint.text = "Turn Point: ${turnPoint.latitude}, ${turnPoint.longitude}"
        tvEndPoint.text = "End Point: ${endPoint.latitude}, ${endPoint.longitude}"
        tvApproachDistance.text = "Approach Distance: ${approachDistance}m"

        btnStartTracking.setOnClickListener {
            startLocationUpdates()
        }

        btnStopTracking.setOnClickListener {
            stopLocationUpdates()
        }
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates()
        } else {
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

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            checkPermissions()
            return
        }

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
            .setMinUpdateIntervalMillis(2000)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    updateLocationUI(location)
                    checkRouteProgress(location)
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            null
        )

        btnStartTracking.isEnabled = false
        btnStopTracking.isEnabled = true
        tvStatus.text = "Status: Tracking..."
        Toast.makeText(this, "Location tracking started", Toast.LENGTH_SHORT).show()
    }

    private fun stopLocationUpdates() {
        if (::locationCallback.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }

        btnStartTracking.isEnabled = true
        btnStopTracking.isEnabled = false
        tvStatus.text = "Status: Stopped"
        Toast.makeText(this, "Location tracking stopped", Toast.LENGTH_SHORT).show()
    }

    private fun updateLocationUI(location: Location) {
        tvCurrentLocation.text = String.format(
            "Current Location: %.6f, %.6f",
            location.latitude,
            location.longitude
        )

        // Update distances to key points
        val distanceToStart = location.distanceTo(startPoint)
        val distanceToTurn = location.distanceTo(turnPoint)
        val distanceToEnd = location.distanceTo(endPoint)

        tvDistanceToStart.text = String.format("Distance to Start: %.1f m", distanceToStart)
        tvDistanceToTurn.text = String.format("Distance to Turn: %.1f m", distanceToTurn)
        tvDistanceToEnd.text = String.format("Distance to End: %.1f m", distanceToEnd)
    }

    private fun checkRouteProgress(currentLocation: Location) {
        val distanceToStart = currentLocation.distanceTo(startPoint)
        val distanceToTurn = currentLocation.distanceTo(turnPoint)
        val distanceToEnd = currentLocation.distanceTo(endPoint)

        // Check if reached start point (within 20 meters)
        if (!hasReachedStart && distanceToStart < 20) {
            hasReachedStart = true
            tvRouteStatus.text = "✓ Reached Start Point!"
            tvRouteStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            Toast.makeText(this, "Start point reached!", Toast.LENGTH_SHORT).show()
        }

        // Check if reached turn point (within 20 meters) only after reaching start
        if (hasReachedStart && !hasReachedTurn && distanceToTurn < 20) {
            hasReachedTurn = true
            tvRouteStatus.text = "✓ Reached Turn Point!"
            Toast.makeText(this, "Turn point reached! Continue to end point.", Toast.LENGTH_SHORT).show()
        }

        // Check if approaching end point (only after reaching start and turn)
        if (hasReachedStart && hasReachedTurn && !endPointApproached && distanceToEnd < approachDistance) {
            endPointApproached = true
            showEndPointApproachSignal()
        }

        // Update progress status
        updateProgressStatus(distanceToStart, distanceToTurn, distanceToEnd)
    }

    private fun showEndPointApproachSignal() {
        // Visual signal - change background color
        signalContainer.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_orange_light))

        // Show signal message
        tvSignalMessage.visibility = View.VISIBLE
        tvSignalMessage.text = "⚠️ APPROACHING END POINT! ⚠️"
        tvSignalMessage.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))

        // Blink animation
        tvSignalMessage.animate()
            .alpha(0f)
            .setDuration(500)
            .withEndAction {
                tvSignalMessage.animate()
                    .alpha(1f)
                    .setDuration(500)
                    .withEndAction {
                        if (endPointApproached) {
                            tvSignalMessage.animate().start()
                        }
                    }
                    .start()
            }
            .start()

        Toast.makeText(this, "Approaching end point!", Toast.LENGTH_SHORT).show()
    }

    private fun updateProgressStatus(distanceToStart: Float, distanceToTurn: Float, distanceToEnd: Float) {
        tvRouteStatus.text = when {
            !hasReachedStart -> "Status: Proceed to start point (${String.format("%.1f", distanceToStart)}m away)"
            !hasReachedTurn -> "Status: Proceed to turn point (${String.format("%.1f", distanceToTurn)}m away)"
            !endPointApproached -> "Status: Proceed to end point (${String.format("%.1f", distanceToEnd)}m away)"
            else -> "Status: End point approached! Signal active!"
        }
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
                    startLocationUpdates()
                } else {
                    Toast.makeText(this, "Location permission denied", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (::locationCallback.isInitialized) {
            stopLocationUpdates()
        }
    }

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1
    }
}