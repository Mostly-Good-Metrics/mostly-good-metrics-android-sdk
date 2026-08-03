package com.mostlygoodmetrics.sdk

import java.security.MessageDigest

/**
 * How the SDK assigns experiment variants.
 */
enum class MGMExperimentMode {
    /**
     * Variants are assigned by the server via `GET /v1/experiments`.
     * The SDK never buckets locally. This is the default.
     */
    SERVER,

    /**
     * Variants are bucketed on device from experiment configs
     * (`GET /v1/experiments/configs`, or inline configs supplied via
     * [MGMConfiguration.Builder.localExperiments]). No per-user assignment
     * request ever leaves the device.
     */
    LOCAL
}

/**
 * An experiment definition used for local (on-device) bucketing.
 *
 * @property id The experiment UUID (the bucketing key, stable across renames)
 * @property name The experiment name used with [MostlyGoodMetrics.getVariant]
 * @property variants The ordered list of variant names
 */
data class MGMExperimentConfig(
    val id: String,
    val name: String,
    val variants: List<String>
)

/**
 * Deterministic on-device bucketing for [MGMExperimentMode.LOCAL].
 *
 * Matches the server's canonical algorithm exactly:
 * `bucket = first 8 bytes of SHA-256(utf8("<experiment_uuid>:<user_id>"))`
 * interpreted as a big-endian *unsigned* 64-bit integer, then
 * `variant = variants[bucket % variants.size]`.
 */
internal object LocalExperimentBucketing {

    /**
     * Compute the unsigned 64-bit bucket for a (experiment, user) pair.
     */
    fun bucket(experimentId: String, userId: String): ULong {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$experimentId:$userId".toByteArray(Charsets.UTF_8))
        // Big-endian unsigned interpretation of the first 8 bytes. ULong keeps
        // the arithmetic unsigned — a plain Long would go negative when the
        // top bit of the hash is set and corrupt the modulo below.
        var value = 0UL
        for (i in 0 until 8) {
            value = (value shl 8) or digest[i].toUByte().toULong()
        }
        return value
    }

    /**
     * Deterministically pick the variant for a user, or null when the
     * experiment has no variants.
     */
    fun variantFor(config: MGMExperimentConfig, userId: String): String? {
        if (config.variants.isEmpty()) return null
        val index = (bucket(config.id, userId) % config.variants.size.toULong()).toInt()
        return config.variants[index]
    }
}
