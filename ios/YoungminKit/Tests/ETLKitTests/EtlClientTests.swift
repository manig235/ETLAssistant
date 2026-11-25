import Foundation
import Testing
@testable import ETLKit

struct EtlClientTests {

    @Test func xssiPrefixIsStripped() throws {
        let obj = try EtlClient.parseBody("while(1);{\"id\":1}", "/api/v1/x")
        #expect(try (obj as! JSONObject).getInt("id") == 1)
        let arr = try EtlClient.parseBody("  while(1);\n[{\"id\":1},{\"id\":2}]", "/api/v1/x")
        #expect((arr as! JSONArray).length() == 2)
        #expect(try (EtlClient.parseBody("{\"id\":3}", "/api/v1/x") as! JSONObject).getInt("id") == 3)
    }

    @Test func garbageBodyThrowsWithoutEchoingBody() {
        do {
            _ = try EtlClient.parseBody("<html>SECRET-ish</html>", "/api/v1/x")
            Issue.record("예외가 나야 한다")
        } catch let e as EtlException {
            #expect(e.message.contains("/api/v1/x"))
            #expect(!e.message.contains("SECRET"))
            #expect(e.kind == "parse")
        } catch {
            Issue.record("EtlException이 아님: \(error)")
        }
    }

    @Test func nextLinkParsing() {
        let h = "<https://myetl.snu.ac.kr/api/v1/courses?page=1&per_page=100>; rel=\"current\"," +
            "<https://myetl.snu.ac.kr/api/v1/courses?page=2&per_page=100>; rel=\"next\"," +
            "<https://myetl.snu.ac.kr/api/v1/courses?page=9&per_page=100>; rel=\"last\""
        #expect(EtlClient.nextLink(h) == "https://myetl.snu.ac.kr/api/v1/courses?page=2&per_page=100")
        #expect(EtlClient.nextLink("<https://x/y?page=1>; rel=\"current\", <https://x/y?page=3>; rel=\"last\"") == nil)
        #expect(EtlClient.nextLink(nil) == nil)
        #expect(EtlClient.nextLink("") == nil)
    }

    @Test func malformedTokenRejectedAndNotEchoed() throws {
        for bad in ["", "has space", "탈취", "a\nb"] {
            do {
                _ = try EtlClient(bad)
                Issue.record("accepted: \(bad)")
            } catch let e as EtlException {
                #expect(!e.message.contains(bad) || bad.isEmpty)
            }
        }
        _ = try EtlClient("abc~123-OK") // 정상 형식
    }

    @Test func onlyApiPathsAreRequested() async throws {
        let c = try EtlClient("tok-123")
        for bad in ["https://evil.example/api/v1/x", "/login", "api/v1/x", "/api/v2/x"] {
            do {
                _ = try await c.get(bad)
                Issue.record("accepted: \(bad)")
            } catch let e as EtlException {
                #expect(!e.message.contains("tok-123"))
            }
        }
    }

    @Test func permissionVersusTokenFor401() {
        // 권한 없음(토큰은 유효): 영어와 한국어 모두
        #expect(EtlClient.isPermissionDenied(#"{"status":"unauthorized","errors":[{"message":"user not authorized to perform that action"}]}"#))
        #expect(EtlClient.isPermissionDenied(#"{"errors":[{"message":"사용자에게 이 동작을 수행할 권한이 없음"}]}"#))
        // 토큰 문제
        #expect(!EtlClient.isPermissionDenied(#"{"errors":[{"message":"invalid access token."}]}"#))
        #expect(!EtlClient.isPermissionDenied(#"{"errors":[{"message":"유효하지 않은 액세스 토큰입니다"}]}"#))
        #expect(!EtlClient.isPermissionDenied(""))
        #expect(!EtlClient.isPermissionDenied("unauthorized"))
    }

    @Test func errorHintExtractsMessageOnly() {
        #expect(EtlClient.errorHint(#"{"errors":[{"message":"사용자에게 이 동작을 수행할 권한이 없음"}]}"#) ==
            " — 서버 메시지: 사용자에게 이 동작을 수행할 권한이 없음")
        #expect(EtlClient.errorHint("<html>x</html>") == "")
        #expect(EtlClient.errorHint("") == "")
    }

    @Test func errorHintsNeverReflectTokensOrSignedUrls() {
        for body in [
            #"{"message":"invalid token Bearer SECRET-ETL-TOKEN"}"#,
            #"{"message":"not authorized https://myetl.snu.ac.kr/files/1?verifier=SECRET"}"#,
            "SECRET-ETL-TOKEN https://storage.example/file?session_token=SECRET",
        ] {
            let hint = EtlClient.errorHint(body.lowercased())
            #expect(!hint.lowercased().contains("secret"))
            #expect(!hint.contains("https://"))
            #expect(!hint.contains("verifier="))
        }
    }

    @Test func originCheckUsesSchemeHostAndPortAndRejectsUserInfo() {
        #expect(EtlClient.sameOrigin("https://myetl.snu.ac.kr/api/v1/courses"))
        #expect(EtlClient.sameOrigin("https://MYETL.SNU.AC.KR:443/files/1"))
        for url in [
            "http://myetl.snu.ac.kr/api/v1/courses", "https://myetl.snu.ac.kr:8443/files/1",
            "https://myetl.snu.ac.kr.evil.example/files/1", "https://myetl.snu.ac.kr@evil.example/files/1",
            "https://attacker@myetl.snu.ac.kr/files/1", "https://evil.example/myetl.snu.ac.kr/files/1", "bad url",
        ] {
            #expect(!EtlClient.sameOrigin(url), "\(url)")
        }
    }

    @Test func pathOnlyDropsQuery() {
        #expect(EtlClient.pathOnly("https://myetl.snu.ac.kr/files/1/download?verifier=SECRET") == "/files/1/download")
        #expect(EtlClient.pathOnly("/api/v1/x?a=b") == "/api/v1/x")
    }

    // ---- 파일 이름(EtlFiles.fitName / storedName) -----------------------------

    @Test func fitNameKeepsExtensionWithinBytes() {
        let n = EtlDownloads.fitName("syllabus-" + String(repeating: "가", count: 100) + ".docx", maxBytes: 200)
        #expect(n.utf8.count <= 200)
        #expect(n.hasSuffix(".docx"))
        #expect(EtlDownloads.storedName(7, "a.pdf") == "7-a.pdf")
    }

    @Test func lockedFileIsNotDownloaded() async throws {
        let c = try EtlClient("tok-123")
        let meta = JSONObject().put("id", 5).put("locked_for_user", true).put("lock_explanation", "11/30에 열림").put("url", "https://myetl.snu.ac.kr/files/5/download")
        do {
            _ = try await EtlDownloads.saveAttachment(c, meta: meta, saveToUser: false, workspace: FileManager.default.temporaryDirectory)
            Issue.record("잠긴 파일을 받으면 안 된다")
        } catch let e as EtlException {
            #expect(e.message.contains("잠겨"))
        }
    }

    @Test func downloadRejectsNonEtlUrlsBeforeNetwork() async throws {
        let c = try EtlClient("tok-123")
        let dest = FileManager.default.temporaryDirectory.appendingPathComponent("etl-test-\(UUID().uuidString)")
        await #expect(throws: EtlException.self) { _ = try await c.download("https://evil.example/files/1", to: dest) }
        await #expect(throws: EtlException.self) { _ = try await c.download("http://myetl.snu.ac.kr/files/1", to: dest) }
    }
}
