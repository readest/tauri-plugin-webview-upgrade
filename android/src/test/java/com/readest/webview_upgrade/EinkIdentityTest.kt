package com.readest.webview_upgrade

// Mirrors the unit-test table of readest/readest's eink_identity.rs — the
// Kotlin port must agree with the Rust detection on every case (keep in
// sync).
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EinkIdentityTest {
    /// HANVON Clear7 reports the SoC vendor as manufacturer; brand must still match.
    @Test
    fun detects_hanvon_when_manufacturer_is_soc_vendor() {
        assertTrue(isEinkIdentity("qualcomm", "hanvon", "clear7", "bengal_515"))
    }

    /// Plain e-ink-only brands match on manufacturer or brand alone.
    @Test
    fun detects_regular_eink_brands() {
        assertTrue(isEinkIdentity("onyx", "boox", "palma", "musu"))
        assertTrue(isEinkIdentity("", "tolino", "tab7", "ntx_6"))
        assertTrue(isEinkIdentity("amazon", "amazon", "kindle paperwhite", "walleye"))
    }

    /// Mixed-lineup brands must not match their non-e-ink devices: Amazon
    /// Fire tablets are LCD, the Galaxy A9 is an AMOLED phone, and the Huawei
    /// nova line only shares its name with the BOOX Nova.
    @Test
    fun rejects_non_eink_devices_of_matched_brands() {
        assertFalse(isEinkIdentity("amazon", "amazon", "kfonki", "firehd8"))
        assertFalse(isEinkIdentity("samsung", "samsung", "sm-a920f", "a920f"))
        assertFalse(isEinkIdentity("someoem", "somebrand", "galaxy a9", "a920f"))
        assertFalse(isEinkIdentity("huawei", "huawei", "nam-lx9", "nova12"))
    }

    /// Ambiguous model tokens still count when a known e-ink manufacturer
    /// matched: the Hisense A9 is detected through the gated path.
    @Test
    fun ambiguous_models_count_for_gated_vendors() {
        assertTrue(isEinkIdentity("hisense", "hisense", "a9", "a9"))
    }

    /// An unknown OEM still matches when the model carries a known e-ink name.
    @Test
    fun detects_by_model_when_no_brand_matches() {
        assertTrue(isEinkIdentity("someoem", "somebrand", "inknote_a5pro", "xxx"))
    }

    /// Mixed-lineup brands (Xiaomi, Hisense) only match with an e-ink model.
    @Test
    fun phone_brands_need_an_eink_model() {
        assertFalse(isEinkIdentity("xiaomi", "redmi", "m2012k11c", "venus"))
        assertTrue(isEinkIdentity("xiaomi", "xiaomi", "inkpalm", "lapis"))
        assertFalse(isEinkIdentity("hisense", "hisense", "dub-al00a", "wayne"))
        assertTrue(isEinkIdentity("hisense", "hisense", "hlj-bd60", "a5pro"))
    }

    /// Ordinary phones must not match, even with e-ink-ish substrings elsewhere.
    @Test
    fun rejects_plain_phone() {
        assertFalse(isEinkIdentity("google", "google", "pixel 9", "tegu"))
        assertFalse(isEinkIdentity("samsung", "samsung", "sm-g9910", "r8q"))
        // Manufacturer tokens must not match against model/device strings
        assertFalse(isEinkIdentity("qualcomm", "samsung", "sm-boox99", "r8q"))
    }
}
