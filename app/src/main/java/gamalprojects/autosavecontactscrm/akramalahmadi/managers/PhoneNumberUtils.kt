package gamalprojects.autosavecontactscrm.akramalahmadi.managers

object PhoneNumberUtils {

    /**
     * Converts Arabic-Indic / Eastern Arabic numerals to ASCII digits,
     * then strips any non-digit character.
     */
    fun cleanNumber(phone: String): String {
        val sb = StringBuilder(phone.length)
        for (ch in phone) {
            when (ch) {
                in '0'..'9' -> sb.append(ch)
                in '\u0660'..'\u0669' -> sb.append(('0'.code + (ch.code - 0x0660)).toChar())
                in '\u06F0'..'\u06F9' -> sb.append(('0'.code + (ch.code - 0x06F0)).toChar())
            }
        }
        return sb.toString()
    }

    /**
     * Standardizes a phone number.
     * Removes leading +, 00, country prefix (e.g. 967), or local 0 so we can compare the core number.
     * In Yemen, a mobile number like 777777777 can appear as:
     * +967777777777, 00967777777777, 967777777777, 0777777777, 777777777.
     */
    fun normalize(phone: String): String {
        var digits = cleanNumber(phone)
        if (digits.isEmpty()) return ""

        while (digits.startsWith("00") && digits.length > 2) {
            digits = digits.substring(2)
        }

        // Handling Yemen country code (967) for both 9-digit mobile and 7-8 digit landline
        if (digits.startsWith("967") && digits.length >= 10) {
            digits = digits.substring(3)
        }

        while (digits.startsWith("0") && digits.length >= 8) {
            digits = digits.substring(1)
        }

        // Consistent matching on core last 9 digits for international numbers
        if (digits.length >= 9) {
            return digits.substring(digits.length - 9)
        }

        return digits
    }

    /**
     * Compares two phone numbers for equality after normalization.
     */
    fun areNumbersEqual(p1: String, p2: String): Boolean {
        val n1 = normalize(p1)
        val n2 = normalize(p2)
        if (n1.isEmpty() || n2.isEmpty()) return false
        return n1 == n2
    }
}
