package space.linuxct.teleforward.service

/**
 * Turns a **parcel tracking number that is literally present in a notification's text** into a carrier
 * tracking url, so a "your parcel has shipped" SMS or shop notification becomes one tap from the
 * tracking page. Pure Kotlin, unit-testable, no Android types.
 *
 * This deliberately lives beside [LinkHarvest] rather than among the per-app magic links: a tracking
 * number arrives by SMS, email or any shop app, so detection must be **app-agnostic**, and the number
 * is *readable* rather than a hidden id that needs reconstructing.
 *
 * ## Why only UPS and USPS
 *
 * The obvious instinct — "validate the check digit and it's safe" — is wrong, and measurably so. A
 * check digit adds exactly **one decimal digit of redundancy: a 10× filter**, so roughly one in ten
 * random strings of the right shape passes. What actually makes detection safe is a **structural
 * anchor** the number can't be confused with.
 *
 *  - **UPS** is anchored by its literal `1Z` prefix. Safe.
 *  - **USPS IMpb** is anchored by an unusual length (20/22/26 digits) plus a leading `9` on the modern
 *    forms. Safe enough.
 *  - **FedEx** is a bare 12- or 15-digit number. Its checksum is reliable, but `123456789012` and
 *    `987654321098` both validate — so any 12-digit order id has a ~1-in-10 chance of becoming a wrong
 *    link. **Excluded on purpose.**
 *  - **DHL Express** is a bare 10-digit number, i.e. the same shape as a phone number — `2125551234`
 *    validates as an AWB. **Excluded on purpose.**
 *  - **UPU S10** (`RR123456785IN`) is well-anchored and would be safe to *detect*, but there is no
 *    universal official tracker for it; linking it would mean sending the number to a third-party
 *    aggregator. Left out until that's a deliberate choice rather than a side effect.
 */
object Parcel {

    /** `1Z` + 16 alphanumerics. The `1Z` literal is the anchor that makes this safe in free text. */
    private val UPS_REGEX = Regex("""\b1Z[A-Z0-9]{16}\b""", RegexOption.IGNORE_CASE)

    /**
     * A USPS IMpb number: 20, 22 or 26 digits, optionally preceded by the `420` + ZIP5 routing prefix
     * that appears on labels (stripped before use — the routing prefix is not part of the number you
     * track). Longest alternative first so a 26-digit number isn't clipped to 20.
     */
    private val USPS_REGEX = Regex("""\b(?:420\d{5})?(\d{26}|\d{22}|\d{20})\b""")

    /** Upper bound on tracking links synthesised per notification. */
    const val MAX_TRACKING_LINKS = 3

    fun upsUrl(tracking: String): String =
        "https://www.ups.com/track?loc=en_US&tracknum=$tracking"

    fun uspsUrl(tracking: String): String =
        "https://tools.usps.com/go/TrackConfirmAction?qtc_tLabels1=$tracking"

    /**
     * Pure: every carrier tracking url derivable from [texts], de-duplicated in first-seen order and
     * capped at [MAX_TRACKING_LINKS]. Only checksum-valid, structurally-anchored numbers qualify — a
     * miss yields nothing rather than a wrong link.
     */
    fun trackingUrls(texts: Iterable<CharSequence?>): List<String> {
        val out = LinkedHashSet<String>()
        for (cs in texts) {
            val s = cs?.toString()
            if (s.isNullOrEmpty()) continue
            for (m in UPS_REGEX.findAll(s)) {
                val candidate = m.value.uppercase()
                if (isValidUps(candidate)) out += upsUrl(candidate)
                if (out.size >= MAX_TRACKING_LINKS) return out.toList()
            }
            for (m in USPS_REGEX.findAll(s)) {
                // group(1) is the number without the 420+ZIP5 routing prefix.
                val candidate = m.groupValues[1]
                if (isValidUsps(candidate)) out += uspsUrl(candidate)
                if (out.size >= MAX_TRACKING_LINKS) return out.toList()
            }
        }
        return out.toList()
    }

    /**
     * Pure: UPS's mod-10 check digit. Over the 15 characters between the `1Z` prefix and the final
     * check digit, a letter counts as `(ASCII - 63) mod 10` and a digit as itself; positions are
     * 1-indexed with odd ×1 and even ×2; the check digit is `(10 - sum mod 10) mod 10`.
     */
    fun isValidUps(tracking: String): Boolean {
        if (tracking.length != 18 || !tracking.startsWith("1Z", ignoreCase = true)) return false
        val payload = tracking.substring(2)
        val check = payload[15]
        if (!check.isDigit()) return false
        var sum = 0
        for (i in 0 until 15) {
            val c = payload[i]
            val value = when {
                c.isDigit() -> c - '0'
                c in 'A'..'Z' -> (c.code - 63) % 10
                else -> return false
            }
            // 1-indexed position: odd ×1, even ×2.
            sum += if ((i + 1) % 2 == 0) value * 2 else value
        }
        return (10 - sum % 10) % 10 == check - '0'
    }

    /**
     * Pure: USPS IMpb's mod-10 check digit — weights 3 and 1 alternating from the rightmost body digit,
     * check digit `(10 - sum mod 10) mod 10`.
     *
     * Two structural rules matter. Modern 22/26-digit numbers must start with `9` (an extra anchor
     * against ordinary long digit runs). And a legacy 20-digit number whose body does **not** begin
     * `9[1-5]` must have `"91"` prepended before checksumming — miss that and valid Signature
     * Confirmation numbers are wrongly rejected.
     */
    fun isValidUsps(digits: String): Boolean {
        if (digits.length != 20 && digits.length != 22 && digits.length != 26) return false
        if (!digits.all { it.isDigit() }) return false
        // Anchor: the modern forms always start with 9. (The legacy 20-digit form may not.)
        if (digits.length != 20 && !digits.startsWith("9")) return false
        val check = digits.last() - '0'
        var body = digits.dropLast(1)
        if (digits.length == 20 && !Regex("""^9[1-5]""").containsMatchIn(body)) {
            body = "91$body"
        }
        var sum = 0
        var weight = 3
        for (i in body.indices.reversed()) {
            sum += (body[i] - '0') * weight
            weight = if (weight == 3) 1 else 3
        }
        return (10 - sum % 10) % 10 == check
    }
}
