package com.xeniaone.templeapp.pinelabs

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.util.Log
import com.google.gson.Gson
import com.xeniaone.templeapp.model.ReceiptItem
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Manager and helper for Pine Labs Plutus Smart / APOS Inter-Process Communication.
 * Handles:
 * 1. Pine Labs hardware and MasterApp package detection
 * 2. Payment initiation via Hybrid Intent (MethodId: 1001)
 * 3. Thermal print initiation via Hybrid Intent (MethodId: 1002)
 * 4. Image dump hex conversion (PrintDataType: 2)
 */
object PineLabsManager {

    private const val TAG = "PineLabsManager"
    private val gson = Gson()


    /**
     * Checks if Pine Labs environment is available on this device.
     * Looks for:
     * - Plutus Smart MasterApp package (com.pinelabs.masterapp)
     * - Intent handler for com.pinelabs.masterapp.HYBRID_REQUEST
     * - Pine Labs POS hardware manufacturer / model attributes
     */
    fun isPineLabsAvailable(context: Context): Boolean {

        val pm = context.packageManager

        // 1. Check if MasterApp package is installed
        val isPackageInstalled = try {
            pm.getPackageInfo(PineLabsConstants.PACKAGE_NAME, 0) != null
        } catch (_: Exception) {
            false
        }
        if (isPackageInstalled) {
            Log.d(TAG, "Pine Labs MasterApp package detected: ${PineLabsConstants.PACKAGE_NAME}")
            return true
        }

        // 2. Check if HYBRID_REQUEST Intent can be resolved
        val hybridIntent = Intent(PineLabsConstants.ACTION_HYBRID_REQUEST).apply {
            setPackage(PineLabsConstants.PACKAGE_NAME)
        }
        val activities = pm.queryIntentActivities(hybridIntent, 0)
        if (activities.isNotEmpty()) {
            Log.d(TAG, "Pine Labs HYBRID_REQUEST activity resolved: ${activities.size} candidate(s)")
            return true
        }

        // 3. Check hardware manufacturer / brand / model
        val manufacturer = Build.MANUFACTURER.lowercase(Locale.ROOT)
        val brand = Build.BRAND.lowercase(Locale.ROOT)
        val model = Build.MODEL.lowercase(Locale.ROOT)
        val hardware = Build.HARDWARE.lowercase(Locale.ROOT)

        val isPineHardware = manufacturer.contains("pinelabs") ||
                brand.contains("pinelabs") ||
                model.contains("pinelabs") ||
                model.contains("plutus") ||
                hardware.contains("pinelabs")

        if (isPineHardware) {
            Log.d(TAG, "Pine Labs hardware signature detected: mfg=$manufacturer, model=$model")
            return true
        }

        return false
    }

    /**
     * Human-readable diagnostic description of device detection state.
     */
    fun getDeviceStatusDescription(context: Context): String {
        return if (isPineLabsAvailable(context)) {
            "Pine Labs POS Terminal Active (${Build.MANUFACTURER} ${Build.MODEL})"
        } else {
            "Standard Device (${Build.MANUFACTURER} ${Build.MODEL})"
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PAYMENT INTEGRATION (DoTransaction - MethodId 1001)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds the DoTransaction request payload JSON string.
     *
     * @param billingRefNo External invoice/order reference (e.g. orderId)
     * @param amountRupees Amount in INR (e.g. 100.50 -> 10050 paise)
     * @param transactionType 4001 for Card Sale, 5120 for UPI Sale
     * @param applicationId Pine Labs Application ID
     * @param userId Pine Labs User ID
     */
    fun buildTransactionPayload(
        billingRefNo: String,
        amountRupees: Double,
        transactionType: Long = PineLabsConstants.TXN_TYPE_SALE,
        applicationId: String = PineLabsConstants.DEFAULT_APPLICATION_ID,
        userId: String = PineLabsConstants.DEFAULT_USER_ID,
        mobileNumber: String? = null
    ): String {
        Log.i(TAG, "🔑 [PineLabs AppId Log] buildTransactionPayload -> applicationId = $applicationId, userId = $userId")
        val amountInPaise = (amountRupees * 100.0).roundToLong()
        val request = PineLabsTransactionRequest(
            header = PineLabsHeader(
                applicationId = applicationId,
                userId = userId,
                methodId = PineLabsConstants.METHOD_DO_TRANSACTION,
                versionNo = PineLabsConstants.DEFAULT_VERSION_NO
            ),
            detail = PineLabsTransactionDetail(
                transactionType = transactionType,
                billingRefNo = billingRefNo.take(10),
                paymentAmount = amountInPaise,
                isSwipe = true,
                mobileNumber = mobileNumber
            )
        )
        return gson.toJson(request)
    }

    /**
     * Creates an Intent to launch Pine Labs MasterApp for payment.
     */
    fun createTransactionIntent(
        context: Context,
        billingRefNo: String,
        amountRupees: Double,
        transactionType: Long = PineLabsConstants.TXN_TYPE_SALE,
        applicationId: String = PineLabsConstants.DEFAULT_APPLICATION_ID,
        userId: String = PineLabsConstants.DEFAULT_USER_ID
    ): Intent {
        val payload = buildTransactionPayload(
            billingRefNo = billingRefNo,
            amountRupees = amountRupees,
            transactionType = transactionType,
            applicationId = applicationId,
            userId = userId
        )
        Log.d(TAG, "Creating Pine Labs Transaction Intent with payload: $payload")

        return Intent(PineLabsConstants.ACTION_HYBRID_REQUEST).apply {
            setPackage(PineLabsConstants.PACKAGE_NAME)
            putExtra(PineLabsConstants.EXTRA_REQUEST_DATA, payload)
            putExtra(PineLabsConstants.EXTRA_PACKAGE_NAME, context.packageName)
        }
    }

    /**
     * Parses the transaction response returned from Pine Labs MasterApp.
     */
    fun parseTransactionResponse(data: Intent?): PineLabsTransactionResponse? {
        val json = data?.getStringExtra(PineLabsConstants.EXTRA_RESPONSE_DATA)
        if (json.isNullOrBlank()) {
            Log.w(TAG, "Empty or null RESPONSE_DATA from Pine Labs MasterApp")
            return null
        }
        Log.d(TAG, "Pine Labs Transaction Response JSON: $json")
        return try {
            gson.fromJson(json, PineLabsTransactionResponse::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing Pine Labs transaction response: ${e.message}", e)
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRINT INTEGRATION (PrintData - MethodId 1002)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds structured print items for Temple Prasadam receipt.
     * Uses space-padding for column alignment as Pine Labs print API
     * supports Left and Center alignment natively, requiring spaces for table alignment.
     */
    fun buildTempleReceiptPrintItems(
        orderId: String,
        dateFormatted: String,
        paymentRef: String,
        paymentModeTitle: String,
        items: List<ReceiptItem>,
        totalAmount: Double,
        qrData: String,
        customerName: String = "",
        mobileNumber: String = "",
        printerWidth: Int = PineLabsConstants.PRINTER_WIDTH_32,
        imageBitmap: Bitmap? = null,
        footerMessage: String = "",
        companyBrand: String = ""
    ): List<PineLabsPrintItem> {
        val list = mutableListOf<PineLabsPrintItem>()

        fun addCenter(text: String, width: Int = printerWidth) {
            list.add(
                PineLabsPrintItem(
                    printDataType = PineLabsConstants.PRINT_DATA_TYPE_TEXT,
                    printerWidth = width,
                    isCenterAligned = true,
                    dataToPrint = text
                )
            )
        }

        fun addRow(left: String, right: String) {
            val formatted = formatTwoColumns(left, right, printerWidth)
            list.add(
                PineLabsPrintItem(
                    printDataType = PineLabsConstants.PRINT_DATA_TYPE_TEXT,
                    printerWidth = printerWidth,
                    isCenterAligned = false,
                    dataToPrint = formatted
                )
            )
        }

        fun addDivider(char: Char = '-') {
            list.add(
                PineLabsPrintItem(
                    printDataType = PineLabsConstants.PRINT_DATA_TYPE_TEXT,
                    printerWidth = printerWidth,
                    isCenterAligned = false,
                    dataToPrint = char.toString().repeat(printerWidth)
                )
            )
        }

        // 1. Temple Header
        addCenter("Sabarimala")
        addCenter("Sri Dharmasastha Temple")
        addDivider('=')
        addCenter("       RECEIPT       ")
        addDivider('=')

        // 2. Metadata
        addRow("Receipt No:", orderId)
        addRow("Date:", dateFormatted)
        if (customerName.isNotBlank()) addRow("Name:", customerName.trim())
        if (mobileNumber.isNotBlank()) addRow("Mobile:", mobileNumber.trim())
        addRow("Payment Mode:", paymentModeTitle)
        if (paymentRef.isNotBlank()) addRow("Reference No:", paymentRef)
        addDivider('-')

        // 3. Tabular Item Header
        addRow("Item (Qty)", "Total (₹)")
        addDivider('-')

        // 4. Line Items
        items.forEach { item ->
            val leftCol = "${item.name} x${item.quantity}"
            val rightCol = "%.2f".format(Locale.US, item.total)
            addRow(leftCol, rightCol)
        }
        addDivider('-')

        // 5. Total
        addRow("TOTAL AMOUNT:", "₹%.2f".format(Locale.US, totalAmount))
        addDivider('=')



        // 6. Footer Notes / Helper
        fun addMultiLine(text: String) {
            if (text.isBlank()) return
            val normalized = text.replace("\\n", "\n")
            normalized.split("\n").forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotBlank()) {
                    addCenter(trimmed)
                }
            }
        }
        if (footerMessage.isNotBlank()) {
            addMultiLine(footerMessage)
        }


        // 7. QR Code
        if (qrData.isNotBlank()) {
            list.add(
                PineLabsPrintItem(
                    printDataType = PineLabsConstants.PRINT_DATA_TYPE_QR_CODE,
                    printerWidth = printerWidth,
                    isCenterAligned = true,
                    dataToPrint = qrData
                )
            )
        }

        // 7.5. Footer Message & Company Brand (below QR code)
        if (companyBrand.isNotBlank() && companyBrand != footerMessage) {
            addMultiLine(companyBrand)
        }



        // 8. Footer Image (Image Dump)
        if (imageBitmap != null) {
            Log.d(TAG, "🖨️ [PineLabs Footer Image] Processing bitmap: ${imageBitmap.width}x${imageBitmap.height}, hasAlpha=${imageBitmap.hasAlpha()}")
            val preparedBmp = prepareForPrinter(imageBitmap, 384)
            val hex = bitmapToHex(preparedBmp)
            list.add(
                PineLabsPrintItem(
                    printDataType = PineLabsConstants.PRINT_DATA_TYPE_IMAGE_DUMP,
                    printerWidth = printerWidth,
                    isCenterAligned = true,
                    imageData = hex
                )
            )
            preparedBmp.recycle()
        } else {
            Log.w(TAG, "⚠️ [PineLabs Footer Image] imageBitmap is null, footer image skipped.")
        }

        // 9. Feed space at paper end
        list.add(
            PineLabsPrintItem(
                printDataType = PineLabsConstants.PRINT_DATA_TYPE_TEXT,
                printerWidth = printerWidth,
                isCenterAligned = false,
                dataToPrint = "\n\n\n"
            )
        )

        return list
    }

    /**
     * Resizes to paper width (default 384 dots for 2" paper).
     */
    fun scaleToWidth(source: Bitmap, targetWidth: Int = 384): Bitmap {
        val targetHeight = (source.height * targetWidth.toFloat() / source.width).toInt()
        return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
    }
    /**
     * Builds PrintData payload JSON.
     */
    fun buildPrintPayload(
        printRefNo: String,
        printItems: List<PineLabsPrintItem>,
        applicationId: String = PineLabsConstants.DEFAULT_APPLICATION_ID,
        userId: String = PineLabsConstants.DEFAULT_USER_ID
    ): String {
        Log.i(TAG, "🔑 [PineLabs AppId Log] buildPrintPayload -> applicationId = $applicationId, userId = $userId")
        val request = PineLabsPrintRequest(
            header = PineLabsHeader(
                applicationId = applicationId,
                userId = userId,
                methodId = PineLabsConstants.METHOD_PRINT_DATA,
                versionNo = PineLabsConstants.DEFAULT_VERSION_NO
            ),
            detail = PineLabsPrintDetail(
                printRefNo = printRefNo.take(10),
                savePrintData = true,
                data = printItems
            )
        )
        return gson.toJson(request)
    }

    /**
     * Creates an Intent to launch Pine Labs MasterApp for thermal printing.
     */
    fun createPrintIntent(
        context: Context,
        printRefNo: String,
        printItems: List<PineLabsPrintItem>,
        applicationId: String = PineLabsConstants.DEFAULT_APPLICATION_ID,
        userId: String = PineLabsConstants.DEFAULT_USER_ID
    ): Intent {
        val payload = buildPrintPayload(
            printRefNo = printRefNo,
            printItems = printItems,
            applicationId = applicationId,
            userId = userId
        )
        Log.d(TAG, "Creating Pine Labs Print Intent with payload: $payload")

        return Intent(PineLabsConstants.ACTION_HYBRID_REQUEST).apply {
            setPackage(PineLabsConstants.PACKAGE_NAME)
            putExtra(PineLabsConstants.EXTRA_REQUEST_DATA, payload)
            putExtra(PineLabsConstants.EXTRA_PACKAGE_NAME, context.packageName)
        }
    }

    /**
     * Creates an Intent to print via Image Dump (PrintDataType = 2)
     * by converting an Android Bitmap to a monochrome 1-bit hexadecimal dump string.
     */
    fun createPrintImageDumpIntent(
        context: Context,
        printRefNo: String,
        bitmap: Bitmap,
        printerWidth: Int = PineLabsConstants.PRINTER_WIDTH_32,
        applicationId: String = PineLabsConstants.DEFAULT_APPLICATION_ID,
        userId: String = PineLabsConstants.DEFAULT_USER_ID
    ): Intent {
        val hexDump = convertBitmapToHexDump(bitmap)
        val item = PineLabsPrintItem(
            printDataType = PineLabsConstants.PRINT_DATA_TYPE_IMAGE_DUMP,
            printerWidth = printerWidth,
            isCenterAligned = true,
            imageData = hexDump
        )
        val feed = PineLabsPrintItem(
            printDataType = PineLabsConstants.PRINT_DATA_TYPE_TEXT,
            printerWidth = printerWidth,
            isCenterAligned = false,
            dataToPrint = "\n\n\n"
        )
        return createPrintIntent(
            context = context,
            printRefNo = printRefNo,
            printItems = listOf(item, feed),
            applicationId = applicationId,
            userId = userId
        )
    }

    /**
     * Parses the PrintData response returned from Pine Labs MasterApp.
     */
    fun parsePrintResponse(data: Intent?): PineLabsPrintResponse? {
        if (data == null) {
            Log.w(TAG, "❌ [PineLabs Print] result.data is NULL from MasterApp")
            return null
        }
        val json = data.getStringExtra(PineLabsConstants.EXTRA_RESPONSE_DATA)
            ?: data.getStringExtra("ResponseData")
            ?: data.getStringExtra("response_data")

        Log.d(TAG, "🖨️ [PineLabs Print Response Raw JSON]: $json")
        if (json.isNullOrBlank()) {
            val bundle = data.extras
            if (bundle != null) {
                for (key in bundle.keySet()) {
                    Log.d(TAG, "🖨️ [PineLabs Print Bundle Extra] $key = ${bundle.get(key).toString()}")
                }
            }
            Log.w(TAG, "⚠️ [PineLabs Print] Empty or null RESPONSE_DATA from Pine Labs Print API")
            return null
        }
        return try {
            val res = gson.fromJson(json, PineLabsPrintResponse::class.java)
            Log.d(TAG, "🖨️ [PineLabs Print Parsed] ResponseCode=${res?.response?.responseCode}, Message=${res?.response?.responseMsg}")
            res
        } catch (e: Exception) {
            Log.e(TAG, "❌ [PineLabs Print] Error parsing Pine Labs print response: ${e.message}", e)
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TERMINAL INFO (MethodId 1004)
    // ─────────────────────────────────────────────────────────────────────────

    fun createGetTerminalInfoIntent(
        context: Context,
        applicationId: String = PineLabsConstants.DEFAULT_APPLICATION_ID,
        userId: String = PineLabsConstants.DEFAULT_USER_ID
    ): Intent {
        val request = PineLabsTerminalInfoRequest(
            header = PineLabsHeader(
                applicationId = applicationId,
                userId = userId,
                methodId = PineLabsConstants.METHOD_GET_TERMINAL_INFO,
                versionNo = PineLabsConstants.DEFAULT_VERSION_NO
            )
        )
        val payload = gson.toJson(request)
        return Intent(PineLabsConstants.ACTION_HYBRID_REQUEST).apply {
            setPackage(PineLabsConstants.PACKAGE_NAME)
            putExtra(PineLabsConstants.EXTRA_REQUEST_DATA, payload)
            putExtra(PineLabsConstants.EXTRA_PACKAGE_NAME, context.packageName)
        }
    }

    fun parseTerminalInfoResponse(data: Intent?): PineLabsTerminalInfoResponse? {
        val json = data?.getStringExtra(PineLabsConstants.EXTRA_RESPONSE_DATA) ?: return null
        return try {
            gson.fromJson(json, PineLabsTerminalInfoResponse::class.java)
        } catch (_: Exception) {
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UTILITIES
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Formats two columns across a fixed character line width using space padding.
     */
    fun formatTwoColumns(left: String, right: String, totalWidth: Int): String {
        val maxLeft = (totalWidth - right.length - 1).coerceAtLeast(1)
        val trimmedLeft = if (left.length > maxLeft) left.take(maxLeft - 1) + "." else left
        val spacesCount = (totalWidth - trimmedLeft.length - right.length).coerceAtLeast(1)
        return trimmedLeft + " ".repeat(spacesCount) + right
    }

    fun convertBitmapToHexDump(bitmap: Bitmap): String {
        val prepared = prepareForPrinter(bitmap, 384)
        val hex = bitmapToHex(prepared)
        prepared.recycle()
        return hex
    }

    fun prepareForPrinter(src: Bitmap, targetWidth: Int = 384): Bitmap {
        val h = (src.height * targetWidth.toFloat() / src.width).toInt()
        val scaled = Bitmap.createScaledBitmap(src, targetWidth, h, true)
        val out = Bitmap.createBitmap(targetWidth, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.drawColor(android.graphics.Color.WHITE)          // transparent → white
        canvas.drawBitmap(scaled, 0f, 0f, null)
        if (scaled !== src) scaled.recycle()
        return out
    }

    fun bitmapToHex(bmp: Bitmap): String {
        val sb = StringBuilder()
        for (y in 0 until bmp.height) {
            var x = 0
            while (x < bmp.width) {
                var b = 0
                for (bit in 0 until 8) {
                    val p = if (x + bit < bmp.width) bmp.getPixel(x + bit, y) else android.graphics.Color.WHITE
                    val lum = (android.graphics.Color.red(p) * 0.299 + android.graphics.Color.green(p) * 0.587 + android.graphics.Color.blue(p) * 0.114)
                    if (lum < 128) b = b or (0x80 shr bit)
                }
                sb.append(String.format(Locale.US, "%02X", b))
                x += 8
            }
        }
        return sb.toString()
    }
}
