package com.xeniaone.templeapp.pinelabs

/**
 * Constants for Pine Labs Plutus Smart / APOS integration
 * Reference: Pine Labs Plutus Smart/APOS Integration Guide v2.7
 */
object PineLabsConstants {

    // Package and Action for App-to-App Hybrid Intent Integration
    const val PACKAGE_NAME = "com.pinelabs.masterapp"
    const val ACTION_HYBRID_REQUEST = "com.pinelabs.masterapp.HYBRID_REQUEST"
    const val ACTION_SERVER = "com.pinelabs.masterapp.SERVER"

    // Intent Extras
    const val EXTRA_REQUEST_DATA = "REQUEST_DATA"
    const val EXTRA_PACKAGE_NAME = "packageName"
    const val EXTRA_RESPONSE_DATA = "RESPONSE_DATA"

    // Messenger Constants (for Native Bound Service IPC if used)
    const val MESSAGE_CODE = 1001
    const val BILLING_REQUEST_TAG = "MASTERAPPREQUEST"
    const val BILLING_RESPONSE_TAG = "MASTERAPPRESPONSE"

    // Method IDs (Header.MethodId)
    const val METHOD_DO_TRANSACTION = "1001"
    const val METHOD_PRINT_DATA = "1002"
    const val METHOD_SETTLEMENT = "1003"
    const val METHOD_GET_TERMINAL_INFO = "1004"
    const val METHOD_CONNECT_BT = "1005"
    const val METHOD_DISCONNECT_BT = "1006"
    const val METHOD_SCAN_CODE = "1007"
    const val METHOD_UPLOAD_INVOICE = "1008"
    const val METHOD_SINGLE_SCAN = "1009"
    const val METHOD_MULTI_SCAN = "1010"

    // Transaction Types (Detail.TransactionType)
    const val TXN_TYPE_SALE = 4001L          // Card Sale (Chip / Swipe / Contactless Tap)
    const val TXN_TYPE_REFUND = 4002L        // Refund
    const val TXN_TYPE_VOID = 4006L          // Void Transaction
    const val TXN_TYPE_REPRINT = 4504L       // Reprint Last Transaction
    const val TXN_TYPE_WALLET_PAY = 5102L    // Wallet Pay
    const val TXN_TYPE_UPI_SALE = 5120L      // Dynamic UPI Sale on Terminal Screen
    const val TXN_TYPE_UPI_STATUS = 5122L    // UPI Get Status
    const val TXN_TYPE_BHARAT_QR = 5123L     // Bharat QR Sale

    // Print Data Types (PrintData.Data[i].PrintDataType)
    const val PRINT_DATA_TYPE_TEXT = 0
    const val PRINT_DATA_TYPE_IMAGE_PATH = 1
    const val PRINT_DATA_TYPE_IMAGE_DUMP = 2
    const val PRINT_DATA_TYPE_BARCODE = 3
    const val PRINT_DATA_TYPE_QR_CODE = 4

    // Printer Line Widths (character count per line)
    const val PRINTER_WIDTH_24 = 24
    const val PRINTER_WIDTH_32 = 32
    const val PRINTER_WIDTH_48 = 48

    // Response Codes
    const val RESPONSE_CODE_SUCCESS = 0
    const val RESPONSE_CODE_APP_NOT_ACTIVATED = 1
    const val RESPONSE_CODE_ALREADY_ACTIVATED = 2
    const val RESPONSE_CODE_INVALID_METHOD_ID = 3
    const val RESPONSE_CODE_INVALID_USER_PIN = 4
    const val RESPONSE_CODE_USER_BLOCKED = 5
    const val RESPONSE_CODE_PERMISSION_DENIED = 6
    const val RESPONSE_CODE_INVALID_DATA_FORMAT = 7
    const val RESPONSE_CODE_UPI_INITIATED_CHECK_STATUS = 100

    // Printer Response Codes
    const val PRINTER_RES_SUCCESS = 0
    const val PRINTER_RES_FAILED = 1
    const val PRINTER_RES_BUSY = 1001
    const val PRINTER_RES_OUT_OF_PAPER = 1002
    const val PRINTER_RES_LOW_PAPER = 1003
    const val PRINTER_RES_LOW_BATTERY = 1004
    const val PRINTER_RES_HARDWARE_ERROR = 1005
    const val PRINTER_RES_OVERHEAT = 1006
    const val PRINTER_RES_BUFFER_OVERFLOW = 1007
    const val PRINTER_RES_PAPER_ALIGN_POSITION = 1008
    const val PRINTER_RES_PAPER_JAM = 1009
    const val PRINTER_RES_CUT_POSITION_ERROR = 1010

    // Default Configuration
    const val DEFAULT_APPLICATION_ID = "b5ec66b79af44d7c871781e8125bbd63"
    const val DEFAULT_USER_ID = "1234"
    const val DEFAULT_VERSION_NO = "1.0"
}
