package com.thelightphone.sdk.security

/** OATH credential kind. TOTP is time-based; HOTP is counter-based. */
enum class OathType { TOTP, HOTP }

/** HMAC algorithm a credential was programmed with. */
enum class OathAlgorithm { SHA1, SHA256, SHA512 }

/**
 * A credential stored on a security key's OATH applet, as reported by LIST /
 * CALCULATE ALL. [id] is the raw applet name (the unique key used in APDUs);
 * [issuer] and [name] are the parsed halves of the conventional
 * `period/issuer:account` naming scheme.
 */
data class OathCredential(
    val id: String,
    val issuer: String?,
    val name: String,
    val type: OathType,
    val algorithm: OathAlgorithm,
    val period: Int,
) {
    /** Human label: "Issuer (account)" when both are present, else whichever exists. */
    val label: String
        get() = when {
            issuer != null && name.isNotEmpty() -> "$issuer ($name)"
            issuer != null -> issuer
            else -> name
        }
}

/**
 * One computed code for a credential at a point in time.
 *
 * [value] is null when the key could not return a code without interaction:
 * [requiresTouch] means the user must touch the key, and HOTP credentials are
 * not computed by CALCULATE ALL at all (they need an explicit CALCULATE).
 */
data class OathCode(
    val credential: OathCredential,
    val value: String?,
    val validFromEpochSeconds: Long,
    val validUntilEpochSeconds: Long,
    val requiresTouch: Boolean = false,
) {
    val hasValue: Boolean get() = value != null
}
