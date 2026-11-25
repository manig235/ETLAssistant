import Foundation
import Testing
@testable import ETLKit

/// DayDate / Weekday (LocalDate / DayOfWeek 대체)와 org.json 대체 JSON의 기본 동작.
struct TimeAndJSONTests {

    @Test func dayDateBasics() throws {
        let d = DayDate.of(2026, 11, 19)
        #expect(d.dayOfWeek == .thursday)
        #expect(d.dayOfWeek.label == "목")
        #expect(d.description == "2026-11-19")
        #expect(DayDate(iso: "2026-11-19") == d)
        #expect(DayDate(iso: "2026-02-30") == nil)
        #expect(DayDate(iso: "2026-1-5") == nil)
        #expect(DayDate.of(1970, 1, 1).epochDay == 0)
        #expect(DayDate(epochDay: d.epochDay) == d)
        #expect(DayDate.of(2024, 2, 28).plusDays(1) == DayDate.of(2024, 2, 29))
        #expect(DayDate.of(2026, 12, 31).plusDays(1) == DayDate.of(2027, 1, 1))
        #expect(DayDate.of(2026, 1, 31).plusMonths(1) == DayDate.of(2026, 2, 28))
        #expect(DayDate.of(2026, 1, 15).minusMonths(2) == DayDate.of(2025, 11, 15))
        #expect(d.previousOrSame(.monday) == DayDate.of(2026, 11, 16))
        #expect(d.nextOrSame(.thursday) == d)
        #expect(d.next(.thursday) == DayDate.of(2026, 11, 26))
        #expect(DayDate.of(2026, 2, 1).lengthOfMonth == 28)
        #expect(DayDate.of(1969, 12, 31).epochDay == -1)
        #expect(DayDate(epochDay: -1) == DayDate.of(1969, 12, 31))
        // KST 경계: UTC 15:00 = 다음 날 KST 00:00
        #expect(DayDate(instant("2026-11-22T14:59:59Z")) == DayDate.of(2026, 11, 22))
        #expect(DayDate(instant("2026-11-22T15:00:00Z")) == DayDate.of(2026, 11, 23))
        #expect(DayDate.of(2026, 11, 23).startOfDay == instant("2026-11-22T15:00:00Z"))
        #expect(DayDate.of(2026, 11, 23).atTime(9, 30) == instant("2026-11-23T00:30:00Z"))
        let data = try JSONEncoder().encode([d])
        #expect(String(decoding: data, as: UTF8.self) == "[\"2026-11-19\"]")
        #expect(try JSONDecoder().decode([DayDate].self, from: data) == [d])
        #expect(Weekday.of(8) == .monday)
        #expect(Weekday.sunday.plus(1) == .monday)
        #expect(Weekday.fromLabel("일") == .sunday)
    }

    @Test func jsonKeepsOrderAndOrgJsonCoercions() throws {
        let o = try JSONObject(#"{"b":1,"a":"2","n":null,"d":2.5,"t":"true","s":"x\/yé"}"#)
        #expect(o.keys() == ["b", "a", "n", "d", "t", "s"])
        #expect(o.optLong("a") == 2)
        #expect(o.optString("b") == "1")
        #expect(o.optString("n") == "null")
        #expect(o.str("n") == nil)
        #expect(o.isNull("n") && o.has("n"))
        #expect(o.optBoolean("t"))
        #expect(o.optInt("d") == 2)
        #expect(o.optString("s") == "x/yé")
        #expect(o.toString() == #"{"b":1,"a":"2","n":null,"d":2.5,"t":"true","s":"x/yé"}"#)
        o.put("b", nil)
        #expect(!o.has("b"))
        #expect(JSONObject().put("x", 3.0).toString() == #"{"x":3}"#)
        #expect(JSONObject().put("q", "a\"b\n").toString() == #"{"q":"a\"b\n"}"#)
        #expect(throws: JSONException.self) { try JSONObject("[1]") }
        #expect(throws: JSONException.self) { try JSONArray("{") }
    }
}
