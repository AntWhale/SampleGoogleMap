package com.boo.sample.googlemap

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.location.Location
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle

import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.boo.sample.googlemap.databinding.ActivityMapsBinding
import com.google.android.gms.location.*
import com.google.android.gms.maps.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class MapsActivity : AppCompatActivity(), OnMapReadyCallback {
    val TAG = this::class.java.simpleName

    private lateinit var mMap: GoogleMap
    private lateinit var binding: ActivityMapsBinding

    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private val pastLocation = MutableLiveData<Location>()
    private val currentLocation = MutableLiveData<Location>()
    private var currentMarker: Marker? = null

    @SuppressLint("MissingPermission")
    fun FusedLocationProviderClient.locationFlow() = callbackFlow<Location> {
        //A new Flow is crated. This code executes in a coroutine

        //1. Create callback and add elements into the flow
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                super.onLocationResult(result)

                val location = result.locations.last()

                try {
                    trySend(location)
                } catch (t: Throwable) {
                    t.printStackTrace()
                }
            }
        }

        val locationRequest = LocationRequest.Builder(1000)
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .build()

        //2. Register the callback to get location updates by calling requestLocationUpdates
        requestLocationUpdates(
            locationRequest,
            callback,
            Looper.getMainLooper()
        ).addOnFailureListener { e ->
            e.printStackTrace()
        }

        //3. Wait for the consumer to cancel the coroutine and unregister
        awaitClose {
            removeLocationUpdates(callback)
        }

    }.shareIn(
        lifecycleScope,
        replay = 1,
        started = SharingStarted.WhileSubscribed()
    )

    @RequiresApi(Build.VERSION_CODES.N)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")

        binding = ActivityMapsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Obtain the SupportMapFragment and get notified when the map is ready to be used.
        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        locationPermissionRequest.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )

    }

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.N)
    val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        when {
            permissions.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false) and
                    permissions.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false) -> {
                Log.d(TAG, "all location permissions granted")
                fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
                startLocationUpdates()
            }
            permissions.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false) ->
                Log.d(TAG, "ACCESS_FINE_LOCATION granted")
            permissions.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false) ->
                Log.d(TAG, "ACCESS_COARSE_LOCATION granted")
            else ->
                Log.d(TAG, "location permissions denied")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                fusedLocationClient.locationFlow()
                    .collect{ currentPosition->

                    Log.d(TAG, "currentLocation is $currentPosition")
//                    Log.d(TAG, "currentThread is ${Thread.currentThread().name}")

                    if(currentLocation.value != null) {
                        pastLocation.value = (currentLocation.value)
                    }
                    currentLocation.value = currentPosition

                    val bearing = if(pastLocation.value != null && currentLocation.value != null) {
                        getBearing(pastLocation.value!!, currentLocation.value!!)
                    } else 0f

                    val markerOptions = MarkerOptions()
                        .position(LatLng(currentLocation.value!!.latitude, currentLocation.value!!.longitude))
                        .rotation(bearing)
                        .flat(true)
                        .icon(markerImgDescriptor)

                    currentMarker?.remove()
                    currentMarker = mMap.addMarker(markerOptions)
                }
            }
        }
    }

    val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            super.onLocationResult(locationResult)

            lifecycleScope.launch {
                val currentPosition = locationResult.locations.last()
                Log.d(TAG, "currentPosition $currentPosition")

                if(currentLocation.value != null) {
                    pastLocation.value = (currentLocation.value)
                }
                currentLocation.value = currentPosition

                val bearing = if(pastLocation.value != null && currentLocation.value != null) {
                    getBearing(pastLocation.value!!, currentLocation.value!!)
                } else 0f

                val markerOptions = MarkerOptions()
                    .position(LatLng(currentLocation.value!!.latitude, currentLocation.value!!.longitude))
                    .rotation(bearing)
                    .flat(true)
                    .icon(markerImgDescriptor)

                currentMarker?.let { marker -> marker.remove() }
                currentMarker = mMap.addMarker(markerOptions)
            }
        }
    }

    private val bitmapDrawable by lazy { ResourcesCompat.getDrawable(resources, R.drawable.gps_cursor, null) as BitmapDrawable }
    private val markerBitmap by lazy { bitmapDrawable.bitmap }
    private val markerImg by lazy { Bitmap.createScaledBitmap(markerBitmap, 37, 45, false) }
    val markerImgDescriptor by lazy { BitmapDescriptorFactory.fromBitmap(markerImg) }

    /**
     * Manipulates the map once available.
     * This callback is triggered when the map is ready to be used.
     * This is where we can add markers or lines, add listeners or move the camera. In this case,
     * we just add a marker near Sydney, Australia.
     * If Google Play services is not installed on the device, the user will be prompted to install
     * it inside the SupportMapFragment. This method will only be triggered once the user has
     * installed Google Play services and returned to the app.
     */

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onMapReady(googleMap: GoogleMap) {
        Log.d(TAG, "onMapReady")
        mMap = googleMap

        val LATLNG = LatLng(37.566418, 126.977943)

        val cameraPosition = CameraPosition.Builder()
            .target(LATLNG)
            .zoom(15.0f)
            .build()

        val cameraUpdate = CameraUpdateFactory.newCameraPosition(cameraPosition)
        mMap.moveCamera(cameraUpdate)



        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
        startLocationUpdates()
    }

    private suspend fun radiansToDegrees(x: Double) = withContext(Dispatchers.IO) { x * 180.0 / Math.PI }

    private suspend fun getBearing(pastLocation: Location, currentLocation: Location): Float {
        return withContext(Dispatchers.IO) {
            val pastLat = (Math.PI * pastLocation.latitude) / 180.0f
            val pastLon = (Math.PI * pastLocation.longitude) / 180.0f
            val currentLat = (Math.PI * currentLocation.latitude) / 180.0f
            val currentLon = (Math.PI * currentLocation.longitude) / 180.0f

            val degree = radiansToDegrees(
                atan2(
                    sin(currentLon - pastLon) * cos(currentLat),
                    cos(pastLat) * sin(currentLat) - sin(pastLat) * cos(currentLat) * cos(currentLon - pastLon)
                )
            )

             if (degree >= 0) {
                 Log.d(TAG, "getBearing: ${degree.toFloat()}")
                 degree.toFloat()
            } else {
                 Log.d(TAG, "getBearing: ${(360 + degree).toFloat()}")
                 (360 + degree).toFloat()
            }
        }
    }

    override fun onPause() {
        Log.d(TAG, "onPause")
        super.onPause()
        stopLocationUpdates()
    }

    private fun stopLocationUpdates() {
        if(this::fusedLocationClient.isInitialized)
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }
}