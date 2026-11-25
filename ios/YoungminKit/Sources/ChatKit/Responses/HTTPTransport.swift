import Foundation
import os

/// 진단 로그(비밀값 없음: 메서드·호스트·경로·상태 코드와 오류 응답 앞부분만). `log stream --predicate 'subsystem == "kr.snu.etlassistant.ios"'`로 본다.
public enum DiagLog {
    public static let logger = Logger(subsystem: "kr.snu.etlassistant.ios", category: "net")
    public static func request(_ r: URLRequest, status: Int?, error: Error? = nil, body: Data? = nil) {
        let where_ = "\(r.httpMethod ?? "GET") \(r.url?.host ?? "?")\(r.url?.path ?? "")"
        if let error { logger.notice("\(where_, privacy: .public) -> error \(String(describing: error), privacy: .public)"); return }
        var tail = ""
        if let status, !(200..<300).contains(status), let body {
            tail = " " + String(decoding: body.prefix(400), as: UTF8.self).replacingOccurrences(of: "\n", with: " ")
        }
        logger.notice("\(where_, privacy: .public) -> \(status ?? -1)\(tail, privacy: .public)")
    }
}

/// 줄 단위로 읽는 HTTP 응답(스트리밍).
public struct HTTPLineResponse: Sendable {
    public var status: Int
    public var headers: [String: String]
    /// 본문 줄(빈 줄 포함, 개행 문자 제외).
    public var lines: AsyncThrowingStream<String, Error>

    public init(status: Int, headers: [String: String] = [:], lines: AsyncThrowingStream<String, Error>) {
        self.status = status
        self.headers = headers
        self.lines = lines
    }

    /// 헤더 값(대소문자 무시).
    public func header(_ name: String) -> String? {
        headers.first { $0.key.caseInsensitiveCompare(name) == .orderedSame }?.value
    }
}

/// HTTP 계층(새로 만듦). 테스트는 이 프로토콜을 흉내 내 실제 네트워크를 쓰지 않는다.
public protocol HTTPTransport: Sendable {
    /// 한 번에 받는 요청(토큰, 모델 목록, 키 목록 등).
    func data(for request: URLRequest) async throws -> (Data, HTTPURLResponse)
    /// SSE 스트리밍 요청.
    func lines(for request: URLRequest) async throws -> HTTPLineResponse
}

/// URLSession 구현. 리다이렉트는 따라가지 않는다(토큰이 다른 곳으로 새지 않게, 안드로이드 OAuth 규칙과 같음).
public final class URLSessionTransport: NSObject, HTTPTransport, URLSessionTaskDelegate, @unchecked Sendable {
    private var session: URLSession!

    public init(configuration: URLSessionConfiguration = .ephemeral) {
        super.init()
        configuration.httpCookieStorage = nil
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        session = URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
    }

    public func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                           newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }

    public func data(for request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let data: Data, response: URLResponse
        do { (data, response) = try await session.data(for: request) } catch { DiagLog.request(request, status: nil, error: error); throw error }
        guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        DiagLog.request(request, status: http.statusCode, body: data)
        return (data, http)
    }

    public func lines(for request: URLRequest) async throws -> HTTPLineResponse {
        let bytes: URLSession.AsyncBytes, response: URLResponse
        do { (bytes, response) = try await session.bytes(for: request) } catch { DiagLog.request(request, status: nil, error: error); throw error }
        guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        DiagLog.request(request, status: http.statusCode)
        var headers: [String: String] = [:]
        for (k, v) in http.allHeaderFields { headers["\(k)"] = "\(v)" }
        let stream = AsyncThrowingStream<String, Error> { continuation in
            let task = Task {
                var splitter = SSELineSplitter()
                do {
                    for try await b in bytes {
                        if let line = splitter.push(b) { continuation.yield(line) }
                    }
                    if let rest = splitter.finish() { continuation.yield(rest) }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
        return HTTPLineResponse(status: http.statusCode, headers: headers, lines: stream)
    }
}
