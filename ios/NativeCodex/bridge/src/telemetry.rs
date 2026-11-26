//! Keeps Codex usage analytics, feedback uploads, and OpenTelemetry exporters off.
//!
//! Pinned Codex (29eb79e) defaults, as used by the in-process app-server:
//! - `analytics.enabled` unset means *enabled*: `AnalyticsEventsClient::new_with_runtime`
//!   creates a delivery queue unless the value is `Some(false)`, and events go to
//!   `{chatgpt_base_url}/codex/analytics-events/events` (analytics/src/client.rs).
//!   The stdio `--analytics-default-enabled` switch never reaches this path.
//! - `feedback.enabled` unset means enabled (core/src/config/mod.rs); it gates the
//!   `feedback/upload` RPC (Sentry) and background Guardian review retention.
//! - `otel.metrics_exporter` unset means Statsig (core/src/config/otel.rs). The
//!   in-process server never calls `otel_init::build_provider`, so no exporter is
//!   built, but the values are pinned here as well so a future path cannot inherit
//!   Codex defaults.
//!
//! These are applied as CLI-layer overrides, which outrank `CODEX_HOME/config.toml`.
//! Request-level `config` maps are merged after CLI overrides, so the bridge also
//! removes telemetry keys from them, and refuses `feedback/upload`.

use serde_json::Value;

/// Top-level config tables that control telemetry. Request overrides may not set them.
const TELEMETRY_TABLES: [&str; 3] = ["analytics", "feedback", "otel"];

/// Methods that would send diagnostics to OpenAI or its vendors.
pub(crate) const BLOCKED_METHODS: [&str; 1] = ["feedback/upload"];

pub(crate) fn overrides() -> Vec<(String, toml::Value)> {
    let off = |key: &str| (key.to_owned(), toml::Value::Boolean(false));
    let none = |key: &str| (key.to_owned(), toml::Value::String("none".into()));
    vec![
        off("analytics.enabled"),
        off("feedback.enabled"),
        none("otel.exporter"),
        none("otel.trace_exporter"),
        none("otel.metrics_exporter"),
        off("otel.log_user_prompt"),
        off("otel.log_agent_responses"),
    ]
}

pub(crate) fn is_blocked(method: &str) -> bool {
    BLOCKED_METHODS.contains(&method)
}

fn is_telemetry_key(key: &str) -> bool {
    let head = key.split('.').next().unwrap_or(key);
    TELEMETRY_TABLES.contains(&head.trim())
}

/// Removes telemetry settings from a JSON-RPC message's `params.config` override map.
pub(crate) fn strip_request_overrides(mut message: Value) -> Value {
    if let Some(config) = message
        .get_mut("params")
        .and_then(|params| params.get_mut("config"))
        .and_then(Value::as_object_mut)
    {
        config.retain(|key, _| !is_telemetry_key(key));
    }
    message
}

#[cfg(test)]
mod tests {
    use super::*;
    use codex_config::types::OtelExporterKind;
    use codex_core::config::ConfigBuilder;
    use serde_json::json;

    /// Config loading is deep; run it on a large stack like the bridge thread does.
    fn load(home: std::path::PathBuf, overrides: Vec<(String, toml::Value)>) -> codex_core::config::Config {
        std::thread::Builder::new()
            .stack_size(16 * 1024 * 1024)
            .spawn(move || {
                tokio::runtime::Builder::new_current_thread()
                    .enable_all()
                    .build()
                    .expect("runtime")
                    .block_on(
                        ConfigBuilder::default()
                            .codex_home(home.clone())
                            .fallback_cwd(Some(home))
                            .cli_overrides(overrides)
                            .build(),
                    )
                    .expect("config")
            })
            .expect("thread")
            .join()
            .expect("config thread")
    }

    fn temp_home(config_toml: &str) -> std::path::PathBuf {
        let home = std::env::temp_dir().join(format!("youngmin-telemetry-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&home).expect("home");
        std::fs::write(home.join("config.toml"), config_toml).expect("config.toml");
        home
    }

    #[test]
    fn codex_defaults_would_enable_telemetry() {
        // Guards the premise: if upstream flips these defaults, revisit this module.
        let home = temp_home("");
        let config = load(home.clone(), Vec::new());
        let _ = std::fs::remove_dir_all(home);
        assert_ne!(config.analytics_enabled, Some(false));
        assert!(config.feedback_enabled);
        assert_eq!(config.otel.metrics_exporter, OtelExporterKind::Statsig);
    }

    #[test]
    fn overrides_disable_all_telemetry_even_if_config_toml_enables_it() {
        let home = temp_home(
            "[analytics]\nenabled = true\n\n[feedback]\nenabled = true\n\n[otel]\nexporter = \"statsig\"\ntrace_exporter = \"statsig\"\nmetrics_exporter = \"statsig\"\nlog_user_prompt = true\n",
        );
        let config = load(home.clone(), overrides());
        let _ = std::fs::remove_dir_all(home);
        assert_eq!(config.analytics_enabled, Some(false));
        assert!(!config.feedback_enabled);
        assert_eq!(config.otel.exporter, OtelExporterKind::None);
        assert_eq!(config.otel.trace_exporter, OtelExporterKind::None);
        assert_eq!(config.otel.metrics_exporter, OtelExporterKind::None);
        assert!(!config.otel.log_user_prompt);
        assert!(!config.otel.log_agent_responses);
    }

    #[test]
    fn request_overrides_cannot_reenable_telemetry() {
        let message = strip_request_overrides(json!({
            "id": 1,
            "method": "thread/start",
            "params": {"model": "m", "config": {
                "analytics.enabled": true,
                "analytics": {"enabled": true},
                "feedback.enabled": true,
                "otel": {"metrics_exporter": "statsig"},
                "otel.exporter": "statsig",
                "model_reasoning_effort": "high",
            }},
        }));
        assert_eq!(message["params"]["config"], json!({"model_reasoning_effort": "high"}));
        assert_eq!(message["params"]["model"], "m");
        // Messages without a config map pass through unchanged.
        let plain = json!({"id": 2, "method": "turn/start", "params": {"threadId": "t"}});
        assert_eq!(strip_request_overrides(plain.clone()), plain);
    }

    #[test]
    fn feedback_upload_is_refused() {
        assert!(is_blocked("feedback/upload"));
        assert!(!is_blocked("thread/start"));
        assert!(!is_blocked("account/read"));
    }

    #[test]
    fn bridge_applies_overrides_and_screens_requests() {
        // The engine start path is not unit-testable without a device runtime, so
        // pin the wiring in lib.rs to the functions tested above.
        let source = include_str!("lib.rs");
        assert_eq!(source.matches("overrides.extend(telemetry::overrides());").count(), 1);
        assert!(source.contains("cli_overrides: overrides,"));
        assert!(source.contains("let value = telemetry::strip_request_overrides(value);"));
        assert!(source.contains("if telemetry::is_blocked(&request.method) =>"));
    }
}
