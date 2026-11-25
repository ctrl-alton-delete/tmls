//! tmls for Android: the Kotlin-facing API over `client-core` (spec ARCH-2).
//!
//! M0 has only enough to prove the chain Gradle → cargo-ndk → UniFFI → Kotlin works on a
//! device. The real API (plan M3: `Core`, polling, argv builders) grows here; `client-core`
//! itself stays Android-free.

uniffi::setup_scaffolding!();

/// What the app shows as the core's version: `core X.Y.Z`.
#[uniffi::export]
pub fn version() -> String {
    format!("core {}", env!("CARGO_PKG_VERSION"))
}

/// How many herdr agent-detection rule files `client-core` has built in, and the herdr commit
/// they come from: a cheap call that only works if `client-core` linked into the library.
#[uniffi::export]
pub fn agent_rules() -> AgentRules {
    AgentRules {
        count: client_core::detect::RULE_FILES.len() as u32,
        herdr_commit: client_core::detect::HERDR_COMMIT.to_string(),
    }
}

#[derive(uniffi::Record, Debug, PartialEq, Eq)]
pub struct AgentRules {
    pub count: u32,
    pub herdr_commit: String,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn version_names_the_crate_version() {
        assert_eq!(version(), format!("core {}", env!("CARGO_PKG_VERSION")));
    }

    #[test]
    fn agent_rules_come_from_client_core() {
        let rules = agent_rules();
        assert!(rules.count > 0);
        assert_eq!(rules.herdr_commit.len(), 40);
    }
}
