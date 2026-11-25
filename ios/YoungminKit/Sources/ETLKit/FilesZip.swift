import Compression
import Foundation

/// raw DEFLATE(zlib 머리 없음) 풀기·묶기. Kotlin `java.util.zip.Inflater(true)`/`Deflater(.., true)` 자리.
/// `Compression` 프레임워크의 `COMPRESSION_ZLIB`가 raw DEFLATE다.
public enum RawDeflate {
    public struct Corrupt: Error, Sendable {}

    /// [src]를 [cap] 바이트 근처까지 푼다(넘으면 거기서 멈추고 앞부분만 돌려준다).
    /// 스트림이 중간에 끊겼으면(입력을 다 썼는데 끝 표시가 없으면) 그때까지 푼 앞부분을 돌려준다(Java `needsInput`처럼).
    /// 아무것도 풀지 못한 손상 데이터는 [Corrupt].
    public static func inflate(_ src: Data, cap: Int) throws -> Data {
        try process(COMPRESSION_STREAM_DECODE, src, cap: cap)
    }

    /// raw DEFLATE로 묶는다(테스트 픽스처·zip 쓰기용).
    public static func deflate(_ src: Data) -> Data {
        (try? process(COMPRESSION_STREAM_ENCODE, src, cap: Int.max)) ?? Data()
    }

    private static func process(_ op: compression_stream_operation, _ src: Data, cap: Int) throws -> Data {
        let stream = UnsafeMutablePointer<compression_stream>.allocate(capacity: 1)
        defer { stream.deallocate() }
        guard compression_stream_init(stream, op, COMPRESSION_ZLIB) == COMPRESSION_STATUS_OK else { throw Corrupt() }
        defer { compression_stream_destroy(stream) }
        let bufSize = 1 << 16
        let dst = UnsafeMutablePointer<UInt8>.allocate(capacity: bufSize)
        defer { dst.deallocate() }
        var out = Data()
        let bytes = [UInt8](src)
        return try bytes.withUnsafeBufferPointer { sp -> Data in
            stream.pointee.src_ptr = sp.baseAddress ?? UnsafePointer(dst)
            stream.pointee.src_size = bytes.count
            while true {
                stream.pointee.dst_ptr = dst
                stream.pointee.dst_size = bufSize
                let status = compression_stream_process(stream, Int32(COMPRESSION_STREAM_FINALIZE.rawValue))
                let n = bufSize - stream.pointee.dst_size
                if n > 0 { out.append(dst, count: n) }
                switch status {
                case COMPRESSION_STATUS_END:
                    return out
                case COMPRESSION_STATUS_OK:
                    if out.count >= cap { return out }   // 앞부분만 쓴다
                    if n == 0 {
                        // 입력을 다 썼는데 끝이 아니다: 끊긴 스트림이면 앞부분, 하나도 못 풀었으면 손상.
                        if out.isEmpty { throw Corrupt() }
                        return out
                    }
                default:
                    // 끊긴 스트림도 오류로 끝난다. 앞부분이 있으면 그것을 쓴다.
                    if out.isEmpty { throw Corrupt() }
                    return out
                }
            }
        }
    }
}

/// 작은 ZIP 읽기(중앙 디렉터리 기반, 저장·DEFLATE만). iOS에는 공개 ZIP API가 없어 직접 읽는다.
/// docx·pptx·hwpx처럼 신뢰할 수 없는 받은 파일을 읽으므로 모든 위치·길이를 확인하고, 압축 해제 크기에 상한을 둔다.
/// zip64·암호화 항목은 지원하지 않는다([ZipArchive.Failure]).
public struct ZipArchive: Sendable {
    public enum Failure: Error, Sendable, Equatable {
        /// zip 형식이 아니거나 구조가 손상됐다.
        case corrupt
        /// 지원하지 않는 항목(암호화, zip64, 알 수 없는 압축 방식).
        case unsupported
        /// 풀었을 때 상한을 넘는다.
        case tooLarge
    }

    public struct Entry: Sendable, Hashable {
        public let name: String
        public let method: UInt16
        public let flags: UInt16
        public let compressedSize: Int
        public let uncompressedSize: Int
        let localHeaderOffset: Int
    }

    private let data: Data
    /// 중앙 디렉터리 순서의 항목들.
    public let entries: [Entry]

    static let MAX_ENTRIES = 100_000

    public init(contentsOf url: URL) throws {
        let d: Data
        do { d = try Data(contentsOf: url, options: .alwaysMapped) } catch { throw Failure.corrupt }
        try self.init(data: d)
    }

    public init(data: Data) throws {
        self.data = data
        let b = data
        let n = b.count
        guard n >= 22 else { throw Failure.corrupt }
        // 끝에서 EOCD(PK\5\6)를 찾는다(주석은 최대 65535바이트).
        var eocd = -1
        var i = n - 22
        let lowest = max(0, n - 22 - 0xFFFF)
        while i >= lowest {
            if Self.u32(b, i) == 0x0605_4B50 { eocd = i; break }
            i -= 1
        }
        guard eocd >= 0 else { throw Failure.corrupt }
        let count = Int(Self.u16(b, eocd + 10))
        let cdSize = Int(Self.u32(b, eocd + 12))
        let cdOffset = Int(Self.u32(b, eocd + 16))
        if count == 0xFFFF || cdSize == 0xFFFF_FFFF || cdOffset == 0xFFFF_FFFF { throw Failure.unsupported }
        guard cdOffset >= 0, cdOffset <= eocd, cdSize <= eocd - cdOffset else { throw Failure.corrupt }
        var list: [Entry] = []
        var p = cdOffset
        let end = cdOffset + cdSize
        while list.count < min(count, Self.MAX_ENTRIES) {
            guard p + 46 <= end, Self.u32(b, p) == 0x0201_4B50 else { throw Failure.corrupt }
            let flags = Self.u16(b, p + 8)
            let method = Self.u16(b, p + 10)
            let comp = Int(Self.u32(b, p + 20))
            let uncomp = Int(Self.u32(b, p + 24))
            let nameLen = Int(Self.u16(b, p + 28))
            let extraLen = Int(Self.u16(b, p + 30))
            let commentLen = Int(Self.u16(b, p + 32))
            let local = Int(Self.u32(b, p + 42))
            guard p + 46 + nameLen <= end else { throw Failure.corrupt }
            let nameBytes = b.subdata(in: (b.startIndex + p + 46)..<(b.startIndex + p + 46 + nameLen))
            let name = String(decoding: nameBytes, as: UTF8.self)
            list.append(Entry(name: name, method: method, flags: flags, compressedSize: comp, uncompressedSize: uncomp, localHeaderOffset: local))
            p += 46 + nameLen + extraLen + commentLen
        }
        entries = list
    }

    public func entry(_ name: String) -> Entry? { entries.first { $0.name == name } }

    /// 항목 내용을 푼다. [maxBytes]를 넘으면 [Failure.tooLarge].
    public func read(_ e: Entry, maxBytes: Int) throws -> Data {
        if e.flags & 1 != 0 { throw Failure.unsupported }
        if e.compressedSize == 0xFFFF_FFFF || e.uncompressedSize == 0xFFFF_FFFF || e.localHeaderOffset == 0xFFFF_FFFF { throw Failure.unsupported }
        let b = data
        let p = e.localHeaderOffset
        guard p >= 0, p + 30 <= b.count, Self.u32(b, p) == 0x0403_4B50 else { throw Failure.corrupt }
        let start = p + 30 + Int(Self.u16(b, p + 26)) + Int(Self.u16(b, p + 28))
        guard start <= b.count, e.compressedSize <= b.count - start else { throw Failure.corrupt }
        let raw = b.subdata(in: (b.startIndex + start)..<(b.startIndex + start + e.compressedSize))
        switch e.method {
        case 0:
            if raw.count > maxBytes { throw Failure.tooLarge }
            return raw
        case 8:
            if raw.isEmpty { return Data() }
            let out: Data
            do { out = try RawDeflate.inflate(raw, cap: maxBytes + 1) } catch { throw Failure.corrupt }
            if out.count > maxBytes { throw Failure.tooLarge }
            return out
        default:
            throw Failure.unsupported
        }
    }

    static func u16(_ b: Data, _ off: Int) -> UInt16 {
        let s = b.startIndex + off
        return UInt16(b[s]) | (UInt16(b[s + 1]) << 8)
    }

    static func u32(_ b: Data, _ off: Int) -> UInt32 {
        let s = b.startIndex + off
        return UInt32(b[s]) | (UInt32(b[s + 1]) << 8) | (UInt32(b[s + 2]) << 16) | (UInt32(b[s + 3]) << 24)
    }
}

/// 작은 ZIP 쓰기(저장·DEFLATE). 테스트 픽스처와 앱 내부용. CRC-32를 채운다.
public enum ZipWriter {
    public static func make(_ entries: [(name: String, data: Data)], deflate: Bool = true) -> Data {
        var out = Data()
        var central = Data()
        func le16(_ v: Int, _ d: inout Data) { d.append(UInt8(v & 0xFF)); d.append(UInt8((v >> 8) & 0xFF)) }
        func le32(_ v: UInt32, _ d: inout Data) { for k in 0..<4 { d.append(UInt8((v >> (8 * UInt32(k))) & 0xFF)) } }
        for (name, raw) in entries {
            let nameBytes = Data(name.utf8)
            let packed = deflate ? RawDeflate.deflate(raw) : raw
            let method = deflate ? 8 : 0
            let crc = CRC32.checksum(raw)
            let offset = out.count
            le32(0x0403_4B50, &out); le16(20, &out); le16(0x0800, &out); le16(method, &out); le16(0, &out); le16(0, &out)
            le32(crc, &out); le32(UInt32(packed.count), &out); le32(UInt32(raw.count), &out)
            le16(nameBytes.count, &out); le16(0, &out)
            out.append(nameBytes); out.append(packed)
            le32(0x0201_4B50, &central); le16(20, &central); le16(20, &central); le16(0x0800, &central); le16(method, &central)
            le16(0, &central); le16(0, &central); le32(crc, &central); le32(UInt32(packed.count), &central); le32(UInt32(raw.count), &central)
            le16(nameBytes.count, &central); le16(0, &central); le16(0, &central); le16(0, &central); le16(0, &central)
            le32(0, &central); le32(UInt32(offset), &central)
            central.append(nameBytes)
        }
        let cdOffset = out.count
        out.append(central)
        le32(0x0605_4B50, &out); le16(0, &out); le16(0, &out); le16(entries.count, &out); le16(entries.count, &out)
        le32(UInt32(central.count), &out); le32(UInt32(cdOffset), &out); le16(0, &out)
        return out
    }
}

/// CRC-32(IEEE).
public enum CRC32 {
    private static let table: [UInt32] = (0..<256).map { i -> UInt32 in
        var c = UInt32(i)
        for _ in 0..<8 { c = (c & 1) != 0 ? 0xEDB8_8320 ^ (c >> 1) : c >> 1 }
        return c
    }

    public static func checksum(_ d: Data) -> UInt32 {
        var c: UInt32 = 0xFFFF_FFFF
        for b in d { c = table[Int((c ^ UInt32(b)) & 0xFF)] ^ (c >> 8) }
        return c ^ 0xFFFF_FFFF
    }
}
