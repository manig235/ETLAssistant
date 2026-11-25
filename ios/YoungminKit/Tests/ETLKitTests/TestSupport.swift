import Foundation
@testable import ETLKit

/// `Instant.parse`.
func instant(_ s: String) -> Date { EtlLogic.parseInstant(s)! }

/// `Instant.toString()` (초 단위, UTC).
func isoString(_ d: Date) -> String {
    let f = ISO8601DateFormatter()
    f.formatOptions = [.withInternetDateTime]
    f.timeZone = TimeZone(identifier: "UTC")
    return f.string(from: d)
}

/// KST 시각 문자열(`2026-11-20T23:30:00`)을 Date로.
func kst(_ s: String) -> Date { EtlLogic.parseInstant(s + "+09:00")! }

func jsonObject(_ s: String) -> JSONObject { try! JSONObject(s) }
func jsonArray(_ s: String) -> JSONArray { try! JSONArray(s) }
