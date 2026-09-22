package com.rhnxdev.hzplayer.core.util

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.util.Log
import com.rhnxdev.hzplayer.R
import com.rhnxdev.hzplayer.domain.model.StorageKind
import java.io.File

private const val TAG = "StorageVolumes"

/**
 * Display label per storage volume, keyed by mount path, so a volume reads the
 * same in the File Browser's roots list and in its breadcrumbs.
 */
fun storageVolumeLabels(context: Context): Map<String, String> {
    val labels = linkedMapOf(
        Environment.getExternalStorageDirectory().absolutePath to
            context.getString(R.string.internal_storage),
    )
    val manager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager ?: return labels
    val usbAttached = context.hasUsbMassStorage()
    manager.storageVolumes
        .filterNot { it.isPrimary }
        .mapNotNull { volume -> volume.mountDirectory()?.let { volume to it } }
        .forEachIndexed { index, (volume, dir) ->
            labels[dir.absolutePath] = storageVolumeLabel(context, volume, dir, index, usbAttached)
        }
    return labels
}

/**
 * Display label for [volume]. Prefers the volume's own name — the FAT/exFAT label
 * or vendor string the framework surfaces via [StorageVolume.getDescription], the
 * same text Android's Settings/Files apps show (e.g. "SANDISK", "KINGSTON") —
 * falling back to a generic "USB Storage"/"SD Card" string only when the
 * framework has no real name for the drive, and to "Storage N" as a last resort.
 *
 * @param dir — mount directory of [volume].
 * @param index — position in the volume list, used only by the "Storage N" fallback.
 * @param usbAttached — whether a USB mass-storage device is currently connected;
 *   pass the cached result of [hasUsbMassStorage] to avoid re-querying per volume.
 */
fun storageVolumeLabel(
    context: Context,
    volume: StorageVolume,
    dir: File,
    index: Int,
    usbAttached: Boolean = context.hasUsbMassStorage(),
): String {
    val kind = classifyVolume(context, volume, dir, usbAttached)
    if (kind == StorageKind.INTERNAL) return context.getString(R.string.internal_storage)

    val realName = volume.realName(context)
    if (realName != null) return realName

    return when (kind) {
        StorageKind.USB -> context.getString(R.string.usb_storage)
        StorageKind.SD_CARD -> context.getString(R.string.sd_card)
        StorageKind.INTERNAL -> context.getString(R.string.internal_storage)
        StorageKind.OTHER -> context.getString(R.string.storage_numbered, index + 1)
    }
}

/**
 * The volume's own name, when the framework has one — usually the FAT/exFAT
 * volume label or vendor string. Returns null for blank descriptions and for the
 * generic placeholders the framework falls back to when a drive has no real
 * label of its own, so callers can fall back to a "USB Storage"/"SD Card" string
 * instead of showing that placeholder verbatim.
 */
private fun StorageVolume.realName(context: Context): String? {
    val description = runCatching { getDescription(context) }.getOrNull()?.trim()
    if (description.isNullOrEmpty()) return null
    val generic = setOf(
        "usb drive", "usb storage", "usb device", "external storage",
        "external sd card", "sd card", "portable drive", "removable storage",
    )
    if (description.lowercase() in generic) return null
    return description
}

/**
 * Classify a removable [volume] as USB or SD card.
 *
 * The `isRemovable` flag is reliable but does not say *which* removable medium a
 * volume is, so classification prefers the framework's own answer: every
 * StorageVolume wraps a hidden VolumeInfo → DiskInfo that exposes isUsb()/isSd()
 * (the exact calls the system Settings app uses). We read those by reflection.
 * Only when reflection is unavailable do we fall back to weaker signals — the
 * description text, the mount path / block-device node, and a connected USB
 * mass-storage device — treating an otherwise-unidentified removable volume as
 * an SD card.
 */
fun classifyVolume(
    context: Context,
    volume: StorageVolume,
    dir: File,
    usbAttached: Boolean,
): StorageKind {
    Log.d(
        TAG,
        "volume ${dir.absolutePath}: removable=${volume.isRemovable} primary=${volume.isPrimary} " +
            "state=${volume.state} uuid=${volume.uuid} " +
            "desc=${runCatching { volume.getDescription(context) }.getOrNull()}",
    )
    if (volume.isPrimary) return StorageKind.INTERNAL
    if (!volume.isRemovable) return StorageKind.OTHER

    // 1. Authoritative: ask the framework's DiskInfo directly.
    diskKind(volume)?.let {
        Log.d(TAG, "classifyVolume(${dir.absolutePath}): DiskInfo → $it")
        return it
    }

    // 2. Fallback heuristics when the hidden API is unreachable.
    val description = runCatching { volume.getDescription(context) }.getOrNull()?.lowercase().orEmpty()
    val path = dir.absolutePath.lowercase()
    val node = dir.name.lowercase()
    val looksUsb = description.contains("usb") || description.contains("otg") ||
        path.contains("usb") || path.contains("otg") ||
        Regex("""(^|/)sd[a-z]\d*$""").containsMatchIn(node)

    val kind = when {
        looksUsb -> StorageKind.USB
        // A connected USB mass-storage device wins even when the mount node happens
        // to look like an SD FAT serial ("XXXX-XXXX") — many OTG drives mount that
        // way too, so the serial pattern alone can't tell USB and SD apart.
        usbAttached -> StorageKind.USB
        else -> StorageKind.SD_CARD
    }
    Log.d(
        TAG,
        "classifyVolume(${dir.absolutePath}): heuristic → $kind " +
            "(desc='$description', node='$node', usbAttached=$usbAttached)",
    )
    return kind
}

/**
 * The volume's disk kind via the hidden VolumeInfo/DiskInfo chain that Android's
 * own storage UI relies on: StorageVolume.getDisk()?.isUsb()/isSd(). Returns null
 * when any link in the chain is missing (blocked reflection, no backing disk).
 */
private fun diskKind(volume: StorageVolume): StorageKind? = try {
    // StorageVolume → VolumeInfo (mVolumeInfo field, or the public-ish getVolumeInfo()).
    val volumeInfo = runCatching {
        StorageVolume::class.java.getMethod("getVolumeInfo").invoke(volume)
    }.getOrNull() ?: runCatching {
        StorageVolume::class.java.getDeclaredField("mVolumeInfo")
            .apply { isAccessible = true }
            .get(volume)
    }.getOrNull()

    val disk = volumeInfo?.let {
        runCatching { it.javaClass.getMethod("getDisk").invoke(it) }.getOrNull()
    } ?: runCatching {
        // Some builds expose DiskInfo straight off StorageVolume.
        StorageVolume::class.java.getMethod("getDisk").invoke(volume)
    }.getOrNull()

    when {
        disk == null -> null
        runCatching { disk.javaClass.getMethod("isUsb").invoke(disk) as? Boolean }.getOrNull() == true ->
            StorageKind.USB
        runCatching { disk.javaClass.getMethod("isSd").invoke(disk) as? Boolean }.getOrNull() == true ->
            StorageKind.SD_CARD
        else -> null
    }
} catch (t: Throwable) {
    Log.d(TAG, "diskKind reflection unavailable: ${t.message}")
    null
}

/** True when a USB mass-storage class device is currently attached over the host port. */
fun Context.hasUsbMassStorage(): Boolean {
    val manager = getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
    return runCatching {
        manager.deviceList.values.any { device ->
            (0 until device.interfaceCount).any {
                device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE
            }
        }
    }.getOrDefault(false)
}

/**
 * Label for a /storage mount that [storageVolumeLabel] never saw — some OEMs
 * mount SD/OTG volumes StorageManager omits, leaving no removable flag. USB is
 * then matched by mount name or a connected USB mass-storage device, SD card by
 * capacity. Same wording as [storageVolumeLabel] so both paths label a volume
 * identically.
 *
 * @param index — position in the fallback list, used only by the "Storage N" case.
 * @param usbAttached — whether a USB mass-storage device is currently connected;
 *   pass the cached result of [hasUsbMassStorage] to avoid re-querying per volume.
 */
fun fallbackVolumeLabel(
    context: Context,
    dir: File,
    index: Int,
    usbAttached: Boolean = context.hasUsbMassStorage(),
): String = when (fallbackVolumeKind(dir, usbAttached)) {
    StorageKind.USB -> context.getString(R.string.usb_storage)
    StorageKind.SD_CARD -> context.getString(R.string.sd_card)
    else -> context.getString(R.string.storage_numbered, index + 1)
}

/**
 * Best-effort kind for a /storage mount with no StorageVolume metadata.
 *
 * @param usbAttached — a connected USB mass-storage device wins over the capacity
 *   guess, since an unlabelled OTG drive otherwise falls through to "SD card"
 *   purely for being bigger than 1 GB.
 */
fun fallbackVolumeKind(dir: File, usbAttached: Boolean): StorageKind {
    val node = dir.name.lowercase()
    return when {
        node.contains("usb") || node.contains("otg") ||
            Regex("""^sd[a-z]\d*$""").matches(node) -> StorageKind.USB
        usbAttached -> StorageKind.USB
        // Volumes above 1 GB are treated as SD cards; smaller ones are internal partitions.
        dir.totalSpace > 1_000_000_000L -> StorageKind.SD_CARD
        else -> StorageKind.OTHER
    }
}

/**
 * The mount directory of a [StorageVolume] as a [File]. Uses the public
 * getDirectory() on API 30+, falling back to the hidden getPathFile()/getPath()
 * accessors (present since the platform added StorageVolume) on API 28-29.
 */
fun StorageVolume.mountDirectory(): File? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        directory?.let { return it }
    }
    return try {
        val pathFile = StorageVolume::class.java.getMethod("getPathFile").invoke(this) as? File
        pathFile ?: (StorageVolume::class.java.getMethod("getPath").invoke(this) as? String)
            ?.let { File(it) }
    } catch (_: Exception) {
        null
    }
}
