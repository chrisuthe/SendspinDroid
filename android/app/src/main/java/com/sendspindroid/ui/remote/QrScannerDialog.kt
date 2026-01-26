package com.sendspindroid.ui.remote

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.sendspindroid.R
import com.sendspindroid.databinding.DialogQrScannerBinding
import com.sendspindroid.remote.RemoteConnection
import java.util.concurrent.Executors

/**
 * Dialog fragment for scanning Music Assistant Remote ID QR codes.
 *
 * Uses CameraX for camera preview and ML Kit for barcode detection.
 * Validates scanned codes match the expected 26-character Remote ID format.
 *
 * ## Usage
 * ```kotlin
 * QrScannerDialog.show(supportFragmentManager) { remoteId ->
 *     // Use the scanned Remote ID
 * }
 * ```
 */
class QrScannerDialog : DialogFragment() {

    companion object {
        private const val TAG = "QrScannerDialog"

        fun show(
            fragmentManager: androidx.fragment.app.FragmentManager,
            onRemoteIdScanned: (String) -> Unit
        ): QrScannerDialog {
            val dialog = QrScannerDialog()
            dialog.onRemoteIdScanned = onRemoteIdScanned
            dialog.show(fragmentManager, TAG)
            return dialog
        }
    }

    private var _binding: DialogQrScannerBinding? = null
    private val binding get() = _binding!!

    private var onRemoteIdScanned: ((String) -> Unit)? = null
    private var scanComplete = false

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val barcodeScanner = BarcodeScanning.getClient()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            showPermissionDenied()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, R.style.Theme_SendSpinDroid_Dialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogQrScannerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.grantPermissionButton.setOnClickListener {
            requestCameraPermission()
        }

        binding.enterManuallyButton.setOnClickListener {
            dismiss()
        }

        checkCameraPermission()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
        barcodeScanner.close()
        _binding = null
    }

    private fun checkCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                startCamera()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                showPermissionDenied()
            }
            else -> {
                requestCameraPermission()
            }
        }
    }

    private fun requestCameraPermission() {
        requestPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun showPermissionDenied() {
        binding.permissionDeniedView.visibility = View.VISIBLE
        binding.cameraPreview.visibility = View.GONE
        binding.scanOverlay.visibility = View.GONE
    }

    private fun startCamera() {
        binding.permissionDeniedView.visibility = View.GONE
        binding.cameraPreview.visibility = View.VISIBLE
        binding.scanOverlay.visibility = View.VISIBLE

        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases(cameraProvider)
            } catch (e: Exception) {
                Log.e(TAG, "Camera initialization failed", e)
                showError(getString(R.string.camera_not_available))
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun bindCameraUseCases(cameraProvider: ProcessCameraProvider) {
        // Preview use case
        val preview = Preview.Builder()
            .build()
            .also {
                it.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
            }

        // Image analysis for barcode scanning
        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor) { imageProxy ->
                    if (scanComplete) {
                        imageProxy.close()
                        return@setAnalyzer
                    }

                    @androidx.camera.core.ExperimentalGetImage
                    val mediaImage = imageProxy.image
                    if (mediaImage != null) {
                        try {
                            val inputImage = InputImage.fromMediaImage(
                                mediaImage,
                                imageProxy.imageInfo.rotationDegrees
                            )

                            barcodeScanner.process(inputImage)
                                .addOnSuccessListener { barcodes ->
                                    processBarcode(barcodes)
                                }
                                .addOnFailureListener { e ->
                                    Log.w(TAG, "Barcode scanning failed", e)
                                }
                                .addOnCompleteListener {
                                    imageProxy.close()
                                }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to create input image", e)
                            imageProxy.close()
                        }
                    } else {
                        imageProxy.close()
                    }
                }
            }

        // Use back camera
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                viewLifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind camera use cases", e)
            showError(getString(R.string.camera_not_available))
        }
    }

    private fun processBarcode(barcodes: List<Barcode>) {
        if (scanComplete) return
        if (_binding == null) return  // Guard against stale callback after destroy

        for (barcode in barcodes) {
            val rawValue = barcode.rawValue ?: continue

            // Try to parse as Remote ID
            val remoteId = RemoteConnection.parseRemoteId(rawValue)
            if (remoteId != null) {
                scanComplete = true
                Log.i(TAG, "Valid Remote ID scanned: ${RemoteConnection.formatRemoteId(remoteId)}")

                // Update UI on main thread
                requireActivity().runOnUiThread {
                    binding.instructionText.text = getString(R.string.qr_scanner_success)
                    binding.scanProgress.visibility = View.VISIBLE

                    // Brief delay to show success, then dismiss
                    binding.root.postDelayed({
                        onRemoteIdScanned?.invoke(remoteId)
                        dismiss()
                    }, 500)
                }
                return
            }
        }
    }

    private fun showError(message: String) {
        binding.instructionText.text = message
    }
}
