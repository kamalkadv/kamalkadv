#
# KioskOS product config.
#
# Cara pakai: di makefile produk device Anda (mis. device/<vendor>/<device>/<device>.mk)
# tambahkan baris:
#
#   $(call inherit-product, vendor/kioskos/kioskos.mk)
#

# Aplikasi sistem lock/kiosk (privileged, ditandatangani dengan platform key).
PRODUCT_PACKAGES += \
    KioskLock

# Allowlist izin privileged untuk KioskLock.
PRODUCT_COPY_FILES += \
    vendor/kioskos/permissions/privapp-permissions-kiosklock.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/permissions/privapp-permissions-kiosklock.xml

# Overlay framework: jadikan KioskLock pemilik "persistent data block" (partisi FRP),
# satu-satunya area yang TIDAK dihapus oleh factory reset dan bisa ditulis aplikasi.
PRODUCT_PACKAGE_OVERLAYS += vendor/kioskos/overlay

# Pastikan device memang punya partisi FRP. Nilai ini biasanya sudah di-set oleh
# device tree (lihat fstab). Contoh:
#   PRODUCT_VENDOR_PROPERTIES += ro.frp.pst=/dev/block/bootdevice/by-name/frp
