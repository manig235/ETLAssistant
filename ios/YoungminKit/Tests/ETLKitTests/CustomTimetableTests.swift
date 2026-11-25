import Foundation
import Testing
@testable import ETLKit

/// Kotlin CustomTimetableTest. 시간표 격자(TimetableGridLogic, chat 패키지) 부분은 이 모듈 밖이라 뺐다.
struct CustomTimetableTests {
    typealias T = TimetableLogic
    private let meeting = T.Meeting("월", "09:00", "10:15", " 301-101 ")
    private var custom: T.CourseMeetings { T.CourseMeetings(-42, " 개인 수업 ", [meeting], " 김교수 ") }
    private var catalog: T.Weekly {
        var c = custom
        c.courseId = 42
        c.name = "수강편람 과목"
        return T.Weekly("42", 1234, [c], ["일부 정보 누락"])
    }

    private func with(_ c: T.CourseMeetings, _ f: (inout T.CourseMeetings) -> Void) -> T.CourseMeetings {
        var x = c
        f(&x)
        return x
    }
    private func with(_ m: T.Meeting, _ f: (inout T.Meeting) -> Void) -> T.Meeting {
        var x = m
        f(&x)
        return x
    }

    @Test func validatesRequiredFieldsAndLocalIdentity() {
        #expect(CustomTimetableLogic.validationError(custom) == nil)
        #expect(CustomTimetableLogic.validationError(with(custom) { $0.courseId = 42 }) != nil)
        #expect(CustomTimetableLogic.validationError(with(custom) { $0.name = " " }) != nil)
        #expect(CustomTimetableLogic.validationError(with(custom) { $0.meetings = [] }) != nil)
        #expect(CustomTimetableLogic.validationError(with(custom) { $0.meetings = [with(meeting) { $0.day = "월화" }] }) != nil)
    }

    @Test func rejectsInvalidOrOvernightTimes() {
        for (start, end) in [("oops", "10:00"), ("09:00", "09:00"), ("10:00", "09:00"), ("09:00", "24:00"), ("09:00:30", "10:00")] {
            let m = with(meeting) { $0.start = start; $0.end = end }
            #expect(CustomTimetableLogic.normalize(with(custom) { $0.meetings = [m] }) == nil)
        }
    }

    @Test func normalizesMultipleDaysAndOptionalFields() {
        let result = CustomTimetableLogic.normalize(with(custom) { $0.meetings = [with(meeting) { $0.day = "수" }, meeting, meeting] })!
        #expect(result.name == "개인 수업")
        #expect(result.professor == "김교수")
        #expect(result.meetings.map(\.day) == ["월", "수"])
        #expect(result.meetings.allSatisfy { $0.room == "301-101" })
        let optional = CustomTimetableLogic.normalize(with(custom) { $0.professor = " "; $0.meetings = [with(meeting) { $0.room = " " }] })!
        #expect(optional.professor == nil)
        #expect(optional.meetings.count == 1 && optional.meetings[0].room == nil)
    }

    @Test func localOnlyDoesNotPretendToBeFreshCatalogCache() {
        #expect(CustomTimetableLogic.merge(nil, []) == nil)
        let result = CustomTimetableLogic.merge(nil, [custom])!
        #expect(result.fetchedAt == 0)
        #expect(result.ids == "")
        #expect(result.courses.map(\.courseId) == [-42])
    }

    @Test func catalogRefreshKeepsCustomAndMetadataSeparate() {
        let result = CustomTimetableLogic.merge(catalog, [custom])!
        #expect(result.ids == catalog.ids)
        #expect(result.fetchedAt == catalog.fetchedAt)
        #expect(result.errors == catalog.errors)
        #expect(result.courses.map(\.courseId) == [42, -42])
        var refreshed = catalog
        refreshed.courses = [with(catalog.courses[0]) { $0.name = "갱신한 과목" }]
        let after = CustomTimetableLogic.merge(refreshed, result.courses.filter { $0.courseId < 0 })!
        #expect(after.courses.map(\.name) == ["갱신한 과목", "개인 수업"])
    }

    @Test func deletedOrEditedCustomDoesNotReappearFromOldMergedData() {
        let old = CustomTimetableLogic.merge(catalog, [custom])!
        #expect(CustomTimetableLogic.merge(old, [])!.courses.map(\.courseId) == [42])
        let edited = with(custom) { $0.name = "수정한 수업"; $0.meetings = [with(meeting) { $0.day = "토" }] }
        let result = CustomTimetableLogic.merge(old, [edited])!
        #expect(result.courses.count == 2)
        #expect(result.courses.last!.name == "수정한 수업")
        #expect(result.courses.last!.meetings.map(\.day) == ["토"])
    }

    @Test func persistenceAndDailyAgendaKeepCustomIdentity() {
        let merged = CustomTimetableLogic.merge(catalog, [custom])!
        let restored = T.fromJson(T.toJson(merged))!
        let local = restored.courses.filter { $0.courseId == -42 }
        #expect(local.count == 1)
        #expect(local[0].professor == "김교수")
        #expect(local[0].meetings.map(\.room) == ["301-101"])
        #expect(T.day(restored, DayDate.of(2026, 11, 23)).classes.contains { $0.courseId == -42 })
    }

    @Test func storeKeepsCustomSeparateFromCatalog() throws {
        let store = TimetableStore(InMemoryStore())
        try store.saveCustom(custom)
        #expect(store.loadCatalog() == nil)
        #expect(store.load()?.courses.map(\.courseId) == [-42])
        store.save(catalog)
        #expect(store.load()?.courses.map(\.courseId) == [42, -42])
        store.deleteCustom(-42)
        #expect(store.load()?.courses.map(\.courseId) == [42])
        #expect(throws: EtlException.self) { try store.saveCustom(with(custom) { $0.courseId = 5 }) }
    }
}
