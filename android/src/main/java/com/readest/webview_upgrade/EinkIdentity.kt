package com.readest.webview_upgrade

// Pure e-ink device identity matching.
//
// Kotlin port of the e-ink identity whitelist in readest/readest
// (apps/readest-app/src-tauri/src/eink_identity.rs, introduced in #6392).
// The Rust table drives the host app's e-ink device paths; this copy drives
// this plugin's night-mode pin — keep the two in sync (the mirrored test
// tables in both repos are the drift guard).
//
// All four inputs are expected to be already lowercased by the caller.
internal fun isEinkIdentity(
    manufacturer: String,
    brand: String,
    model: String,
    device: String,
): Boolean {
    val brands = "$manufacturer $brand"

    // Some readers report the SoC vendor (e.g. QUALCOMM) as the manufacturer
    // and carry the reader brand only in ro.product.brand, so the
    // manufacturer/brand whitelist is matched against both fields joined as
    // one string.
    for (vendor in EINK_MANUFACTURERS) {
        if (brands.contains(vendor)) {
            // Special case for makers that ship both e-ink and non-e-ink
            // devices: the model must confirm.
            if (vendor == "hisense" || vendor == "xiaomi" || vendor == "amazon") {
                if (EINK_MODELS.any { model.contains(it) || device.contains(it) }) return true
            } else {
                return true
            }
        }
    }

    // An unknown OEM still matches when the model carries a known e-ink
    // name, minus the ambiguous tokens that also name mainstream phones and
    // are therefore only trusted when a known e-ink manufacturer matched.
    return EINK_MODELS
        .filter { it !in EINK_AMBIGUOUS_MODELS }
        .any { model.contains(it) || device.contains(it) }
}

// Known e-ink device manufacturers and brands (case-insensitive matching).
// Mirrors eink_identity.rs EINK_MANUFACTURERS — keep in sync.
internal val EINK_MANUFACTURERS = listOf(
    "onyx",       // BOOX devices
    "boox",       // BOOX devices (alternate)
    "amazon",     // Kindle devices (model-gated: Fire tablets are LCD)
    "kobo",       // Kobo e-readers
    "remarkable", // reMarkable tablets
    "pocketbook", // PocketBook e-readers
    "boyue",      // Boyue/Likebook devices
    "likebook",   // Likebook devices
    "dasung",     // Dasung e-ink monitors
    "bigme",      // Bigme e-readers
    "hisense",    // Hisense e-ink phones (A5, A7, etc.)
    "hanvon",     // Hanvon e-readers
    "tolino",     // Tolino e-readers
    "bookeen",    // Bookeen e-readers
    "supernote",  // Supernote devices
    "mobiscribe", // Mobiscribe e-readers
    "xiaomi",     // Xiaomi InkPalm (needs model check)
    "meebook",    // Meebook e-readers
    "ireader",    // iReader e-readers
)

// Known e-ink device models (for makers that also build non-e-ink devices).
// Mirrors eink_identity.rs EINK_MODELS — keep in sync.
internal val EINK_MODELS = listOf(
    "kindle",
    "a5pro",
    "a7cc", // Hisense e-ink models
    "a7e",
    "a9",
    "inkpalm", // Xiaomi InkPalm
    "eink",
    "e-ink",
    "paper",
    "note air",
    "note2",
    "note3",
    "note5",
    "nova",
    "poke",
    "leaf",
    "page",
    "tab ultra",
    "max lumi",
)

// Model tokens too short or too collide-prone to trust from an unknown OEM:
// "a9" also matches the Samsung Galaxy A9 line and "nova" the Huawei nova
// line. They only count when a known e-ink manufacturer matched, which is
// how their real devices (Hisense A9, BOOX Nova) are identified anyway.
// Mirrors eink_identity.rs EINK_AMBIGUOUS_MODELS — keep in sync.
private val EINK_AMBIGUOUS_MODELS = listOf("a9", "nova")
