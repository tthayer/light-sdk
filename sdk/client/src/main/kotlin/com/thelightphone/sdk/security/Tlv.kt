package com.thelightphone.sdk.security

import java.io.ByteArrayOutputStream

/**
 * A minimal BER-TLV element as used by the YKOATH applet: single-byte tags and
 * definite lengths (short form, or long form 0x81/0x82). This is not a general
 * BER parser — it only covers what the applet emits.
 */
class Tlv(val tag: Int, val value: ByteArray) {
    companion object {
        /** Encodes a single tag/value pair. */
        fun encode(tag: Int, value: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(tag and 0xff)
            when {
                value.size < 0x80 -> out.write(value.size)
                value.size < 0x100 -> { out.write(0x81); out.write(value.size) }
                else -> { out.write(0x82); out.write(value.size shr 8); out.write(value.size and 0xff) }
            }
            out.write(value)
            return out.toByteArray()
        }

        /**
         * Parses a flat sequence of TLVs. Throws [IllegalArgumentException] on a
         * truncated element so malformed card data fails loudly rather than
         * silently returning partial results.
         */
        fun parseList(bytes: ByteArray): List<Tlv> {
            val result = ArrayList<Tlv>()
            var i = 0
            while (i < bytes.size) {
                val tag = bytes[i].toInt() and 0xff
                i += 1
                require(i < bytes.size) { "TLV truncated: missing length for tag 0x${tag.toString(16)}" }
                var length = bytes[i].toInt() and 0xff
                i += 1
                when (length) {
                    0x81 -> {
                        require(i < bytes.size) { "TLV truncated: missing 1-byte length" }
                        length = bytes[i].toInt() and 0xff
                        i += 1
                    }
                    0x82 -> {
                        require(i + 1 < bytes.size) { "TLV truncated: missing 2-byte length" }
                        length = ((bytes[i].toInt() and 0xff) shl 8) or (bytes[i + 1].toInt() and 0xff)
                        i += 2
                    }
                }
                require(i + length <= bytes.size) {
                    "TLV truncated: tag 0x${tag.toString(16)} claims $length bytes, ${bytes.size - i} remain"
                }
                result.add(Tlv(tag, bytes.copyOfRange(i, i + length)))
                i += length
            }
            return result
        }
    }
}
