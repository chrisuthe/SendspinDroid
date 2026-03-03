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
import com.sendspindroid.R
import com.sendspindroid.databinding.DialogQrScannerBinding
import com.sendspindroid.remote.RemoteConnection
import zxingcpp.BarcodeReader
import java.util.concurrent.Executors

/**
 * Dialog fragment for scanning Music Assistant Remote ID QR codes.
 *
 * Uses CameraX for camera preview and zxing-cpp for barcode detection.
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
    private val barcodeReader = BarcodeReader().apply {
        options.formats = setOf(BarcodeReader.Format.QR_CODE)
    }

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
        val preview = Preview.Builder()
            .build()
            .also {
                it.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
            }

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor) { imageProxy ->
                    if (scanComplete) {
                        imageProxy.close()
                        return@setAnalyzer
                    }

                    try {
                        val barcodes = barcodeReader.read(imageProxy)
                        imageProxy.close()
                        processBarcodes(barcodes)
                    } catch (e: Exception) {
                        Log.w(TAG, "Barcode scanning failed", e)
                        imageProxy.close()
                    }
                }
            }

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

    private fun processBarcodes(barcodes: List<BarcodeReader.Result>) {
        if (scanComplete) return
        if (_binding == null) return

        for (barcode in barcodes) {
            val rawValue = barcode.text ?: continue
            Log.d(TAG, "Barcode scanned: $rawValue")

            val remoteId = RemoteConnection.parseRemoteId(rawValue)
            if (remoteId != null) {
                scanComplete = true
                Log.i(TAG, "Valid Remote ID scanned: ${RemoteConnection.formatRemoteId(remoteId)}")

                requireActivity().runOnUiThread {
                    binding.instructionText.text = getString(R.string.qr_scanner_success)
                    binding.scanProgress.visibility = View.VISIBLE

                    binding.root.postDelayed({
                        onRemoteIdScanned?.invoke(remoteId)
                        dismiss()
                    }, 500)
                }
                return
            } else {
                Log.w(TAG, "Barcode rejected - no valid Remote ID found in: $rawValue")
            }
        }
    }

    private fun showError(message: String) {
        binding.instructionText.text = message
    }
}
