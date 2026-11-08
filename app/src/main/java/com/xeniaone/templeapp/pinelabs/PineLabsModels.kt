package com.xeniaone.templeapp.pinelabs

import com.google.gson.annotations.SerializedName

// ─────────────────────────────────────────────────────────────────────────────
// REQUEST DATA MODELS
// ─────────────────────────────────────────────────────────────────────────────

data class PineLabsHeader(
    @SerializedName("ApplicationId") val applicationId: String,
    @SerializedName("UserId") val userId: String = PineLabsConstants.DEFAULT_USER_ID,
    @SerializedName("MethodId") val methodId: String,
    @SerializedName("VersionNo") val versionNo: String = PineLabsConstants.DEFAULT_VERSION_NO
)

data class PineLabsTransactionDetail(
    @SerializedName("TransactionType") val transactionType: Long,
    @SerializedName("BillingRefNo") val billingRefNo: String,
    @SerializedName("PaymentAmount") val paymentAmount: Long, // In paise! E.g. Rs 100.00 = 10000
    @SerializedName("IsSwipe") val isSwipe: Boolean = true,
    @SerializedName("BankCode") val bankCode: String? = null,
    @SerializedName("InvoiceNo") val invoiceNo: String? = null,
    @SerializedName("MobileNumberForEChargeSlip") val mobileNumber: String? = null,
    @SerializedName("AdditionalInfo") val additionalInfo: Map<String, String>? = null
)

data class PineLabsTransactionRequest(
    @SerializedName("Header") val header: PineLabsHeader,
    @SerializedName("Detail") val detail: PineLabsTransactionDetail
)

data class PineLabsPrintItem(
    @SerializedName("PrintDataType") val printDataType: Int = PineLabsConstants.PRINT_DATA_TYPE_TEXT,
    @SerializedName("PrinterWidth") val printerWidth: Int = PineLabsConstants.PRINTER_WIDTH_32,
    @SerializedName("IsCenterAligned") val isCenterAligned: Boolean = false,
    @SerializedName("DataToPrint") val dataToPrint: String = "",
    @SerializedName("ImagePath") val imagePath: String = "",
    @SerializedName("ImageData") val imageData: String = ""
)

data class PineLabsPrintDetail(
    @SerializedName("PrintRefNo") val printRefNo: String,
    @SerializedName("SavePrintData") val savePrintData: Boolean = true,
    @SerializedName("Data") val data: List<PineLabsPrintItem>
)

data class PineLabsPrintRequest(
    @SerializedName("Header") val header: PineLabsHeader,
    @SerializedName("Detail") val detail: PineLabsPrintDetail
)

data class PineLabsTerminalInfoRequest(
    @SerializedName("Header") val header: PineLabsHeader
)

// ─────────────────────────────────────────────────────────────────────────────
// RESPONSE DATA MODELS
// ─────────────────────────────────────────────────────────────────────────────

data class PineLabsResponseHeader(
    @SerializedName("ApplicationId") val applicationId: String? = null,
    @SerializedName("UserId") val userId: String? = null,
    @SerializedName("MethodId") val methodId: String? = null,
    @SerializedName("VersionNo") val versionNo: String? = null
)

data class PineLabsResponseMeta(
    @SerializedName("ResponseCode") val responseCode: Int = -1,
    @SerializedName("ResponseMsg") val responseMsg: String = "",
    @SerializedName("AppVersion") val appVersion: String? = null,
    @SerializedName("ParameterJson") val parameterJson: String? = null
)

data class PineLabsTransactionDetailResponse(
    @SerializedName("BillingRefNo") val billingRefNo: String? = null,
    @SerializedName("ApprovalCode") val approvalCode: String? = null,
    @SerializedName("HostResponse") val hostResponse: String? = null,
    @SerializedName("CardNumber") val cardNumber: String? = null,
    @SerializedName("CardType") val cardType: String? = null,
    @SerializedName("CardholderName") val cardholderName: String? = null,
    @SerializedName("ExpiryDate") val expiryDate: String? = null,
    @SerializedName("InvoiceNumber") val invoiceNumber: Long? = null,
    @SerializedName("BatchNumber") val batchNumber: Long? = null,
    @SerializedName("TerminalId") val terminalId: String? = null,
    @SerializedName("MerchantId") val merchantId: String? = null,
    @SerializedName("MerchantName") val merchantName: String? = null,
    @SerializedName("RetrievalReferenceNumber") val rrn: String? = null,
    @SerializedName("PlutusTransactionLogID") val plutusTransactionLogId: String? = null,
    @SerializedName("AuthAmoutPaise") val authAmountPaise: String? = null,
    @SerializedName("AcquirerName") val acquirerName: String? = null,
    @SerializedName("AcquiringBankCode") val acquiringBankCode: String? = null,
    @SerializedName("TransactionDate") val transactionDate: String? = null,
    @SerializedName("TransactionTime") val transactionTime: String? = null,
    @SerializedName("TransactionType") val transactionType: Long? = null,
    @SerializedName("Remark") val remark: String? = null
)

data class PineLabsTransactionResponse(
    @SerializedName("Header") val header: PineLabsResponseHeader? = null,
    @SerializedName("Response") val response: PineLabsResponseMeta? = null,
    @SerializedName("Detail") val detail: PineLabsTransactionDetailResponse? = null
) {
    val isApproved: Boolean
        get() {
            val code = response?.responseCode ?: -1
            val msg = response?.responseMsg.orEmpty()
            val approval = detail?.approvalCode.orEmpty()
            return (code == PineLabsConstants.RESPONSE_CODE_SUCCESS && (approval.isNotEmpty() || msg.equals("APPROVED", ignoreCase = true) || msg.equals("SUCCESS", ignoreCase = true)))
                    || msg.equals("APPROVED", ignoreCase = true)
        }

    val isUpiPending: Boolean
        get() = response?.responseCode == PineLabsConstants.RESPONSE_CODE_UPI_INITIATED_CHECK_STATUS
}

data class PineLabsPrintResponse(
    @SerializedName("Header") val header: PineLabsResponseHeader? = null,
    @SerializedName("Response") val response: PineLabsResponseMeta? = null
) {
    val isSuccess: Boolean
        get() = response?.responseCode == PineLabsConstants.PRINTER_RES_SUCCESS
}

data class PineLabsTerminalInfoResponse(
    @SerializedName("Header") val header: PineLabsResponseHeader? = null,
    @SerializedName("Response") val response: PineLabsResponseMeta? = null,
    @SerializedName("Detail") val detail: PineLabsTerminalDetail? = null
)

data class PineLabsTerminalDetail(
    @SerializedName("PlutusStoreId") val storeId: String? = null,
    @SerializedName("PlutusTerminalId") val terminalId: String? = null,
    @SerializedName("SerialNumber") val serialNumber: String? = null,
    @SerializedName("MerchantName") val merchantName: String? = null,
    @SerializedName("StoreName") val storeName: String? = null
)
