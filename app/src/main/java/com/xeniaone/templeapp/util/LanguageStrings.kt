package com.xeniaone.templeapp.util

data class LanguageOption(
    val code: String,
    val nativeName: String,
    val englishName: String,
    val subText: String
)

object LanguageStrings {

    val supportedLanguages = listOf(
        LanguageOption("en", "English", "English", "Default"),
        LanguageOption("ml", "മലയാളം", "Malayalam", "കേരളം"),
        LanguageOption("ta", "தமிழ்", "Tamil", "தமிழ்நாடு"),
        LanguageOption("te", "తెలుగు", "Telugu", "ఆంధ్రప్రదేశ్ / తెలంగాణ"),
        LanguageOption("kn", "ಕನ್ನಡ", "Kannada", "ಕರ್ನಾಟಕ"),
        LanguageOption("hi", "हिन्दी", "Hindi", "भारत")
    )

    fun getSelectLanguageTitle(lang: String): String = when (lang) {
        "ml" -> "ഭാഷ തിരഞ്ഞെടുക്കുക"
        "ta" -> "மொழி தேர்ந்தெடுக்கவும்"
        "te" -> "భాషను ఎంచుకోండి"
        "kn" -> "ಭಾಷೆಯನ್ನು ಆಯ್ಕೆಮಾಡಿ"
        "hi" -> "भाषा चुनें"
        else -> "Select Language"
    }

    fun getSelectLanguageSubtitle(lang: String): String = when (lang) {
        "ml" -> "തുടരുന്നതിന് നിങ്ങളുടെ ഭാഷ തിരഞ്ഞെടുക്കുക"
        "ta" -> "தொடர உங்கள் விருப்பமான மொழியைத் தேர்ந்தெடுக்கவும்"
        "te" -> "కొనసాగడానికి మీ ప్రాధాన్య భాషను ఎంచుకోండి"
        "kn" -> "ಮುಂದುವರಿಯಲು ನಿಮ್ಮ ಆದ್ಯತೆಯ ಭಾಷೆಯನ್ನು ಆಯ್ಕೆಮಾಡಿ"
        "hi" -> "जारी रखने के लिए अपनी पसंदीदा भाषा चुनें"
        else -> "Choose your preferred language to continue"
    }

    fun getContinue(lang: String): String = when (lang) {
        "ml" -> "തുടരുക"
        "ta" -> "தொடரவும்"
        "te" -> "కొనసాగించండి"
        "kn" -> "ಮುಂದುವರಿಸಿ"
        "hi" -> "आगे बढ़ें"
        else -> "CONTINUE"
    }

    fun getSelectYourItem(lang: String): String = when (lang) {
        "ml" -> "നിങ്ങളുടെ ഇനം തിരഞ്ഞെടുക്കുക"
        "ta" -> "பொருளைத் தேர்ந்தெடுக்கவும்"
        "te" -> "మీ వస్తువును ఎంచుకోండి"
        "kn" -> "ನಿಮ್ಮ ವಸ್ತುವನ್ನು ಆಯ್ಕೆಮಾಡಿ"
        "hi" -> "अपना प्रसाद चुनें"
        else -> "Select Your Item"
    }

    fun getItemAppam(lang: String): String = when (lang) {
        "ml" -> "അപ്പം"
        "ta" -> "அப்பம்"
        "te" -> "అప్పం"
        "kn" -> "ಅಪ್ಪಂ"
        "hi" -> "अप्पम"
        else -> "Appam"
    }

    fun getItemAravana(lang: String): String = when (lang) {
        "ml" -> "അരവണ"
        "ta" -> "அரவணை"
        "te" -> "అరవణ"
        "kn" -> "ಅರವಣ"
        "hi" -> "अरवणा"
        else -> "Aravana"
    }

    fun getItemCombo(lang: String): String = when (lang) {
        "ml" -> "അരവണ അപ്പം കോംബോ"
        "ta" -> "அரவணை அப்பம் காம்போ"
        "te" -> "అరవణ అప్పం కాంబో"
        "kn" -> "ಅರವಣ ಅಪ್ಪಂ ಕಾಂಬೊ"
        "hi" -> "अरवणा अप्पम कॉम्बो"
        else -> "Aravana Appam Combo"
    }

    fun getColItemName(lang: String): String = when (lang) {
        "ml" -> "ഇനം"
        "ta" -> "பொருள்"
        "te" -> "వస్తువు"
        "kn" -> "ವಸ್ತು"
        "hi" -> "प्रसाद"
        else -> "Item Name"
    }

    fun getColQty(lang: String): String = when (lang) {
        "ml" -> "എണ്ണം"
        "ta" -> "அளவு"
        "te" -> "పరిమాణం"
        "kn" -> "ಪ್ರಮಾಣ"
        "hi" -> "मात्रा"
        else -> "Qty"
    }

    fun getColAmount(lang: String): String = when (lang) {
        "ml" -> "വില"
        "ta" -> "விலை"
        "te" -> "ధర"
        "kn" -> "ಬೆಲೆ"
        "hi" -> "दर"
        else -> "Amount"
    }

    fun getColTotal(lang: String): String = when (lang) {
        "ml" -> "ആകെ"
        "ta" -> "மொத்தம்"
        "te" -> "మొత్తం"
        "kn" -> "ಒಟ್ಟು"
        "hi" -> "कुल"
        else -> "Total"
    }

    fun getTotalAmountLabel(lang: String): String = when (lang) {
        "ml" -> "ആകെ തുക"
        "ta" -> "மொத்தத் தொகை"
        "te" -> "మొత్తం మొత్తం"
        "kn" -> "ಒಟ್ಟು ಮೊತ್ತ"
        "hi" -> "कुल राशि"
        else -> "Total Amount"
    }

    fun getScanCodeToPay(lang: String): String = when (lang) {
        "ml" -> "തുക അടയ്ക്കാൻ ഈ കോഡ് സ്കാൻ ചെയ്യുക"
        "ta" -> "பணம் செலுத்த இந்த குறியீட்டை ஸ்கேன் செய்யவும்"
        "te" -> "మొత్తం చెల్లించడానికి ఈ కోడ్‌ను స్కాన్ చేయండి"
        "kn" -> "ಮೊತ್ತವನ್ನು ಪಾವತಿಸಲು ಈ ಕೋಡ್ ಅನ್ನು ಸ್ಕ್ಯಾನ್ ಮಾಡಿ"
        "hi" -> "भुगतान करने के लिए इस कोड को स्कैन करें"
        else -> "Scan this code to Pay Amount"
    }

    fun getCartEmptyMessage(lang: String): String = when (lang) {
        "ml" -> "കാർട്ട് ശൂന്യമാണ്! ദയവായി പ്രസാദം തിരഞ്ഞെടുക്കുക"
        "ta" -> "கார்ட் காலியாக உள்ளது! தயவுசெய்து பொருளைத் தேர்ந்தெடுக்கவும்"
        "te" -> "కార్ట్ ఖాళీగా ఉంది! దయచేసి ప్రసాదాన్ని ఎంచుకోండి"
        "kn" -> "ಕಾರ್ಟ್ ಖಾಲಿಯಾಗಿದೆ! ದಯವಿಟ್ಟು ಪ್ರಸಾದವನ್ನು ಆಯ್ಕೆಮಾಡಿ"
        "hi" -> "कार्ट खाली है! कृपया प्रसाद चुनें"
        else -> "Cart is empty! Please select a prasadam item."
    }

    fun getLanguageDisplayName(code: String): String {
        return supportedLanguages.firstOrNull { it.code == code }?.nativeName ?: "English"
    }
}
