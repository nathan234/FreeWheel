//! Port of `BegodeModelCatalog.kt` — model-specific defaults for the
//! Gotway/Begode protocol family.

use std::sync::OnceLock;

#[derive(Debug, Clone, PartialEq)]
pub struct BegodeModelProfile {
    pub display_name: &'static str,
    pub brand: &'static str,
    pub full_voltage_v: f64,
    pub low_voltage_v: Option<f64>,
    pub empty_voltage_v: Option<f64>,
    pub no_load_speed_kmh: Option<f64>,
    pub smart_bms_count: i32,
}

struct Entry {
    profile: BegodeModelProfile,
    /// Raw (un-normalized) aliases; display name is added at build time.
    raw_aliases: Vec<&'static str>,
    firmware_signatures: Vec<String>,
}

struct Built {
    profile: BegodeModelProfile,
    aliases: Vec<String>,
    firmware_signatures: Vec<String>,
}

fn e(display_name: &'static str, full_voltage_v: f64) -> Entry {
    Entry {
        profile: BegodeModelProfile {
            display_name,
            brand: "Begode",
            full_voltage_v,
            low_voltage_v: None,
            empty_voltage_v: None,
            no_load_speed_kmh: None,
            smart_bms_count: 0,
        },
        raw_aliases: Vec::new(),
        firmware_signatures: Vec::new(),
    }
}

impl Entry {
    fn brand(mut self, b: &'static str) -> Self {
        self.profile.brand = b;
        self
    }
    fn aliases(mut self, a: &[&'static str]) -> Self {
        self.raw_aliases.extend_from_slice(a);
        self
    }
    fn low(mut self, v: f64) -> Self {
        self.profile.low_voltage_v = Some(v);
        self
    }
    fn empty(mut self, v: f64) -> Self {
        self.profile.empty_voltage_v = Some(v);
        self
    }
    fn no_load(mut self, v: f64) -> Self {
        self.profile.no_load_speed_kmh = Some(v);
        self
    }
    fn bms(mut self, n: i32) -> Self {
        self.profile.smart_bms_count = n;
        self
    }
    /// Register GW/JL/CF firmware signatures for the given 5-digit codes.
    fn gw_cf(mut self, codes: &[&str]) -> Self {
        for code in codes {
            self.firmware_signatures.push(format!("GW:{code}"));
            self.firmware_signatures.push(format!("JL:{code}"));
            self.firmware_signatures.push(format!("CF:{code}"));
        }
        self
    }
    /// Register a raw firmware signature (e.g. "JN:20122").
    fn sig(mut self, s: &str) -> Self {
        self.firmware_signatures.push(s.to_string());
        self
    }
}

fn entries() -> &'static [Built] {
    static ENTRIES: OnceLock<Vec<Built>> = OnceLock::new();
    ENTRIES.get_or_init(|| {
        let raw = vec![
            e("A1", 42.0).empty(32.0),
            e("A2", 84.0).empty(62.0).no_load(52.0).gw_cf(&["15110"]),
            e("A5", 84.0).empty(62.0).no_load(63.0),
            e("ACM 16", 67.2),
            e("ACM S+", 84.0),
            e("Blitz", 134.4).low(106.0).empty(99.0).no_load(150.0).bms(2).gw_cf(&["20351"]),
            e("Blitz Pro", 168.0).aliases(&["Blitz PRO"]).low(132.0).empty(124.0).no_load(155.0).bms(2),
            e("C8", 84.0).empty(62.0).no_load(52.0),
            e("Commander C30", 100.8).brand("Extreme Bull").empty(74.0).no_load(97.0),
            e("Commander C38", 100.8).brand("Extreme Bull").empty(74.0).no_load(79.0),
            e("Commander GT", 134.4).brand("Extreme Bull").empty(97.6).no_load(112.0),
            e("Commander Max", 168.0).aliases(&["MAX"]).brand("Extreme Bull").low(120.0).empty(116.0).no_load(170.0),
            e("Commander Mini", 134.4).aliases(&["Commander mini"]).brand("Extreme Bull").empty(100.0).no_load(107.0),
            e("Commander Mini Pro", 134.4).aliases(&["Commander Mini PRO"]).brand("Extreme Bull").empty(100.0).no_load(107.0),
            e("Commander Pro", 134.4).brand("Extreme Bull").empty(97.6).no_load(112.0).sig("JN:20122"),
            e("Commander Pro 50S", 134.4).aliases(&["CommanderPRO50s"]).brand("Extreme Bull").empty(97.6).no_load(120.0),
            e("EX", 100.8).empty(78.0).no_load(83.0),
            e("EX.N C30", 100.8).empty(72.0).no_load(97.0).gw_cf(&["20020"]),
            e("EX.N C38", 100.8).empty(72.0).no_load(80.0).gw_cf(&["20120"]),
            e("EX20S C30", 100.8).low(78.0).empty(76.0).no_load(86.0).gw_cf(&["20030"]),
            e("EX20S C38", 100.8).low(78.0).empty(76.0).no_load(79.0).gw_cf(&["20130"]),
            e("EX30 C40", 134.4).aliases(&["EX30"]).empty(99.2).no_load(120.0).gw_cf(&["20250"]),
            e("Extreme", 134.4).aliases(&["EXTREME"]).empty(99.0).no_load(108.0).bms(2).gw_cf(&["18250"]),
            e("ET Max", 168.0).aliases(&["ET MAX"]).empty(124.0).no_load(180.0).bms(2).gw_cf(&["20260"]),
            e("Falcon", 100.8).empty(72.0).no_load(67.0).gw_cf(&["16210"]),
            e("Griffin", 151.2).brand("Extreme Bull").low(118.8).empty(111.6).no_load(147.0).bms(2),
            e("GT Pro", 168.0).aliases(&["GT PRO"]).brand("Extreme Bull").empty(124.0).no_load(180.0).sig("JN:20260"),
            e("Hero C30", 100.8).low(79.0).empty(78.0).no_load(97.0).gw_cf(&["20022"]),
            e("Hero C38", 100.8).low(79.0).empty(78.0).no_load(79.0).gw_cf(&["20122"]),
            e("Master", 134.4).low(106.0).empty(104.0).no_load(112.0)
                .gw_cf(&["20140", "20145", "20148", "20149", "20150", "20151"]),
            e("Master Pro", 134.4).aliases(&["Master PRO", "Master pro 3", "Master PRO 3"])
                .empty(99.2).no_load(122.0).gw_cf(&["23040", "23250"]),
            e("Master X", 134.4).empty(99.2).no_load(122.0).gw_cf(&["23041"]),
            e("Monster (84 V)", 84.0).aliases(&["Monster 84V"]).no_load(74.0),
            e("Monster (100 V)", 100.8).aliases(&["Monster 100V"]).empty(78.0).no_load(93.0),
            e("Monster Pro", 100.8).empty(72.0).no_load(106.0).gw_cf(&["24020"]),
            e("Monster V2 (84 V)", 84.0).aliases(&["Monster V2 84V"]).no_load(74.0),
            e("Monster V2 (100 V)", 100.8).aliases(&["Monster V2 100V"]).no_load(93.0),
            e("Monster V3 (84 V)", 84.0).aliases(&["Monster V3 84V"]).no_load(74.0),
            e("Monster V3 (100 V)", 100.8).aliases(&["Monster V3 100V"]).no_load(93.0),
            e("Msuper X (84 V)", 84.0).aliases(&["MSuper X 84V"]).no_load(76.0).gw_cf(&["19310"]),
            e("Msuper X (100 V)", 100.8).aliases(&["MSuper X 100V"]).no_load(95.0).gw_cf(&["19320"]),
            e("Mten 4", 84.0).aliases(&["MTEN4"]).empty(62.0).no_load(56.0).gw_cf(&["10110"]),
            e("Mten 5", 84.0).aliases(&["MTEN5"]).empty(62.0).no_load(71.0).gw_cf(&["12110"]),
            e("Mten Mini", 42.0).aliases(&["Mten mini"]).empty(31.0).no_load(30.0).gw_cf(&["11210"]),
            e("Nikola", 84.0).no_load(70.0),
            e("Nikola Plus", 100.8).empty(72.0).no_load(82.0).gw_cf(&["17020"]),
            e("Panther", 168.0).low(120.0).empty(116.0).no_load(170.0).bms(2),
            e("RACE", 210.0).low(165.0).empty(155.0).no_load(165.0).bms(2),
            e("Rocket", 168.0).aliases(&["ROCKET"]).brand("Extreme Bull").low(124.0).empty(120.0),
            e("RS C30", 100.8).empty(78.0).no_load(97.0).gw_cf(&["19020", "19030", "19040"]),
            e("RS C38", 100.8).empty(78.0).no_load(79.0).gw_cf(&["19120", "19130"]),
            e("T4", 100.8).low(79.0).empty(72.0).no_load(78.0).gw_cf(&["16121", "16122"]),
            e("T4 Pro", 100.8).empty(72.0).no_load(78.0).gw_cf(&["16125"]),
            e("Tesla (67 V)", 67.2).aliases(&["Tesla 67V"]),
            e("Tesla (84 V)", 84.0).aliases(&["Tesla 84V"]).no_load(68.0),
            e("Tesla 2", 84.0).no_load(68.0),
            e("Tesla T3", 84.0).empty(65.0).no_load(68.0).gw_cf(&["16010", "16110"]),
            e("X-Men C30", 100.8).brand("Extreme Bull").empty(78.0).no_load(97.0),
            e("X-Men C38", 100.8).brand("Extreme Bull").empty(78.0).no_load(79.0),
            e("X-Way (134 V)", 134.4).aliases(&["XWAY-134"]).low(105.6).empty(99.2).bms(2),
            e("X-Way (168 V)", 168.0).aliases(&["XWAY-168"]).low(132.0).empty(124.0).bms(2),
        ];
        raw.into_iter()
            .map(|entry| {
                let mut aliases: Vec<String> =
                    entry.raw_aliases.iter().map(|a| normalize(a)).collect();
                aliases.push(normalize(entry.profile.display_name));
                aliases.dedup();
                Built {
                    profile: entry.profile,
                    aliases,
                    firmware_signatures: entry.firmware_signatures,
                }
            })
            .collect()
    })
}

pub fn match_profile(model: &str, firmware: &str) -> Option<BegodeModelProfile> {
    let normalized_model = normalize(model);
    if !normalized_model.is_empty() {
        if let Some(found) = entries()
            .iter()
            .find(|entry| entry.aliases.contains(&normalized_model))
        {
            return Some(found.profile.clone());
        }
    }

    let signature = firmware_signature(firmware)?;
    entries()
        .iter()
        .find(|entry| entry.firmware_signatures.contains(&signature))
        .map(|entry| entry.profile.clone())
}

fn normalize(value: &str) -> String {
    let lowered = value.trim().to_lowercase();
    let replaced: String = lowered
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() { c } else { ' ' })
        .collect();
    replaced.split_whitespace().collect::<Vec<_>>().join(" ")
}

fn firmware_signature(firmware: &str) -> Option<String> {
    let value = firmware.trim().to_uppercase();
    if value.chars().count() < 7 {
        return None;
    }
    let prefix: String = value.chars().take(2).collect();
    if !["GW", "JL", "JN", "CF", "BF"].contains(&prefix.as_str()) {
        return None;
    }
    let code: String = value.chars().skip(2).take(5).collect();
    if code.len() != 5 || code.chars().any(|c| !c.is_ascii_digit()) {
        return None;
    }
    Some(format!("{prefix}:{code}"))
}
